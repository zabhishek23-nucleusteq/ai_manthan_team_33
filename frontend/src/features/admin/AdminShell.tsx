import { LogoutOutlined } from '@ant-design/icons'
import { Button, Menu, Typography } from 'antd'
import type { ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/authContext'

const ITEMS = [
  { key: '/admin', label: 'Overview' },
  { key: '/admin/users', label: 'Users' },
  { key: '/admin/audit', label: 'Audit log' },
  { key: '/admin/health', label: 'Health' },
]

/** Super admin section frame: title + section navigation (works at 360px: the menu collapses into "…"). */
export function AdminShell({ title, extra, children }: { title: string; extra?: ReactNode; children: ReactNode }) {
  const { pathname } = useLocation()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <main style={{ padding: 16, maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', justifyContent: 'flex-end' }}>
        <Typography.Text type="secondary">Logged in as {user?.name ?? ''}</Typography.Text>
        <Button
          size="small"
          icon={<LogoutOutlined />}
          onClick={() => {
            logout()
            navigate('/login', { replace: true })
          }}
        >
          Log out
        </Button>
      </div>
      <nav aria-label="Admin sections">
        <Menu
          mode="horizontal"
          selectedKeys={[pathname]}
          items={ITEMS.map((i) => ({ key: i.key, label: <Link to={i.key}>{i.label}</Link> }))}
        />
      </nav>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', justifyContent: 'space-between', margin: '16px 0' }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        {extra}
      </div>
      {children}
    </main>
  )
}
