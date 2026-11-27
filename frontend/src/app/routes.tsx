import { Result } from 'antd'
import type { ReactElement } from 'react'
import { Route } from 'react-router-dom'
import { DASHBOARDS, RoleDashboard } from '../features/dashboard/RoleDashboard'
import { AppLayout } from './AppLayout'
import { DemoLogin } from './DemoLogin'
import { LandingRedirect, LOGIN_ROUTE, RequireAuth, RequireRole } from './guards'
import { NAV_ITEMS, type Role } from './roles'
import './app.css'

function ComingSoon({ title, task }: { title: string; task: string }) {
  return <Result status="info" title={title} subTitle={`This screen is being built (${task}).`} />
}

const rolesOf = (path: string): readonly Role[] => {
  const item = NAV_ITEMS.find((i) => i.path === path)
  if (!item) throw new Error(`No nav item for ${path}`)
  return item.roles
}

/** Route table for signed-in pages. Roles come from NAV_ITEMS (rbac.md §2). */
const PAGES: { path: string; roles: readonly Role[]; element: ReactElement }[] = [
  { path: '/me/dashboard', roles: rolesOf('/me/dashboard'), element: <RoleDashboard def={DASHBOARDS.employee} /> },
  { path: '/claims', roles: rolesOf('/claims'), element: <ComingSoon title="My claims" task="T-022" /> },
  { path: '/claims/new', roles: rolesOf('/claims/new'), element: <ComingSoon title="New claim" task="T-022" /> },
  { path: '/claims/:id', roles: rolesOf('/claims'), element: <ComingSoon title="Claim detail" task="T-023" /> },
  { path: '/manager/dashboard', roles: rolesOf('/manager/dashboard'), element: <RoleDashboard def={DASHBOARDS.manager} /> },
  { path: '/approvals', roles: rolesOf('/approvals'), element: <ComingSoon title="Approvals" task="T-025" /> },
  { path: '/approvals/:claimId', roles: rolesOf('/approvals'), element: <ComingSoon title="Claim review" task="T-025" /> },
  { path: '/delegations', roles: rolesOf('/delegations'), element: <ComingSoon title="Delegation" task="T-025" /> },
  { path: '/finance/dashboard', roles: rolesOf('/finance/dashboard'), element: <RoleDashboard def={DASHBOARDS.finance} /> },
  { path: '/audit-queue', roles: rolesOf('/audit-queue'), element: <ComingSoon title="Audit queue" task="T-026" /> },
  { path: '/payouts', roles: rolesOf('/payouts'), element: <ComingSoon title="Payouts" task="T-031" /> },
  { path: '/policy-rules', roles: rolesOf('/policy-rules'), element: <ComingSoon title="Policy rules" task="T-031" /> },
  { path: '/admin', roles: rolesOf('/admin'), element: <RoleDashboard def={DASHBOARDS.admin} /> },
  { path: '/admin/users', roles: rolesOf('/admin/users'), element: <ComingSoon title="Users" task="T-034" /> },
  { path: '/admin/audit', roles: rolesOf('/admin/audit'), element: <ComingSoon title="Audit log" task="T-034" /> },
  { path: '/admin/health', roles: rolesOf('/admin/health'), element: <ComingSoon title="System health" task="T-034" /> },
]

/** Fragment of <Route>s to place inside the app's <Routes>. */
export function appRoutes() {
  return (
    <>
      <Route path={LOGIN_ROUTE} element={<DemoLogin />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<LandingRedirect />} />
        {PAGES.map((p) => (
          <Route key={p.path} path={p.path} element={<RequireRole roles={p.roles}>{p.element}</RequireRole>} />
        ))}
      </Route>
    </>
  )
}
