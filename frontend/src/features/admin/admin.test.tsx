import { configure, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import dayjs from 'dayjs'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../App'
import { QueryProvider } from '../../api/QueryProvider'
import { ThemeProvider } from '../../design/ThemeProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { resetSessionCacheForTests, setSession } from '../auth/session'
import type { Role } from '../auth/types'
import { rangeFor } from './adminModel'

// antd Selects + modals are slow under a full parallel run: be generous in this file only
configure({ asyncUtilTimeout: 5000 })

let me: { id: number; name: string; email: string; roles: Role[] }

function loginAs(roles: Role[]) {
  me = { id: 9, name: 'Asha Admin', email: 'admin@expenseflow.test', roles }
  setSession({
    accessToken: `x.${btoa(JSON.stringify({ sub: '9', roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id: 9, name: 'Asha Admin', email: 'admin@expenseflow.test', roles },
  })
}

function renderApp(path: string) {
  return render(
    <QueryProvider>
      <ThemeProvider initialMode="light">
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <AppRoutes />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryProvider>,
  )
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const USERS = [
  { id: 1, name: 'Priya Employee', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'], enabled: true },
  { id: 2, name: 'Rahul Manager', email: 'manager@expenseflow.test', roles: ['EMPLOYEE', 'MANAGER'], enabled: true },
]

type Handler = (url: URL, init: RequestInit) => Response | Promise<Response>
let handler: Handler
const calls: { method: string; url: URL; body?: unknown }[] = []

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '0')
  vi.stubEnv('VITE_MOCK_AUTH', '0')
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  calls.length = 0
  handler = () => json({}, 404)
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost')
      if (url.pathname === '/api/auth/me') return json(me)
      calls.push({ method: init.method ?? 'GET', url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return handler(url, init)
    }),
  )
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('Super admin screens (T-034)', { timeout: 20_000 }, () => {
  it('403 for a non-admin on every admin route', async () => {
    loginAs(['EMPLOYEE', 'MANAGER'])
    for (const path of ['/admin', '/admin/users', '/admin/audit', '/admin/health']) {
      const { unmount } = renderApp(path)
      expect(await screen.findByText('403 - No access')).toBeInTheDocument()
      unmount()
    }
    expect(calls).toHaveLength(0)
  })

  it('users: role filter is sent to the API and the empty state is shown', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = (url) =>
      url.searchParams.get('role') === 'FINANCE_HEAD'
        ? json({ items: [], page: 0, size: 20, total: 0 })
        : json({ items: USERS, page: 0, size: 20, total: 2 })
    renderApp('/admin/users')
    expect(await screen.findByText('Rahul Manager')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('combobox', { name: 'Filter by role' }))
    await userEvent.click(await screen.findByTitle('Finance head'))
    expect(await screen.findByText('No users match these filters')).toBeInTheDocument()
    expect(calls.some((c) => c.url.pathname === '/api/admin/users' && c.url.searchParams.get('role') === 'FINANCE_HEAD')).toBe(true)
  })

  it('users: changing roles sends PUT and shows the own-admin refusal in plain words', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = (_url, init) => {
      if (init.method === 'PUT') return json({ code: 'CANNOT_REMOVE_OWN_ADMIN', message: 'x' }, 409)
      return json({ items: USERS, page: 0, size: 20, total: 2 })
    }
    renderApp('/admin/users')
    await userEvent.click(await screen.findByRole('button', { name: 'Edit roles of Priya Employee' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Roles' }))
    await userEvent.click(await screen.findByTitle('Manager'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save roles' }))

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeDefined())
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url.pathname).toBe('/api/admin/users/1/roles')
    expect(put.body).toEqual({ roles: ['EMPLOYEE', 'MANAGER'] })
    expect(await within(dialog).findByText(/cannot remove your own Super admin role/)).toBeInTheDocument()
  })

  it('users: error state offers Retry', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = () => json({ code: 'INTERNAL_ERROR', message: 'Database unavailable' }, 500)
    renderApp('/admin/users')
    expect(await screen.findByText('Database unavailable', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('overview: loads with a 7D range and shows totals', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = () =>
      json({
        totals: { spend: 125000, claims: 40, pendingApprovals: 6, violations: 3, autoApprovedRate: 0.5 },
        usersByRole: { EMPLOYEE: 150, MANAGER: 12 },
        activeUsers: 150,
        totalUsers: 162,
        newUsersInRange: 4,
      })
    renderApp('/admin')
    expect(await screen.findByText('Total users')).toBeInTheDocument()
    expect(screen.getByText('162')).toBeInTheDocument()
    const call = calls.find((c) => c.url.pathname === '/api/admin/overview')!
    const from = dayjs(call.url.searchParams.get('from')), to = dayjs(call.url.searchParams.get('to'))
    expect(Math.round(to.diff(from, 'hour'))).toBe(7 * 24)
    expect(screen.getByRole('radiogroup', { name: 'Date range' })).toBeInTheDocument()
  })

  it('audit: shows events and filters by entity type', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = (url) =>
      json({
        items:
          url.searchParams.get('entityType') === 'USER'
            ? []
            : [{ id: 1, at: '2026-11-27T10:00:00Z', action: 'CLAIM_APPROVED', entityType: 'CLAIM', entityId: '7', actor: { id: 2, name: 'Rahul Manager' } }],
        page: 0,
        size: 20,
        total: 1,
      })
    renderApp('/admin/audit')
    expect(await screen.findByText('CLAIM_APPROVED')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('combobox', { name: 'Filter by entity type' }))
    await userEvent.click(await screen.findByTitle('USER'))
    expect(await screen.findByText('No audit events match these filters')).toBeInTheDocument()
    // Contract: from/to are required on /api/admin/audit (400 "from and to are required" otherwise).
    const auditCalls = calls.filter((c) => c.url.pathname === '/api/admin/audit')
    expect(auditCalls.length).toBeGreaterThan(0)
    for (const c of auditCalls) {
      const from = c.url.searchParams.get('from')
      const to = c.url.searchParams.get('to')
      expect(from).toBeTruthy()
      expect(to).toBeTruthy()
      expect(dayjs(to).diff(dayjs(from), 'day')).toBe(dayjs(rangeFor('7D').to).diff(dayjs(rangeFor('7D').from), 'day'))
    }
  })

  it('health: status cards, error rate and oldest pending approval', async () => {
    loginAs(['SUPER_ADMIN'])
    handler = () =>
      json({
        services: [
          { name: 'database', status: 'UP', latencyMs: 3 },
          { name: 'mock-bank', status: 'DOWN', latencyMs: 0 },
        ],
        errorRate5m: 0.0125,
        oldestPendingApprovalHours: 52.4,
      })
    renderApp('/admin/health')
    expect(await screen.findByText('database')).toBeInTheDocument()
    expect(screen.getByText(/DOWN/)).toBeInTheDocument()
    expect(screen.getByText('Error rate (last 5 min)')).toBeInTheDocument()
    expect(screen.getByText('Oldest pending approval')).toBeInTheDocument()
  })

  it('rangeFor presets cover 1, 7 and 30 days', () => {
    const now = dayjs('2026-11-27T12:00:00Z')
    expect(dayjs(rangeFor('1D', now).to).diff(rangeFor('1D', now).from, 'day')).toBe(1)
    expect(dayjs(rangeFor('7D', now).to).diff(rangeFor('7D', now).from, 'day')).toBe(7)
    expect(dayjs(rangeFor('1M', now).to).diff(rangeFor('1M', now).from, 'day')).toBe(30)
  })
})
