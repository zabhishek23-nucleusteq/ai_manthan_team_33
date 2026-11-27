import type { Role } from './types'

/** Landing priority from docs/architecture/rbac.md §2: highest role wins. */
export const ROLE_PRIORITY: Role[] = ['SUPER_ADMIN', 'FINANCE_HEAD', 'FINANCE_AUDITOR', 'MANAGER', 'EMPLOYEE']

export const LANDING_ROUTE: Record<Role, string> = {
  SUPER_ADMIN: '/admin',
  FINANCE_HEAD: '/finance/dashboard',
  FINANCE_AUDITOR: '/finance/dashboard',
  MANAGER: '/manager/dashboard',
  EMPLOYEE: '/me/dashboard',
}

export const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: 'Employee',
  MANAGER: 'Manager',
  FINANCE_AUDITOR: 'Finance auditor',
  FINANCE_HEAD: 'Finance head',
  SUPER_ADMIN: 'Super admin',
}

export function landingRouteFor(roles: readonly Role[]): string {
  const top = ROLE_PRIORITY.find((r) => roles.includes(r))
  return top ? LANDING_ROUTE[top] : '/403'
}

export function hasAnyRole(userRoles: readonly Role[], allowed: readonly Role[]): boolean {
  return allowed.some((r) => userRoles.includes(r))
}

/**
 * Which roles may open which route prefix (docs/architecture/rbac.md section 2 menus; same rules as the
 * RequireRole guards in App.tsx). Routes not listed (/, /design, /403, ...) are open to everyone signed in.
 * Longest matching prefix wins.
 */
export const ROUTE_ROLES: ReadonlyArray<[string, readonly Role[]]> = [
  ['/me', ['EMPLOYEE']],
  ['/claims', ['EMPLOYEE']],
  ['/manager', ['MANAGER']],
  ['/approvals', ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']],
  ['/delegations', ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']],
  ['/finance', ['FINANCE_AUDITOR', 'FINANCE_HEAD']],
  ['/audit-queue', ['FINANCE_AUDITOR', 'FINANCE_HEAD']],
  ['/payouts', ['FINANCE_AUDITOR', 'FINANCE_HEAD']],
  ['/policy-rules', ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN']],
  ['/admin', ['SUPER_ADMIN']],
]

/** True when a user with these roles may open this in-app path (query string and hash ignored). */
export function canOpen(path: string, roles: readonly Role[]): boolean {
  const pathname = path.split(/[?#]/)[0]
  const match = ROUTE_ROLES.filter(([prefix]) => pathname === prefix || pathname.startsWith(prefix + '/'))
    .sort((a, b) => b[0].length - a[0].length)[0]
  return match ? hasAnyRole(roles, match[1]) : true
}

/**
 * Where to go after login (T-509): the ?next= target only if it is safe AND the user who just logged in may open
 * it; otherwise their own landing page. Prevents the 403 page when a URL left by another role is in ?next=.
 */
export function postLoginTarget(next: string | null | undefined, roles: readonly Role[]): string {
  const safe = safeNext(next)
  return safe && canOpen(safe, roles) ? safe : landingRouteFor(roles)
}

/**
 * Only same-app relative paths are allowed as ?next= targets (prevents open redirects
 * like ?next=//evil.example or ?next=https://evil.example).
 */
export function safeNext(next: string | null | undefined): string | null {
  if (!next) return null
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return null
  if (next.startsWith('/login') || next.startsWith('/signup')) return null
  return next
}
