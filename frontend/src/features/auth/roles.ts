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
 * Only same-app relative paths are allowed as ?next= targets (prevents open redirects
 * like ?next=//evil.example or ?next=https://evil.example).
 */
export function safeNext(next: string | null | undefined): string | null {
  if (!next) return null
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return null
  if (next.startsWith('/login') || next.startsWith('/signup')) return null
  return next
}
