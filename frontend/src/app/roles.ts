/**
 * Roles, menu and landing routes - mirrors docs/architecture/rbac.md §1-§2 (source of truth).
 * Backend is the authority; this only hides menu items and guards routes.
 */
export type Role = 'EMPLOYEE' | 'MANAGER' | 'FINANCE_AUDITOR' | 'FINANCE_HEAD' | 'SUPER_ADMIN'

/** Highest first: decides the landing dashboard for users with several roles. */
export const ROLE_PRIORITY: readonly Role[] = [
  'SUPER_ADMIN',
  'FINANCE_HEAD',
  'FINANCE_AUDITOR',
  'MANAGER',
  'EMPLOYEE',
]

export const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: 'Employee',
  MANAGER: 'Manager',
  FINANCE_AUDITOR: 'Finance auditor',
  FINANCE_HEAD: 'Finance head',
  SUPER_ADMIN: 'Super admin',
}

export const LANDING_ROUTE: Record<Role, string> = {
  SUPER_ADMIN: '/admin',
  FINANCE_HEAD: '/finance/dashboard',
  FINANCE_AUDITOR: '/finance/dashboard',
  MANAGER: '/manager/dashboard',
  EMPLOYEE: '/me/dashboard',
}

export type NavGroup = 'My expenses' | 'Approvals' | 'Finance' | 'Administration'

export interface NavItem {
  path: string
  label: string
  group: NavGroup
  roles: readonly Role[]
}

const APPROVERS: readonly Role[] = ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD']
const FINANCE: readonly Role[] = ['FINANCE_AUDITOR', 'FINANCE_HEAD']

/** Menu items in display order (rbac.md §2). */
export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/me/dashboard', label: 'My dashboard', group: 'My expenses', roles: ['EMPLOYEE'] },
  { path: '/claims', label: 'My claims', group: 'My expenses', roles: ['EMPLOYEE'] },
  { path: '/claims/new', label: 'New claim', group: 'My expenses', roles: ['EMPLOYEE'] },
  { path: '/manager/dashboard', label: 'Team dashboard', group: 'Approvals', roles: ['MANAGER'] },
  { path: '/approvals', label: 'Approvals', group: 'Approvals', roles: APPROVERS },
  { path: '/delegations', label: 'Delegation', group: 'Approvals', roles: APPROVERS },
  { path: '/finance/dashboard', label: 'Finance dashboard', group: 'Finance', roles: FINANCE },
  { path: '/audit-queue', label: 'Audit queue', group: 'Finance', roles: FINANCE },
  { path: '/payouts', label: 'Payouts', group: 'Finance', roles: ['FINANCE_HEAD'] },
  { path: '/policy-rules', label: 'Policy rules', group: 'Finance', roles: ['FINANCE_HEAD'] },
  { path: '/admin', label: 'Admin overview', group: 'Administration', roles: ['SUPER_ADMIN'] },
  { path: '/admin/users', label: 'Users', group: 'Administration', roles: ['SUPER_ADMIN'] },
  { path: '/admin/audit', label: 'Audit log', group: 'Administration', roles: ['SUPER_ADMIN'] },
  { path: '/admin/health', label: 'System health', group: 'Administration', roles: ['SUPER_ADMIN'] },
]

export function hasAnyRole(userRoles: readonly Role[], allowed: readonly Role[]): boolean {
  return allowed.some((r) => userRoles.includes(r))
}

/** Union of the menus of all the user's roles, in display order. */
export function menuForRoles(roles: readonly Role[]): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAnyRole(roles, item.roles))
}

/** Highest role wins (rbac.md §2). No known role -> null (caller shows 403). */
export function primaryRole(roles: readonly Role[]): Role | null {
  return ROLE_PRIORITY.find((r) => roles.includes(r)) ?? null
}

export function landingRoute(roles: readonly Role[]): string | null {
  const role = primaryRole(roles)
  return role ? LANDING_ROUTE[role] : null
}

/** Menu key to highlight: the longest menu path that prefixes the current path. */
export function selectedMenuKey(pathname: string, items: readonly NavItem[]): string | undefined {
  return items
    .filter((i) => pathname === i.path || pathname.startsWith(i.path + '/'))
    .sort((a, b) => b.path.length - a.path.length)[0]?.path
}

/** Keeps only names we know, so a stray claim from the token cannot widen the menu. */
export function toRoles(names: readonly string[]): Role[] {
  return names.filter((n): n is Role => (ROLE_PRIORITY as readonly string[]).includes(n))
}
