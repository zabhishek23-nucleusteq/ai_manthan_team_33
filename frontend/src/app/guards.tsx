import { Button, Result } from 'antd'
import type { ReactNode } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { hasAnyRole, landingRoute, type Role } from './roles'
import { useSession } from './session'

export const LOGIN_ROUTE = '/demo-login'

/** No session -> login, remembering where the user wanted to go. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user } = useSession()
  const location = useLocation()
  if (!user) {
    return <Navigate to={LOGIN_ROUTE} replace state={{ from: location.pathname + location.search }} />
  }
  return <>{children}</>
}

export function Forbidden() {
  const { user } = useSession()
  const home = user ? landingRoute(user.roles) : null
  return (
    <Result
      status="403"
      title="403"
      subTitle="You do not have access to this page."
      extra={
        home ? (
          <Link to={home}>
            <Button type="primary">Go to my dashboard</Button>
          </Link>
        ) : null
      }
    />
  )
}

/** Direct URL to a route the user's roles do not allow -> 403 Result (rbac.md §2). */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { user } = useSession()
  if (!user || !hasAnyRole(user.roles, roles)) return <Forbidden />
  return <>{children}</>
}

/** `/` -> the landing dashboard of the user's highest role. */
export function LandingRedirect() {
  const { user } = useSession()
  const target = user ? landingRoute(user.roles) : null
  return target ? <Navigate to={target} replace /> : <Forbidden />
}
