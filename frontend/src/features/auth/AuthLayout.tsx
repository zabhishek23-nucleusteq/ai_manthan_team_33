import { Card, Flex, Typography, theme } from 'antd'
import type { ReactNode } from 'react'

/** Centered card used by Login and Sign-up; full width with a 16px gutter at 360px. */
export function AuthLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { token } = theme.useToken()
  return (
    <Flex
      component="main"
      justify="center"
      align="center"
      style={{ minHeight: '100vh', padding: 16, background: token.colorBgLayout }}
    >
      <Card style={{ width: '100%', maxWidth: 420 }}>
        <Typography.Text type="secondary" strong>
          ExpenseFlow
        </Typography.Text>
        <Typography.Title level={2} style={{ marginTop: 4 }}>
          {title}
        </Typography.Title>
        {subtitle ? <Typography.Paragraph type="secondary">{subtitle}</Typography.Paragraph> : null}
        {children}
      </Card>
    </Flex>
  )
}
