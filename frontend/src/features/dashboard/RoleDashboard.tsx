import { InfoCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Col, Row, Statistic, Tooltip, Typography } from 'antd'
import { Link } from 'react-router-dom'

export interface KpiDef {
  title: string
  /** Shown in a tooltip: what exactly the number means (docs/ux/internal-wireframes.md §7). */
  definition: string
}

export interface DashboardDef {
  title: string
  responsibility: string
  kpis: KpiDef[]
  actions: { label: string; to: string }[]
}

/** KPI definitions per landing dashboard. Values arrive with the KPI API (T-027) and T-033. */
export const DASHBOARDS = {
  employee: {
    title: 'My dashboard',
    responsibility: 'Create and track your own expense claims.',
    kpis: [
      { title: 'My spend', definition: 'Sum of your claim lines submitted in the range, excluding drafts and rejected claims.' },
      { title: 'Pending', definition: 'Your claims currently waiting for approval or for your reply.' },
      { title: 'Reimbursed', definition: 'Sum of your claims that moved to Paid in the range.' },
      { title: 'Avg time to approval', definition: 'Mean hours from submit to final approval for your claims approved in the range.' },
    ],
    actions: [
      { label: 'New claim', to: '/claims/new' },
      { label: 'My claims', to: '/claims' },
    ],
  },
  manager: {
    title: 'Team dashboard',
    responsibility: "Approve your direct reports' claims and watch team spend.",
    kpis: [
      { title: 'Waiting for me', definition: 'Approval steps assigned to you now, including delegated ones.' },
      { title: 'Oldest waiting', definition: 'Age of the oldest approval step assigned to you.' },
      { title: 'Team spend', definition: 'Sum of claim lines of your direct reports submitted in the range, excluding drafts and rejected.' },
      { title: 'Policy violations', definition: 'Claim lines of your direct reports with at least one policy flag, submitted in the range.' },
    ],
    actions: [
      { label: 'Open approvals', to: '/approvals' },
      { label: 'Delegation', to: '/delegations' },
    ],
  },
  finance: {
    title: 'Finance dashboard',
    responsibility: 'Review high-value and flagged claims, audit samples and pay out approved claims.',
    kpis: [
      { title: 'Total spend', definition: 'Sum of claim lines submitted in the range, excluding drafts and rejected claims.' },
      { title: 'Claims submitted', definition: 'Claims with submit time in the range.' },
      { title: 'Auto-approved %', definition: 'Auto-approved claims divided by claims decided by the policy engine in the range.' },
      { title: 'Policy violations', definition: 'Claim lines with at least one flag (over limit, no receipt, duplicate, weekend) in the range.' },
      { title: 'Avg approval time', definition: 'Mean hours from submit to final decision for claims decided in the range.' },
      { title: 'Awaiting payout', definition: 'Approved claims not yet in a payout batch (live).' },
    ],
    actions: [
      { label: 'Audit queue', to: '/audit-queue' },
      { label: 'Approvals', to: '/approvals' },
    ],
  },
  admin: {
    title: 'Admin overview',
    responsibility: 'Manage users and roles, watch the whole system and its health.',
    kpis: [
      { title: 'Users', definition: 'User accounts; active = enabled.' },
      { title: 'Claims submitted', definition: 'Claims with submit time in the range, all employees.' },
      { title: 'Pending approvals', definition: 'Open approval steps across all levels (live).' },
      { title: 'Oldest pending', definition: 'Age of the oldest open approval step (live) - same metric as the SRE alert.' },
    ],
    actions: [
      { label: 'Users', to: '/admin/users' },
      { label: 'System health', to: '/admin/health' },
    ],
  },
} satisfies Record<string, DashboardDef>

/** Landing dashboard shell with placeholder KPI cards; live data + date filter come in T-033. */
export function RoleDashboard({ def }: { def: DashboardDef }) {
  return (
    <section aria-labelledby="dashboard-title">
      <Typography.Title level={3} id="dashboard-title" style={{ marginTop: 0 }}>
        {def.title}
      </Typography.Title>
      <Typography.Paragraph type="secondary">{def.responsibility}</Typography.Paragraph>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        title="Live figures and the 1 Day / 7 Days / 1 Month / Custom filter arrive with the dashboard API."
      />
      <Row gutter={[16, 16]}>
        {def.kpis.map((kpi) => (
          <Col key={kpi.title} xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title={
                  <span>
                    {kpi.title}{' '}
                    <Tooltip title={kpi.definition}>
                      <InfoCircleOutlined aria-label={`${kpi.title}: ${kpi.definition}`} tabIndex={0} />
                    </Tooltip>
                  </span>
                }
                value="-"
              />
            </Card>
          </Col>
        ))}
      </Row>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 16 }}>
        {def.actions.map((a, i) => (
          <Link key={a.to} to={a.to}>
            <Button type={i === 0 ? 'primary' : 'default'}>{a.label}</Button>
          </Link>
        ))}
      </div>
    </section>
  )
}
