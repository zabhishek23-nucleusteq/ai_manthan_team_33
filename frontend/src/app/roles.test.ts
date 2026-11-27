import { describe, expect, it } from 'vitest'
import { landingRoute, menuForRoles, primaryRole, selectedMenuKey, toRoles, type Role } from './roles'

const labels = (roles: Role[]) => menuForRoles(roles).map((i) => i.label)

describe('menu per role (rbac.md §2)', () => {
  it('employee', () => {
    expect(labels(['EMPLOYEE'])).toMatchInlineSnapshot(`
      [
        "My dashboard",
        "My claims",
        "New claim",
      ]
    `)
  })

  it('manager (+ employee)', () => {
    expect(labels(['EMPLOYEE', 'MANAGER'])).toMatchInlineSnapshot(`
      [
        "My dashboard",
        "My claims",
        "New claim",
        "Team dashboard",
        "Approvals",
        "Delegation",
      ]
    `)
  })

  it('finance auditor (+ employee)', () => {
    expect(labels(['EMPLOYEE', 'FINANCE_AUDITOR'])).toMatchInlineSnapshot(`
      [
        "My dashboard",
        "My claims",
        "New claim",
        "Approvals",
        "Delegation",
        "Finance dashboard",
        "Audit queue",
      ]
    `)
  })

  it('finance head + super admin (+ employee)', () => {
    expect(labels(['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'])).toMatchInlineSnapshot(`
      [
        "My dashboard",
        "My claims",
        "New claim",
        "Approvals",
        "Delegation",
        "Finance dashboard",
        "Audit queue",
        "Payouts",
        "Policy rules",
        "Admin overview",
        "Users",
        "Audit log",
        "System health",
      ]
    `)
  })

  it('super admin only sees admin items', () => {
    expect(labels(['SUPER_ADMIN'])).toEqual(['Admin overview', 'Users', 'Audit log', 'System health'])
  })

  it('no roles -> empty menu', () => {
    expect(labels([])).toEqual([])
  })
})

describe('landing route per role', () => {
  it.each<[Role[], string]>([
    [['EMPLOYEE'], '/me/dashboard'],
    [['EMPLOYEE', 'MANAGER'], '/manager/dashboard'],
    [['EMPLOYEE', 'FINANCE_AUDITOR'], '/finance/dashboard'],
    [['FINANCE_HEAD', 'EMPLOYEE'], '/finance/dashboard'],
    [['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'], '/admin'],
    [['SUPER_ADMIN'], '/admin'],
  ])('%j -> %s', (roles, route) => {
    expect(landingRoute(roles)).toBe(route)
  })

  it('no known role -> null', () => {
    expect(landingRoute([])).toBeNull()
    expect(primaryRole([])).toBeNull()
  })
})

describe('helpers', () => {
  it('selects the longest matching menu path', () => {
    const nav = menuForRoles(['EMPLOYEE', 'SUPER_ADMIN'])
    expect(selectedMenuKey('/claims/new', nav)).toBe('/claims/new')
    expect(selectedMenuKey('/claims/42', nav)).toBe('/claims')
    expect(selectedMenuKey('/admin/users', nav)).toBe('/admin/users')
    expect(selectedMenuKey('/admin', nav)).toBe('/admin')
    expect(selectedMenuKey('/administrator', nav)).toBeUndefined()
  })

  it('drops unknown role names', () => {
    expect(toRoles(['EMPLOYEE', 'ROOT', 'MANAGER'])).toEqual(['EMPLOYEE', 'MANAGER'])
  })
})
