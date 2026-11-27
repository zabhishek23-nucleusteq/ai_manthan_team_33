import { Alert, Button, Card, Col, Row, Statistic, Tag } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useRef } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { PageEmpty, PageLoading } from '../../components/PageState'
import { CustomerPage } from '../customer/CustomerPage'
import { BarList } from './BarList'
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

function KpiBody({ k }: { k: Kpis }) {
  const t = k.totals
  const cards: { title: string; value: string | number; testId: string }[] = [
    { title: 'Spend', value: inr(t.spend), testId: 'kpi-spend' },
    { title: 'Claims', value: t.claims, testId: 'kpi-claims' },
    { title: 'Pending approvals', value: t.pendingApprovals, testId: 'kpi-pending' },
    { title: 'Policy violations', value: t.violations, testId: 'kpi-violations' },
    {
      title: 'Avg approval time',
      value: t.avgApprovalHours == null ? '—' : `${t.avgApprovalHours} h`,
      testId: 'kpi-approval-hours',
    },
  ]
  return (
    <>
      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {cards.map((c) => (
          <Col key={c.title} xs={12} sm={8} lg={4}>
            <Card size="small" data-testid={c.testId}>
              <Statistic title={c.title} value={c.value} />
            </Card>
          </Col>
        ))}
      </Row>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <BarList
            title={`Spend over time (${k.range.bucket.toLowerCase()})`}
            items={k.series.map((p) => ({ label: bucketLabel(p.start, k.range.bucket), value: p.spend }))}
            format={inr}
          />
        </Col>
        <Col xs={24} md={12}>
          <BarList
            title="Spend by category"
            items={k.spendByCategory.map((c) => ({ label: c.category, value: c.spend }))}
            format={inr}
          />
        </Col>
        {k.scope !== 'OWN' ? (
          <Col xs={24} md={12}>
            <BarList
              title="Spend by department"
              items={k.spendByDepartment.map((d) => ({ label: d.department, value: d.spend }))}
              format={inr}
            />
          </Col>
        ) : null}
        <Col xs={24} md={12}>
          <BarList
            title="Spend by month"
            items={k.spendByMonth.map((m) => ({ label: dayjs(`${m.month}-01`).format('MMM YYYY'), value: m.spend }))}
            format={inr}
          />
        </Col>
        <Col xs={24} md={12}>
          <BarList
            title="Violations by rule"
            items={k.violationsByRule.map((v) => ({ label: v.code.replaceAll('_', ' ').toLowerCase(), value: v.count }))}
          />
        </Col>
        <Col xs={24} md={12}>
          <BarList
            title="Average approval time by level (hours)"
            items={k.approvalTimeByLevel.map((l) => ({ label: l.level, value: l.avgHours }))}
            format={(v) => `${v} h`}
          />
        </Col>
      </Row>
    </>
  )
}
