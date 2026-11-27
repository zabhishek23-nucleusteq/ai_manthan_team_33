import { Badge, Card, Col, Row, Statistic } from 'antd'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { AdminShell } from './AdminShell'
import { errorText } from './adminModel'
import { useAdminHealth } from './adminApi'

/** /admin/health (FR-15): service status cards, 5-minute error rate, oldest pending approval. Refreshes every 30 s. */
export function AdminHealthPage() {
  const h = useAdminHealth()

  let body
  if (h.isPending) body = <PageLoading label="Checking services…" />
  else if (h.isError) body = <PageError message={errorText(h.error)} onRetry={() => void h.refetch()} />
  else if (h.data.services.length === 0) body = <PageEmpty description="No services reported" />
  else {
    const errPct = h.data.errorRate5m * 100
    body = (
      <>
        <Row gutter={[16, 16]}>
          {h.data.services.map((s) => (
            <Col key={s.name} xs={24} sm={12} lg={8}>
              <Card>
                <Badge
                  status={s.status === 'UP' ? 'success' : 'error'}
                  text={
                    <span>
                      <strong>{s.name}</strong>: {s.status}
                    </span>
                  }
                />
                <div style={{ marginTop: 8, opacity: 0.75 }}>{s.latencyMs} ms</div>
              </Card>
            </Col>
          ))}
        </Row>
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24} sm={12}>
            <Card>
              <Statistic title="Error rate (last 5 min)" value={errPct} precision={2} suffix="%" />
            </Card>
          </Col>
          <Col xs={24} sm={12}>
            <Card>
              <Statistic
                title="Oldest pending approval"
                value={h.data.oldestPendingApprovalHours ?? '-'}
                precision={h.data.oldestPendingApprovalHours == null ? undefined : 1}
                suffix={h.data.oldestPendingApprovalHours == null ? undefined : 'h'}
              />
            </Card>
          </Col>
        </Row>
      </>
    )
  }

  return <AdminShell title="System health">{body}</AdminShell>
}
