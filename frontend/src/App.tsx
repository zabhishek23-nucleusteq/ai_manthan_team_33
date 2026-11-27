import { Button, Result } from 'antd'
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { QueryProvider } from './api/QueryProvider'
import { appRoutes } from './app/routes'
import { DesignPage } from './design/DesignPage'
import { ThemeProvider } from './design/ThemeProvider'
import { AuthProvider } from './features/auth/AuthProvider'
import { useAuth } from './features/auth/authContext'
import { ForbiddenPage } from './features/auth/ForbiddenPage'
import { LoginPage } from './features/auth/LoginPage'
import { landingRouteFor } from './features/auth/roles'
import { SignupPage } from './features/auth/SignupPage'

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

      {/* Signed-in pages: role layout, menu and every role screen (T-006, src/app/routes.tsx). */}
      {appRoutes()}
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
