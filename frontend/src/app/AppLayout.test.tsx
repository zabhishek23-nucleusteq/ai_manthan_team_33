import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../App'
import { ThemeProvider } from '../design/ThemeProvider'
import { AuthProvider } from '../features/auth/AuthProvider'
import { resetSessionCacheForTests, setSession } from '../features/auth/session'
import type { Role } from '../features/auth/types'

/** Demo users from rbac.md §4 (synthetic); ids match features/auth/mockAuth.ts so mock /me agrees. */
const DEMO: Record<string, { id: number; name: string; roles: Role[] }> = {
  employee: { id: 1, name: 'Priya Employee', roles: ['EMPLOYEE'] },
  manager: { id: 2, name: 'Rahul Manager', roles: ['EMPLOYEE', 'MANAGER'] },
  auditor: { id: 3, name: 'Anita Auditor', roles: ['EMPLOYEE', 'FINANCE_AUDITOR'] },
  head: { id: 4, name: 'Farhan Head', roles: ['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'] },
  admin: { id: 5, name: 'Sam Admin', roles: ['SUPER_ADMIN'] },
}

function signIn(key: keyof typeof DEMO) {
  const { id, name, roles } = DEMO[key]
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name, email: `${key}@expenseflow.test`, roles },
  })
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider initialMode="light">
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <AppRoutes />
            <LocationProbe />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

const location = () => screen.getByTestId('location').textContent

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  window.sessionStorage.clear()
  resetSessionCacheForTests()
})
afterEach(() => vi.unstubAllEnvs())

describe('app shell', () => {
  it.each([
    ['employee', '/me/dashboard', 'My dashboard'],
    ['manager', '/manager/dashboard', 'Team dashboard'],
    ['auditor', '/finance/dashboard', 'Finance dashboard'],
    ['head', '/admin', 'Admin overview'],
    ['admin', '/admin', 'Admin overview'],
  ] as const)('%s lands on %s', async (key, route, title) => {
    signIn(key)
    renderAt('/')
    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
    expect(location()).toBe(route)
    // Same text the Playwright TC-01 spec checks.
    expect(within(screen.getByTestId('current-user')).getByText(`Logged in as ${DEMO[key].name}`)).toBeInTheDocument()
  })

  it('direct URL to a forbidden page shows 403 inside the shell', async () => {
    signIn('manager')
    renderAt('/payouts')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
    expect(screen.getByTestId('current-user')).toBeInTheDocument()
  })

  it('employee cannot open admin pages', async () => {
    signIn('employee')
    renderAt('/admin/users')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
  })

  it('no session -> login with next=', async () => {
    renderAt('/approvals')
    await waitFor(() => expect(location()).toBe('/login?next=%2Fapprovals'))
  })

  it('logout returns to login', async () => {
    signIn('employee')
    renderAt('/me/dashboard')
    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }))
    await waitFor(() => expect(location()?.startsWith('/login')).toBe(true))
  })

  it('on small screens the menu opens in a drawer with only the role items', async () => {
    signIn('employee')
    renderAt('/me/dashboard')
    await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }))
    const menu = await screen.findByRole('menu', { name: 'Main menu' })
    expect(within(menu).getByText('My claims')).toBeInTheDocument()
    expect(within(menu).queryByText('Approvals')).not.toBeInTheDocument()
    await userEvent.click(within(menu).getByText('My claims'))
    await waitFor(() => expect(location()).toBe('/claims'))
  })

  it('shows one Log out button and one main landmark on admin pages', async () => {
    signIn('admin')
    renderAt('/admin/users')
    expect(await screen.findAllByRole('button', { name: /log out/i })).toHaveLength(1)
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('approvals inbox renders inside the shell with one main landmark', async () => {
    signIn('manager')
    renderAt('/approvals')
    await screen.findByTestId('current-user')
    await waitFor(() => expect(screen.getAllByRole('main')).toHaveLength(1))
    expect(screen.getAllByRole('button', { name: /log out/i })).toHaveLength(1)
  })

  it('role screens render inside the shell', async () => {
    signIn('auditor')
    renderAt('/audit-queue')
    expect(await screen.findByRole('heading', { name: /audit queue/i })).toBeInTheDocument()
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('unknown route still shows 404', () => {
    signIn('employee')
    renderAt('/nope/really')
    expect(screen.getByText('Page not found')).toBeInTheDocument()
  })
})
