import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../App'
import { ThemeProvider } from '../../design/ThemeProvider'
import { AuthProvider } from './AuthProvider'
import { landingRouteFor, safeNext } from './roles'
import { getSession, resetSessionCacheForTests, SESSION_KEY, setSession } from './session'
import type { Role } from './types'

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function renderApp(path: string) {
  return render(
    <ThemeProvider initialMode="light">
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
          <LocationProbe />
        </MemoryRouter>
      </AuthProvider>
    </ThemeProvider>,
  )
}

function loginAs(roles: Role[], id = 1) {
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'Test User', email: 'employee@expenseflow.test', roles },
  })
}

const location = () => screen.getByTestId('location').textContent

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  window.sessionStorage.clear()
  resetSessionCacheForTests()
})
afterEach(() => vi.unstubAllEnvs())

describe('login form', () => {
  it('shows validation messages for empty and invalid input', async () => {
    renderApp('/login')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByText('Enter your email')).toBeInTheDocument()
    expect(await screen.findByText('Enter your password')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    expect(await screen.findByText('Enter a valid email, e.g. name@company.com')).toBeInTheDocument()
  })

  it('shows a clear error on wrong credentials and stays logged out', async () => {
    renderApp('/login')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), 'employee@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect.')
    expect(getSession()).toBeNull()
    expect(location()).toBe('/login')
  })

  it('password field can be shown and hidden', async () => {
    renderApp('/login')
    const pw = screen.getByLabelText('Password')
    expect(pw).toHaveAttribute('type', 'password')
    await userEvent.click(screen.getByRole('img', { name: /eye-invisible/i }))
    expect(pw).toHaveAttribute('type', 'text')
  })

  it('logs in with Enter, stores the token in sessionStorage and lands on the role dashboard', async () => {
    renderApp('/login')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), 'manager@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'Demo@123{Enter}')
    await waitFor(() => expect(location()).toBe('/manager/dashboard'))
    expect(screen.getByText('Team dashboard')).toBeInTheDocument()
    expect(JSON.parse(window.sessionStorage.getItem(SESSION_KEY)!).user.email).toBe('manager@expenseflow.test')
  })

  it('returns to the ?next= URL after login', async () => {
    renderApp('/login?next=%2Ffinance%2Fdashboard')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), 'head@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'Demo@123{Enter}')
    await waitFor(() => expect(location()).toBe('/finance/dashboard'))
  })
})

describe('route guards', () => {
  it('redirects to /login?next=... when not logged in', async () => {
    renderApp('/admin')
    await waitFor(() => expect(location()).toBe('/login?next=%2Fadmin'))
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  })

  it('shows a 403 Result for a role that is not allowed', async () => {
    loginAs(['EMPLOYEE'])
    renderApp('/admin')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
    expect(location()).toBe('/admin')
    await userEvent.click(screen.getByRole('button', { name: 'Go to my dashboard' }))
    await waitFor(() => expect(location()).toBe('/me/dashboard'))
  })

  it('lets an allowed role in', async () => {
    loginAs(['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'], 4)
    renderApp('/admin')
    expect(await screen.findByText('Admin overview')).toBeInTheDocument()
  })

  it('logout clears the session and goes back to login', async () => {
    loginAs(['EMPLOYEE'])
    renderApp('/me/dashboard')
    await userEvent.click(await screen.findByRole('button', { name: /log out/i }))
    await waitFor(() => expect(location()?.startsWith('/login')).toBe(true))
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('an expired stored session counts as logged out', async () => {
    setSession({
      accessToken: 'x',
      expiresAt: Date.now() - 1,
      user: { id: 1, name: 'Old', email: 'old@expenseflow.test', roles: ['EMPLOYEE'] },
    })
    resetSessionCacheForTests() // simulate a page reload
    renderApp('/me/dashboard')
    await waitFor(() => expect(location()).toBe('/login?next=%2Fme%2Fdashboard'))
  })
})

describe('sign-up', () => {
  it('checks that passwords match and length rules', async () => {
    renderApp('/signup')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.type(screen.getByLabelText('Confirm password'), 'different')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    // Ant validates fields asynchronously: wait for each message, not only the first.
    expect(await screen.findByText('Password must be 8 to 100 characters')).toBeInTheDocument()
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument()
    expect(await screen.findByText('Enter your full name')).toBeInTheDocument()
  })

  it('reports a taken email on the email field', async () => {
    renderApp('/signup')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'Dup User')
    await user.type(screen.getByLabelText('Work email'), 'employee@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'Secret@123')
    await user.type(screen.getByLabelText('Confirm password'), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText(/already exists/)).toBeInTheDocument()
  })

  it('signs up then can log in with the new account', async () => {
    renderApp('/signup')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'New Person')
    await user.type(screen.getByLabelText('Work email'), 'new.person@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'Secret@123')
    await user.type(screen.getByLabelText('Confirm password'), 'Secret@123')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    await waitFor(() => expect(location()).toContain('/login?created=1'))
    expect(screen.getByText('Account created. Log in to continue.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveValue('new.person@expenseflow.test')
    await user.type(screen.getByLabelText('Password'), 'Secret@123{Enter}')
    await waitFor(() => expect(location()).toBe('/me/dashboard'))
  })
})

describe('helpers', () => {
  it('landing route follows rbac priority', () => {
    expect(landingRouteFor(['EMPLOYEE'])).toBe('/me/dashboard')
    expect(landingRouteFor(['EMPLOYEE', 'MANAGER'])).toBe('/manager/dashboard')
    expect(landingRouteFor(['EMPLOYEE', 'FINANCE_AUDITOR'])).toBe('/finance/dashboard')
    expect(landingRouteFor(['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'])).toBe('/admin')
    expect(landingRouteFor([])).toBe('/403')
  })

  it('safeNext blocks open redirects', () => {
    expect(safeNext('/claims?x=1')).toBe('/claims?x=1')
    expect(safeNext('//evil.example')).toBeNull()
    expect(safeNext('https://evil.example')).toBeNull()
    expect(safeNext('/\\evil.example')).toBeNull()
    expect(safeNext('/login')).toBeNull()
    expect(safeNext(null)).toBeNull()
  })
})
