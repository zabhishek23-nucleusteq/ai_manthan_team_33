import { Button, Result } from 'antd'
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { QueryProvider } from './api/QueryProvider'
import { DesignPage } from './design/DesignPage'
import { ThemeProvider } from './design/ThemeProvider'
import { AdminAuditPage } from './features/admin/AdminAuditPage'
import { AdminHealthPage } from './features/admin/AdminHealthPage'
import { AdminOverviewPage } from './features/admin/AdminOverviewPage'
import { AdminUsersPage } from './features/admin/AdminUsersPage'
import { AuthProvider } from './features/auth/AuthProvider'
import { useAuth } from './features/auth/authContext'
import { ForbiddenPage } from './features/auth/ForbiddenPage'
import { RequireRole } from './features/auth/guards'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { LoginPage } from './features/auth/LoginPage'
import { landingRouteFor } from './features/auth/roles'
import { SignupPage } from './features/auth/SignupPage'
import { ClaimDetailPage } from './features/customer/ClaimDetailPage'
import { ClaimEditorPage } from './features/customer/ClaimEditorPage'
import { ClaimOutcomePage } from './features/customer/ClaimOutcomePage'
import { ClaimsListPage } from './features/customer/ClaimsListPage'
import { PayoutsPage } from './features/finance/PayoutsPage'
import { PolicyRulesPage } from './features/finance/PolicyRulesPage'
import { ApprovalDetailPage } from './features/staff/ApprovalDetailPage'
import { ApprovalsInboxPage } from './features/staff/ApprovalsInboxPage'
import { DelegationsPage } from './features/staff/DelegationsPage'

function NotFound() {
  return (
    <Result
      status="404"
      title="Page not found"
      subTitle="The page you opened does not exist."
      extra={
        <Link to="/">
          <Button type="primary">Go home</Button>
        </Link>
      }
    />
  )
}

function Home() {
  const { isAuthenticated, roles } = useAuth()
  return <Navigate to={isAuthenticated ? landingRouteFor(roles) : '/login'} replace />
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/403" element={<ForbiddenPage />} />
      <Route path="/design" element={<DesignPage />} />

      {/* TEMPORARY landing routes (rbac.md §2) until T-006 (L5) adds the role layout + dashboards. */}
      <Route
        path="/me/dashboard"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <DashboardPage title="My dashboard" />
          </RequireRole>
        }
      />
      <Route
        path="/manager/dashboard"
        element={
          <RequireRole roles={['MANAGER']}>
            <DashboardPage title="Team dashboard" />
          </RequireRole>
        }
      />
      <Route
        path="/finance/dashboard"
        element={
          <RequireRole roles={['FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <DashboardPage title="Finance dashboard" />
          </RequireRole>
        }
      />
      {/* Super admin (T-034). */}
      <Route
        path="/admin"
        element={
          <RequireRole roles={['SUPER_ADMIN']}>
            <AdminOverviewPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/users"
        element={
          <RequireRole roles={['SUPER_ADMIN']}>
            <AdminUsersPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/audit"
        element={
          <RequireRole roles={['SUPER_ADMIN']}>
            <AdminAuditPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/health"
        element={
          <RequireRole roles={['SUPER_ADMIN']}>
            <AdminHealthPage />
          </RequireRole>
        }
      />

      {/* Employee claim capture (T-022) and claim detail with timeline/comments (T-023). */}
      <Route
        path="/claims"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <ClaimsListPage />
          </RequireRole>
        }
      />
      <Route
        path="/claims/new"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <ClaimEditorPage />
          </RequireRole>
        }
      />
      <Route
        path="/claims/:id/edit"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <ClaimEditorPage />
          </RequireRole>
        }
      />
      <Route
        path="/claims/:id/submitted"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <ClaimOutcomePage />
          </RequireRole>
        }
      />
      <Route
        path="/claims/:id"
        element={
          <RequireRole roles={['EMPLOYEE']}>
            <ClaimDetailPage />
          </RequireRole>
        }
      />

      {/* Approver inbox, claim review with receipt viewer, delegation (T-025). */}
      <Route
        path="/approvals"
        element={
          <RequireRole roles={['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <ApprovalsInboxPage />
          </RequireRole>
        }
      />
      <Route
        path="/approvals/:claimId"
        element={
          <RequireRole roles={['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <ApprovalDetailPage />
          </RequireRole>
        }
      />
      <Route
        path="/delegations"
        element={
          <RequireRole roles={['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <DelegationsPage />
          </RequireRole>
        }
      />

      {/* Finance head payout batches + policy rules (T-031; read access per rbac.md section 3). */}
      <Route
        path="/payouts"
        element={
          <RequireRole roles={['FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <PayoutsPage />
          </RequireRole>
        }
      />
      <Route
        path="/policy-rules"
        element={
          <RequireRole roles={['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN']}>
            <PolicyRulesPage />
          </RequireRole>
        }
      />

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default function App() {
  return (
    <QueryProvider>
      <ThemeProvider>
        <AuthProvider>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryProvider>
  )
}
