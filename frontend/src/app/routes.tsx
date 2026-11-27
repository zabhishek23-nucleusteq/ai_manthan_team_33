import { Result } from 'antd'
import type { ReactElement } from 'react'
import { Route } from 'react-router-dom'
import { AdminAuditPage } from '../features/admin/AdminAuditPage'
import { AdminHealthPage } from '../features/admin/AdminHealthPage'
import { AdminOverviewPage } from '../features/admin/AdminOverviewPage'
import { AdminUsersPage } from '../features/admin/AdminUsersPage'
import { RequireAuth, RequireRole } from '../features/auth/guards'
import { ClaimDetailPage } from '../features/customer/ClaimDetailPage'
import { ClaimEditorPage } from '../features/customer/ClaimEditorPage'
import { ClaimOutcomePage } from '../features/customer/ClaimOutcomePage'
import { ClaimsListPage } from '../features/customer/ClaimsListPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { PayoutsPage } from '../features/finance/PayoutsPage'
import { PolicyRulesPage } from '../features/finance/PolicyRulesPage'
import { ApprovalDetailPage } from '../features/staff/ApprovalDetailPage'
import { ApprovalsInboxPage } from '../features/staff/ApprovalsInboxPage'
import { DelegationsPage } from '../features/staff/DelegationsPage'
import { AppLayout } from './AppLayout'
import { rolesOf, type Role } from './roles'
import './app.css'

function ComingSoon({ title, task }: { title: string; task: string }) {
  return (
    <main>
      <Result status="info" title={title} subTitle={`This screen is being built (${task}).`} />
    </main>
  )
}

/** Signed-in pages. Roles come from NAV_ITEMS (rbac.md §2) so menu and guard always agree. */
const PAGES: { path: string; roles: readonly Role[]; element: ReactElement }[] = [
  // Employee (T-022 / T-023, L4)
  { path: '/me/dashboard', roles: rolesOf('/me/dashboard'), element: <DashboardPage title="My dashboard" /> },
  { path: '/claims', roles: rolesOf('/claims'), element: <ClaimsListPage /> },
  { path: '/claims/new', roles: rolesOf('/claims/new'), element: <ClaimEditorPage /> },
  { path: '/claims/:id/edit', roles: rolesOf('/claims'), element: <ClaimEditorPage /> },
  { path: '/claims/:id/submitted', roles: rolesOf('/claims'), element: <ClaimOutcomePage /> },
  { path: '/claims/:id', roles: rolesOf('/claims'), element: <ClaimDetailPage /> },
  // Approvers
  { path: '/manager/dashboard', roles: rolesOf('/manager/dashboard'), element: <DashboardPage title="Team dashboard" /> },
  { path: '/approvals', roles: rolesOf('/approvals'), element: <ApprovalsInboxPage /> },
  { path: '/approvals/:claimId', roles: rolesOf('/approvals'), element: <ApprovalDetailPage /> },
  { path: '/delegations', roles: rolesOf('/delegations'), element: <DelegationsPage /> },
  // Finance
  { path: '/finance/dashboard', roles: rolesOf('/finance/dashboard'), element: <DashboardPage title="Finance dashboard" /> },
  { path: '/audit-queue', roles: rolesOf('/audit-queue'), element: <ComingSoon title="Audit queue" task="T-026" /> },
  // Read access per rbac.md §3 (T-031): auditor views batches; MGR/AUD/ADMIN view rules. Menu stays per §2.
  { path: '/payouts', roles: ['FINANCE_AUDITOR', 'FINANCE_HEAD'], element: <PayoutsPage /> },
  { path: '/policy-rules', roles: ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN'], element: <PolicyRulesPage /> },
  // Super admin (T-034)
  { path: '/admin', roles: rolesOf('/admin'), element: <AdminOverviewPage /> },
  { path: '/admin/users', roles: rolesOf('/admin/users'), element: <AdminUsersPage /> },
  { path: '/admin/audit', roles: rolesOf('/admin/audit'), element: <AdminAuditPage /> },
  { path: '/admin/health', roles: rolesOf('/admin/health'), element: <AdminHealthPage /> },
]

/** Fragment of <Route>s to place inside the app's <Routes>: the shell wraps every signed-in page. */
export function appRoutes() {
  return (
    <Route
      element={
        <RequireAuth>
          <AppLayout />
        </RequireAuth>
      }
    >
      {PAGES.map((p) => (
        <Route key={p.path} path={p.path} element={<RequireRole roles={p.roles}>{p.element}</RequireRole>} />
      ))}
    </Route>
  )
}
