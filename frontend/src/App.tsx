import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Button, Result } from 'antd'
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { DesignPage } from './design/DesignPage'
import { ThemeProvider } from './design/ThemeProvider'
import { AuthProvider } from './features/auth/AuthProvider'
import { useAuth } from './features/auth/authContext'
import { ForbiddenPage } from './features/auth/ForbiddenPage'
import { RequireRole } from './features/auth/guards'
import { LandingPlaceholder } from './features/auth/LandingPlaceholder'
import { LoginPage } from './features/auth/LoginPage'
import { landingRouteFor } from './features/auth/roles'
import { SignupPage } from './features/auth/SignupPage'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

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
            <LandingPlaceholder title="My dashboard" />
          </RequireRole>
        }
      />
      <Route
        path="/manager/dashboard"
        element={
          <RequireRole roles={['MANAGER']}>
            <LandingPlaceholder title="Team dashboard" />
          </RequireRole>
        }
      />
      <Route
        path="/finance/dashboard"
        element={
          <RequireRole roles={['FINANCE_AUDITOR', 'FINANCE_HEAD']}>
            <LandingPlaceholder title="Finance dashboard" />
          </RequireRole>
        }
      />
      <Route
        path="/admin"
        element={
          <RequireRole roles={['SUPER_ADMIN']}>
            <LandingPlaceholder title="Admin overview" />
          </RequireRole>
        }
      />

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
