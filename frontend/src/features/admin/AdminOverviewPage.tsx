import { Card, Col, Row, Statistic, Table } from 'antd'
import { useState } from 'react'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { ROLE_LABEL } from '../auth/roles'
import type { Role } from '../auth/types'
import { AdminShell } from './AdminShell'
import { errorText } from './adminModel'
import { type Range, useAdminOverview } from './adminApi'
import { rangeFor } from './adminModel'
import { DateRangeFilter } from './DateRangeFilter'

const inr = (n: number | undefined) =>
  n === undefined ? '-' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n)

/** /admin (FR-15): system overview for the selected range. */
export function AdminOverviewPage() {
  const [range, setRange] = useState<Range>(() => rangeFor('7D'))
  const o = useAdminOverview(range)

  let body
  if (o.isPending) body = <PageLoading label="Loading overview…" />
  else if (o.isError) body = <PageError message={errorText(o.error)} onRetry={() => void o.refetch()} />
  else if (o.data.totalUsers === 0) body = <PageEmpty description="No users or claims yet" />
  else {
    const t = o.data.totals
    const roles = Object.entries(o.data.usersByRole).map(([role, count]) => ({ role, count }))
    body = (
      <>
        <Row gutter={[16, 16]}>
          {[
            { title: 'Total users', value: o.data.totalUsers },
            { title: 'Active users', value: o.data.activeUsers },
            { title: 'New users in range', value: o.data.newUsersInRange },
            { title: 'Claims in range', value: t?.claims ?? 0 },
            { title: 'Spend in range', value: inr(t?.spend) },
            { title: 'Pending approvals', value: t?.pendingApprovals ?? 0 },
          ].map((s) => (
            <Col key={s.title} xs={24} sm={12} lg={8}>
              <Card>
                <Statistic title={s.title} value={s.value} />
              </Card>
            </Col>
          ))}
        </Row>
        <Card title="Users by role" style={{ marginTop: 16 }}>
          <Table
            rowKey="role"
            size="small"
            pagination={false}
            dataSource={roles}
            columns={[
              { title: 'Role', dataIndex: 'role', render: (r: string) => ROLE_LABEL[r as Role] ?? r },
              { title: 'Users', dataIndex: 'count', align: 'right' },
            ]}
          />
        </Card>
      </>
    )
  }

  return (
    <AdminShell title="Admin overview" extra={<DateRangeFilter value={range} onChange={setRange} />}>
      {body}
    </AdminShell>
  )
}
