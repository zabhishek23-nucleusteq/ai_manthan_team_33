import { FlagOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Flex, Grid, List, Pagination, Select, Space, Table, Typography, type TableColumnsType } from 'antd'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import type { ClaimStatus, ClaimSummary } from '../../api/types'
import { ClaimStatusTag } from '../../components/ClaimStatusTag'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { CLAIM_STATUS } from '../../design/claimStatus'
import { useMyClaims } from './claimsApi'
import { CustomerPage } from './CustomerPage'
import { claimHref, fmtDate, inr } from './format'
import { VIOLATION_SHORT } from './policyHints'

const PAGE_SIZE = 10

const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  ...Object.entries(CLAIM_STATUS).map(([value, { label }]) => ({ value, label })),
]

/** Needs-my-reply first (wireframes §1), then the server order. */
const sortForEmployee = (items: ClaimSummary[]) =>
  [...items].sort(
    (a, b) => Number(b.status === 'CLARIFICATION_REQUESTED') - Number(a.status === 'CLARIFICATION_REQUESTED'),
  )

function Flags({ codes }: { codes: ClaimSummary['violationCodes'] }) {
  if (!codes?.length) return null
  return (
    <Typography.Text type="warning">
      <FlagOutlined aria-hidden /> {codes.length} flag{codes.length > 1 ? 's' : ''}:{' '}
      {codes.map((c) => VIOLATION_SHORT[c] ?? c).join(', ')}
    </Typography.Text>
  )
}

export function ClaimsListPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md // below 768px -> cards

  const status = (params.get('status') || undefined) as ClaimStatus | undefined
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const { data, isPending, isError, refetch, isFetching } = useMyClaims(status, page, PAGE_SIZE)

  const setFilter = (s: string) => {
    const next = new URLSearchParams(params)
    if (s) next.set('status', s)
    else next.delete('status')
    next.delete('page')
    setParams(next, { replace: true })
  }
  const setPage = (p: number) => {
    const next = new URLSearchParams(params)
    if (p > 0) next.set('page', String(p))
    else next.delete('page')
    setParams(next)
  }

  const newButton = (
    <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/claims/new')}>
      New claim
    </Button>
  )

  const items = sortForEmployee(data?.items ?? [])

  const columns: TableColumnsType<ClaimSummary> = [
    { title: 'Claim', dataIndex: 'code', key: 'code', render: (code: string, c) => <Link to={claimHref(c)}>{code}</Link> },
    { title: 'Title', dataIndex: 'title', key: 'title' },
    { title: 'Submitted', dataIndex: 'submittedAt', key: 'submittedAt', render: (v?: string) => fmtDate(v) },
    { title: 'Lines', dataIndex: 'lineCount', key: 'lineCount', align: 'right' },
    { title: 'Amount', dataIndex: 'total', key: 'total', align: 'right', render: (v: number) => inr(v) },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (s: string) => <ClaimStatusTag status={s} /> },
    {
      title: 'Flags',
      dataIndex: 'violationCodes',
      key: 'flags',
      render: (codes: ClaimSummary['violationCodes']) =>
        codes?.length ? (
          <Typography.Text type="warning">
            <FlagOutlined aria-hidden /> {codes.length}
          </Typography.Text>
        ) : null,
    },
  ]

  let body
  if (isPending) body = <PageLoading label="Loading your claims…" />
  else if (isError) body = <PageError title="Could not load claims" message="Please try again." onRetry={() => void refetch()} />
  else if (items.length === 0 && status)
    body = (
      <PageEmpty
        description={`No claims with status ${CLAIM_STATUS[status]?.label ?? status}`}
        actionLabel="Show all"
        onAction={() => setFilter('')}
      />
    )
  else if (items.length === 0)
    body = (
      <PageEmpty
        description="No claims yet"
        actionLabel="Create your first claim"
        onAction={() => navigate('/claims/new')}
      />
    )
  else if (isMobile)
    body = (
      <List
        dataSource={items}
        loading={isFetching}
        split={false}
        renderItem={(c) => (
          <List.Item style={{ padding: '0 0 12px' }}>
            <Link to={claimHref(c)} style={{ display: 'block', width: '100%', color: 'inherit' }} aria-label={`${c.code} ${c.title}, ${CLAIM_STATUS[c.status]?.label ?? c.status}`}>
              <Card size="small" hoverable>
                <Flex justify="space-between" align="center" gap={8}>
                  <Typography.Text strong>{c.code}</Typography.Text>
                  <ClaimStatusTag status={c.status} />
                </Flex>
                <div>{c.title}</div>
                <Typography.Text type="secondary">
                  {inr(c.total)} · {c.lineCount} line{c.lineCount === 1 ? '' : 's'} · {fmtDate(c.submittedAt ?? c.updatedAt)}
                </Typography.Text>
                <div>
                  <Flags codes={c.violationCodes} />
                </div>
              </Card>
            </Link>
          </List.Item>
        )}
      />
    )
  else
    body = (
      <Table<ClaimSummary>
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={isFetching}
        pagination={false}
        scroll={{ x: 800 }}
        onRow={(c) => ({ onDoubleClick: () => navigate(claimHref(c)) })}
      />
    )

  return (
    <CustomerPage title="My claims" extra={newButton}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Select
          aria-label="Filter by status"
          value={status ?? ''}
          onChange={setFilter}
          options={STATUS_OPTIONS}
          style={{ width: isMobile ? '100%' : 240 }}
        />
        {body}
        {data && data.total > PAGE_SIZE ? (
          <Flex justify="center">
            <Pagination
              current={page + 1}
              pageSize={PAGE_SIZE}
              total={data.total}
              onChange={(p) => setPage(p - 1)}
              simple={isMobile}
              showSizeChanger={false}
            />
          </Flex>
        ) : null}
      </Space>
    </CustomerPage>
  )
}
