import { AuditOutlined, CameraOutlined, CheckCircleOutlined, WalletOutlined } from '@ant-design/icons'
import { Card, Col, Flex, Grid, Row, Typography, theme } from 'antd'
import type { ReactNode } from 'react'

const BENEFITS: Array<[ReactNode, string, string]> = [
  [<CameraOutlined key="c" />, 'Snap receipts on your phone', 'Claim a taxi or a hotel stay in under a minute.'],
  [<CheckCircleOutlined key="k" />, 'Instant policy check', 'See right away if a line needs approval and why.'],
  [<AuditOutlined key="a" />, 'Track every step', 'Manager, finance and payout, with a full audit trail.'],
]

/** Brand mark: icon in a rounded square + product name (decorative icon, text carries the name). */
function BrandMark({ onDark }: { onDark: boolean }) {
  const { token } = theme.useToken()
  return (
    <Flex align="center" gap={10}>
      <span
        aria-hidden
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 36,
          height: 36,
          borderRadius: 10,
          fontSize: 20,
          background: onDark ? 'rgba(255,255,255,0.16)' : token.colorPrimary,
          color: '#ffffff',
        }}
      >
        <WalletOutlined />
      </span>
      <Typography.Text strong style={{ fontSize: 18, color: onDark ? '#ffffff' : token.colorText }}>
        ExpenseFlow
      </Typography.Text>
    </Flex>
  )
}

/**
 * Login / Sign-up frame: brand panel (>= 992px) next to the form card; on phones a compact brand header above
 * the card. White text on the #1f5fbf..#143f80 gradient is >= 6:1.
 */
export function AuthLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { token } = theme.useToken()
  const screens = Grid.useBreakpoint()
  const wide = !!screens.lg

  const form = (
    <Card
      style={{ width: '100%', maxWidth: 420, boxShadow: '0 8px 24px rgba(16, 24, 40, 0.08)' }}
      styles={{ body: { padding: wide ? 32 : 20 } }}
    >
      {!wide ? (
        <div style={{ marginBottom: 12 }}>
          <BrandMark onDark={false} />
        </div>
      ) : null}
      <Typography.Title level={2} style={{ marginTop: 0, marginBottom: 4 }}>
        {title}
      </Typography.Title>
      {subtitle ? <Typography.Paragraph type="secondary">{subtitle}</Typography.Paragraph> : null}
      {children}
    </Card>
  )

  return (
    <main style={{ minHeight: '100vh', background: token.colorBgLayout }}>
      <Row style={{ minHeight: '100vh' }}>
        {wide ? (
          <Col
            lg={11}
            xl={10}
            style={{
              background: 'linear-gradient(160deg, #1f5fbf 0%, #143f80 100%)',
              color: '#ffffff',
              padding: '48px 56px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
            }}
          >
            <BrandMark onDark />
            <div>
              <Typography.Title level={1} style={{ color: '#ffffff', fontSize: 34, lineHeight: 1.25, marginBottom: 12 }}>
                Expense claims without the chasing.
              </Typography.Title>
              <Typography.Paragraph style={{ color: '#ffffff', opacity: 0.92, fontSize: 16, marginBottom: 32 }}>
                Policy checks, approvals and payouts in one place.
              </Typography.Paragraph>
              <Flex vertical gap={20}>
                {BENEFITS.map(([icon, head, text]) => (
                  <Flex key={head} gap={14} align="flex-start">
                    <span
                      aria-hidden
                      style={{
                        fontSize: 18,
                        width: 36,
                        height: 36,
                        flex: 'none',
                        borderRadius: 18,
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        background: 'rgba(255,255,255,0.16)',
                      }}
                    >
                      {icon}
                    </span>
                    <div>
                      <div style={{ fontWeight: 600, fontSize: 15 }}>{head}</div>
                      <div style={{ opacity: 0.92 }}>{text}</div>
                    </div>
                  </Flex>
                ))}
              </Flex>
            </div>
            <Typography.Text style={{ color: '#ffffff', opacity: 0.85 }}>Synthetic demo data only.</Typography.Text>
          </Col>
        ) : null}
        <Col xs={24} lg={13} xl={14}>
          <Flex justify="center" align="center" style={{ minHeight: '100vh', padding: 16 }}>
            {form}
          </Flex>
        </Col>
      </Row>
    </main>
  )
}
