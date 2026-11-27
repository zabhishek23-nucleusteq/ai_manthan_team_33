import { ArrowLeftOutlined } from '@ant-design/icons'
import { Flex, Typography } from 'antd'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/** Page frame for employee screens: back link, title, optional actions; 16px gutter at 360px. */
export function CustomerPage({
  title,
  back,
  extra,
  children,
}: {
  title: ReactNode
  back?: { to: string; label: string }
  extra?: ReactNode
  children: ReactNode
}) {
  return (
    <main style={{ maxWidth: 1100, margin: '0 auto', padding: '16px 16px 96px' }}>
      {back ? (
        <Link to={back.to} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minHeight: 44 }}>
          <ArrowLeftOutlined aria-hidden /> {back.label}
        </Link>
      ) : null}
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        {extra}
      </Flex>
      {children}
    </main>
  )
}
