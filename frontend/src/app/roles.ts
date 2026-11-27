/**
 * Menu per role - mirrors docs/architecture/rbac.md §2 (source of truth).
 * Role type, landing priority and labels live in features/auth/roles.ts (L4); this file adds the menu.
 * Backend is the authority; this only hides menu items and guards routes.
 */
import { hasAnyRole, ROLE_PRIORITY } from '../features/auth/roles'
import type { Role } from '../features/auth/types'

export type { Role }

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

/** Union of the menus of all the user's roles, in display order. */
export function menuForRoles(roles: readonly Role[]): NavItem[] {
  return NAV_ITEMS.filter((item) => hasAnyRole(roles, item.roles))
}

/** Highest role (rbac.md §2 priority) or null. */
export function primaryRole(roles: readonly Role[]): Role | null {
  return ROLE_PRIORITY.find((r) => roles.includes(r)) ?? null
}

/** Menu key to highlight: the longest menu path that prefixes the current path. */
export function selectedMenuKey(pathname: string, items: readonly NavItem[]): string | undefined {
  return items
    .filter((i) => pathname === i.path || pathname.startsWith(i.path + '/'))
    .sort((a, b) => b.path.length - a.path.length)[0]?.path
}

/** Roles of a nav path (route table uses this so menu and guard never disagree). */
export function rolesOf(path: string): readonly Role[] {
  const item = NAV_ITEMS.find((i) => i.path === path)
  if (!item) throw new Error(`No nav item for ${path}`)
  return item.roles
}
