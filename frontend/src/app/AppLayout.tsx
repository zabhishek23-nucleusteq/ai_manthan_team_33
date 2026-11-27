import {
  AuditOutlined,
  BankOutlined,
  CheckSquareOutlined,
  DashboardOutlined,
  FileAddOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HeartOutlined,
  LogoutOutlined,
  MenuOutlined,
  MoonOutlined,
  SettingOutlined,
  SunOutlined,
  SwapOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Button, Drawer, Grid, Layout, Menu, Tag, Tooltip, Typography, theme, type MenuProps } from 'antd'
import { useMemo, useState, type ReactNode } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useThemeMode } from '../design/themeModeContext'
import { menuForRoles, primaryRole, ROLE_LABEL, ROLE_PRIORITY, selectedMenuKey, type NavItem } from './roles'
import { useSession } from './session'

const ICONS: Record<string, ReactNode> = {
  '/me/dashboard': <DashboardOutlined />,
  '/claims': <FileTextOutlined />,
  '/claims/new': <FileAddOutlined />,
  '/manager/dashboard': <TeamOutlined />,
  '/approvals': <CheckSquareOutlined />,
  '/delegations': <SwapOutlined />,
  '/finance/dashboard': <DashboardOutlined />,
  '/audit-queue': <FileSearchOutlined />,
  '/payouts': <BankOutlined />,
  '/policy-rules': <SettingOutlined />,
  '/admin': <DashboardOutlined />,
  '/admin/users': <TeamOutlined />,
  '/admin/audit': <AuditOutlined />,
  '/admin/health': <HeartOutlined />,
}

/** Groups the flat nav list into Ant Menu groups, keeping order. */
export function toMenuItems(items: readonly NavItem[]): MenuProps['items'] {
  const groups = new Map<string, NavItem[]>()
  for (const item of items) groups.set(item.group, [...(groups.get(item.group) ?? []), item])
  return [...groups].map(([group, children]) => ({
    type: 'group' as const,
    key: `group:${group}`,
    label: group,
    children: children.map((c) => ({ key: c.path, label: c.label, icon: ICONS[c.path] })),
  }))
}

/** Staff + employee shell: header (user, roles, theme, logout) and role-filtered menu (drawer below lg). */
export function AppLayout() {
  const { user, logout } = useSession()
  const { mode, toggle } = useThemeMode()
  const screens = Grid.useBreakpoint()
  const isDesktop = !!screens.lg
  const [drawerOpen, setDrawerOpen] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { token } = theme.useToken()

  const roles = useMemo(() => user?.roles ?? [], [user])
  const nav = useMemo(() => menuForRoles(roles), [roles])
  const selected = selectedMenuKey(location.pathname, nav)
  const sortedRoles = ROLE_PRIORITY.filter((r) => roles.includes(r))
  const mainRole = primaryRole(roles)

  const menu = (
    <Menu
      mode="inline"
      aria-label="Main menu"
      items={toMenuItems(nav)}
      selectedKeys={selected ? [selected] : []}
      onClick={({ key }) => {
        setDrawerOpen(false)
        navigate(key)
      }}
      style={{ borderInlineEnd: 0 }}
    />
  )

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <a className="ef-skip-link" href="#main-content">
        Skip to content
      </a>
      <Layout.Header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          paddingInline: isDesktop ? 24 : 16,
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
          position: 'sticky',
          top: 0,
          zIndex: 10,
        }}
      >
        {!isDesktop && (
          <Button
            type="text"
            icon={<MenuOutlined />}
            aria-label="Open menu"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen(true)}
          />
        )}
        <Typography.Text strong style={{ fontSize: 18, whiteSpace: 'nowrap' }}>
          ExpenseFlow
        </Typography.Text>
        <div style={{ flex: 1 }} />
        {user && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }} data-testid="current-user">
            <Typography.Text ellipsis style={{ maxWidth: isDesktop ? 220 : 110 }}>
              {user.name}
            </Typography.Text>
            {(isDesktop ? sortedRoles : mainRole ? [mainRole] : []).map((r) => (
              <Tag key={r} color={r === mainRole ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
                {ROLE_LABEL[r]}
              </Tag>
            ))}
          </div>
        )}
        <Tooltip title={mode === 'dark' ? 'Light mode' : 'Dark mode'}>
          <Button
            type="text"
            aria-label={mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            icon={mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
            onClick={toggle}
          />
        </Tooltip>
        <Button
          icon={<LogoutOutlined />}
          onClick={() => {
            logout()
            navigate('/', { replace: true })
          }}
          aria-label="Log out"
        >
          {isDesktop ? 'Log out' : null}
        </Button>
      </Layout.Header>
      <Layout>
        {isDesktop ? (
          <Layout.Sider width={232} theme={mode === 'dark' ? 'dark' : 'light'} style={{ paddingTop: 8 }}>
            {menu}
          </Layout.Sider>
        ) : (
          <Drawer
            title="Menu"
            placement="left"
            open={drawerOpen}
            onClose={() => setDrawerOpen(false)}
            size={280}
            styles={{ body: { padding: 0 } }}
          >
            {menu}
          </Drawer>
        )}
        <Layout.Content
          id="main-content"
          tabIndex={-1}
          role="main"
          style={{ padding: isDesktop ? 24 : 16, outline: 'none', minWidth: 0 }}
        >
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  )
}
