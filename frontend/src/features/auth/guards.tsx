import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './authContext'
import { ForbiddenPage } from './ForbiddenPage'
import { hasAnyRole } from './roles'
import { loginUrlFor } from './session'
import type { Role } from './types'

/** Not logged in -> /login?next=<where you wanted to go>. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    return <Navigate to={loginUrlFor(location.pathname + location.search)} replace />
  }
  return <>{children}</>
}

/** Logged in but without any of `roles` -> Ant Result 403 (URL stays, so the user sees what was refused). */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { isAuthenticated, roles: userRoles } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    return <Navigate to={loginUrlFor(location.pathname + location.search)} replace />
  }
  if (!hasAnyRole(userRoles, roles)) return <ForbiddenPage />
  return <>{children}</>
}
