import { ClockCircleOutlined, FieldTimeOutlined, FileTextOutlined, FlagOutlined, WalletOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Col, Empty, Row, Segmented, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { PageEmpty, PageLoading } from '../../components/PageState'
import { CustomerPage } from '../customer/CustomerPage'
import { CATEGORY_LABEL } from '../customer/format'
import { VIOLATION_SHORT } from '../customer/policyHints'
import { BarsChart, ChartCard, DonutWithLegend, TrendChart } from './charts'
import { KpiFlashcard } from './KpiFlashcard'
import { SERIES_COLORS } from './palette'
import { DateRangeFilter } from './DateRangeFilter'
import { isEmpty, reportPageLoad, useKpis, type Kpis } from './dashboardApi'
import { presetRange, rangeFromParams, rangeToParams, type DateRange } from './range'

const inr = (v: number) => `₹${Math.round(v).toLocaleString('en-IN')}`
const SCOPE_LABEL: Record<Kpis['scope'], string> = { OWN: 'My claims', TEAM: 'My team', ALL: 'Whole company' }

function bucketLabel(start: string, bucket: Kpis['range']['bucket']): string {
  const d = dayjs(start)
  if (bucket === 'HOUR') return d.format('D MMM HH:mm')
  if (bucket === 'WEEK') return `Week of ${d.format('D MMM')}`
  return d.format('ddd D MMM')
}

/** One step wider: 1D -> 7D -> 1M -> last 12 months. */
function widen(range: DateRange): DateRange {
  if (range.preset === '1D') return presetRange('7D')
  if (range.preset === '7D') return presetRange('1M')
  const to = dayjs().second(0).millisecond(0)
  return { preset: 'CUSTOM', from: to.subtract(365, 'day').toISOString(), to: to.toISOString() }
}

/** FR-12 role dashboard (employee / manager / finance): one URL range drives every widget; scope comes from the API. */
export function DashboardPage({ title }: { title: string }) {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const paramKey = params.toString()
  const range = useMemo(() => rangeFromParams(new URLSearchParams(paramKey)), [paramKey])
  const kpis = useKpis(range)
  const setRange = (r: DateRange) => setParams(rangeToParams(r, params))

  const mountedAt = useRef(0)
  const reported = useRef(false)
  useEffect(() => {
    mountedAt.current = performance.now()
  }, [])
  useEffect(() => {
    if (kpis.isSuccess && !reported.current) {
      reported.current = true
      reportPageLoad(location.pathname, performance.now() - mountedAt.current)
    }
  }, [kpis.isSuccess, location.pathname])

  let body
  if (kpis.isPending) {
    body = <PageLoading label="Loading dashboard…" rows={6} />
  } else if (kpis.isError) {
    body = (
      <Alert
        type="error"
        showIcon
        role="alert"
        title="Could not load the dashboard"
        description="The numbers for this range are not available right now."
        action={
          <Button size="small" onClick={() => kpis.refetch()}>
            Retry
          </Button>
        }
      />
    )
  } else if (isEmpty(kpis.data)) {
    body = (
      <PageEmpty
        description={`No data between ${dayjs(range.from).format('D MMM YYYY, HH:mm')} and ${dayjs(range.to).format('D MMM YYYY, HH:mm')}`}
        actionLabel="Widen the range"
        onAction={() => setRange(widen(range))}
      />
    )
  } else {
    body = <KpiBody k={kpis.data} />
  }

  return (
    <CustomerPage
      title={title}
      extra={kpis.data ? <Tag aria-label="Scope">{SCOPE_LABEL[kpis.data.scope]}</Tag> : null}
    >
      <DateRangeFilter value={range} onChange={setRange} />
      <div aria-busy={kpis.isFetching} aria-live="polite">
        {body}
      </div>
    </CustomerPage>
  )
}

const LEVEL_LABEL: Record<string, string> = { MANAGER: 'Manager', AUDITOR: 'Finance auditor', HEAD: 'Finance head' }

function KpiBody({ k }: { k: Kpis }) {
  const t = k.totals
  const [trendMetric, setTrendMetric] = useState<'spend' | 'claims'>('spend')
  const [focusCategory, setFocusCategory] = useState<string | null>(null)

  const cards = [
    { title: 'Spend', value: inr(t.spend), testId: 'kpi-spend', icon: <WalletOutlined />, color: SERIES_COLORS[0], definition: 'Total amount of all claim lines submitted in the selected range.' },
    { title: 'Claims', value: t.claims, testId: 'kpi-claims', icon: <FileTextOutlined />, color: SERIES_COLORS[2], definition: 'Number of claims submitted in the selected range (drafts not counted).' },
    { title: 'Pending approvals', value: t.pendingApprovals, testId: 'kpi-pending', icon: <ClockCircleOutlined />, color: SERIES_COLORS[1], definition: 'Claims waiting for a manager, auditor or finance head right now.' },
    { title: 'Policy violations', value: t.violations, testId: 'kpi-violations', icon: <FlagOutlined />, color: SERIES_COLORS[3], definition: 'Policy flags raised on submitted lines (over limit, missing receipt, duplicate, weekend).' },
    {
      title: 'Avg approval time',
      value: t.avgApprovalHours == null ? '—' : `${t.avgApprovalHours} h`,
      testId: 'kpi-approval-hours',
      icon: <FieldTimeOutlined />,
      color: SERIES_COLORS[4],
      definition: 'Average hours from submit to final decision for claims decided in the range.',
    },
  ]

  const trend = k.series.map((p) => ({ label: bucketLabel(p.start, k.range.bucket), value: trendMetric === 'spend' ? p.spend : p.claims }))
  const byCategory = k.spendByCategory.map((c) => ({ label: CATEGORY_LABEL[c.category as keyof typeof CATEGORY_LABEL] ?? c.category, value: c.spend }))
  const byDept = k.spendByDepartment.map((d) => ({ label: d.department, value: d.spend }))
  const byMonth = k.spendByMonth.map((m) => ({ label: dayjs(`${m.month}-01`).format('MMM YY'), value: m.spend }))
  const violations = k.violationsByRule.map((v) => ({ label: VIOLATION_SHORT[v.code as keyof typeof VIOLATION_SHORT] ?? v.code, value: v.count }))
  const approval = k.approvalTimeByLevel.map((l) => ({ label: LEVEL_LABEL[l.level] ?? l.level, value: l.avgHours }))
  const count = (v: number) => v.toLocaleString('en-IN')
  const hours = (v: number) => `${v} h`

  return (
    <>
      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {cards.map((c) => (
          <Col key={c.title} xs={12} sm={8} xl={{ flex: '20%' }}>
            <KpiFlashcard {...c} />
          </Col>
        ))}
      </Row>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: -4 }}>
        Tip: click a card to see what the number means. Click a category to focus it.
      </Typography.Paragraph>
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={14}>
          <ChartCard
            title={`${trendMetric === 'spend' ? 'Spend' : 'Claims'} over time (${k.range.bucket.toLowerCase()})`}
            data={trend}
            format={trendMetric === 'spend' ? inr : count}
            valueTitle={trendMetric === 'spend' ? 'Spend' : 'Claims'}
            extra={
              <Segmented
                size="small"
                style={{ marginBottom: 8 }}
                value={trendMetric}
                onChange={(v) => setTrendMetric(v as 'spend' | 'claims')}
                options={[
                  { value: 'spend', label: 'Spend' },
                  { value: 'claims', label: 'Claims' },
                ]}
                aria-label="Trend metric"
              />
            }
          >
            <TrendChart data={trend} color={trendMetric === 'spend' ? SERIES_COLORS[0] : SERIES_COLORS[2]} format={trendMetric === 'spend' ? inr : count} />
          </ChartCard>
        </Col>
        <Col xs={24} lg={10}>
          <Card size="small" title="Spend by category" style={{ height: '100%' }}>
            {byCategory.length ? (
              <DonutWithLegend title="Spend by category" data={byCategory} format={inr} focus={focusCategory} onFocus={setFocusCategory} />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No data in this range" />
            )}
          </Card>
        </Col>
        {k.scope !== 'OWN' ? (
          <Col xs={24} lg={12}>
            <ChartCard title="Spend by department" data={byDept} format={inr} valueTitle="Spend">
              <BarsChart data={byDept} format={inr} horizontal />
            </ChartCard>
          </Col>
        ) : null}
        <Col xs={24} lg={12}>
          <ChartCard title="Spend by month" data={byMonth} format={inr} valueTitle="Spend">
            <BarsChart data={byMonth} format={inr} colors={[SERIES_COLORS[5]]} />
          </ChartCard>
        </Col>
        <Col xs={24} lg={12}>
          <ChartCard title="Violations by rule" data={violations} format={count} valueTitle="Flags">
            <BarsChart data={violations} format={count} horizontal colors={[SERIES_COLORS[3], SERIES_COLORS[1], SERIES_COLORS[4], SERIES_COLORS[0], SERIES_COLORS[2]]} />
          </ChartCard>
        </Col>
        <Col xs={24} lg={12}>
          <ChartCard title="Average approval time by level" data={approval} format={hours} valueTitle="Hours">
            <BarsChart data={approval} format={hours} colors={[SERIES_COLORS[2], SERIES_COLORS[0], SERIES_COLORS[4]]} />
          </ChartCard>
        </Col>
      </Row>
    </>
  )
}
