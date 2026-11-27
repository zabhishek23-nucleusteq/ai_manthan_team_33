import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import dayjs from 'dayjs'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../App'
import { resetMockState } from '../../api/mock/handlers'
import { setMockLatency } from '../../api/mock/router'
import { QueryProvider } from '../../api/QueryProvider'
import { ThemeProvider } from '../../design/ThemeProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { resetSessionCacheForTests, setSession } from '../auth/session'
import type { Role } from '../auth/types'
import { validateRange } from './delegationRules'

function loginAs(id = 2, email = 'manager@expenseflow.test', roles: Role[] = ['EMPLOYEE', 'MANAGER']) {
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), email, roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'Rahul Manager', email, roles },
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

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  setMockLatency(0)
  resetMockState()
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  loginAs()
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('approvals (T-025)', () => {
  it('inbox renders pending claims with level and flags', async () => {
    renderApp('/approvals')
    expect(await screen.findByRole('heading', { name: 'Approvals' })).toBeInTheDocument()
    const table = await screen.findByRole('table')
    expect(within(table).getByRole('link', { name: 'DEMO-1002' })).toBeInTheDocument()
    expect(within(table).getByRole('link', { name: 'DEMO-1003' })).toBeInTheDocument()
    expect(within(table).getAllByText('Manager').length).toBeGreaterThan(0)
  })

  it('employees cannot open the approvals inbox', async () => {
    loginAs(1, 'employee@expenseflow.test', ['EMPLOYEE'])
    renderApp('/approvals')
    expect(await screen.findByText(/403|not allowed|permission/i)).toBeInTheDocument()
  })

  it('approve sends an Idempotency-Key and shows success', async () => {
    const fetchSpy = vi.spyOn(await import('../../api/mock/router'), 'handleMockRequest')
    renderApp('/approvals/1002')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Approve \(A\)/ }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('Claim approved.')).toBeInTheDocument()
    const call = fetchSpy.mock.calls.find(([m, url]) => m === 'POST' && String(url).includes('/api/approvals/1002/approve'))
    expect(call).toBeDefined()
    const headers = call![2] as Record<string, string>
    expect(headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('send-back requires a comment, then sends the claim back', async () => {
    renderApp('/approvals/1002')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Send back \(S\)/ }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Send back' }))
    expect(await within(dialog).findByText(/at least 3 characters/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Please attach the taxi bill')
    await user.click(within(dialog).getByRole('button', { name: 'Send back' }))
    expect(await screen.findByText('Sent back to the employee.')).toBeInTheDocument()
  })

  it('keyboard shortcut A opens the approve dialog', async () => {
    renderApp('/approvals/1002')
    await screen.findByRole('button', { name: /Approve \(A\)/ })
    await userEvent.keyboard('a')
    expect(await screen.findByRole('dialog')).toHaveTextContent('Approve claim')
  })

  it('shows a clear error when the claim cannot be loaded', async () => {
    renderApp('/approvals/999999')
    expect(await screen.findByText('Could not load the claim')).toBeInTheDocument()
  })
})

describe('delegations (T-025)', () => {
  it('delegation form validation: date range rules', () => {
    const today = dayjs()
    expect(validateRange(undefined)).toMatch(/Choose/)
    expect(validateRange([today, today.subtract(1, 'day')])).toMatch(/must not be before/)
    expect(validateRange([today, today.add(31, 'day')])).toMatch(/at most 30 days/)
    expect(validateRange([today.subtract(5, 'day'), today.subtract(2, 'day')])).toMatch(/already over/)
    expect(validateRange([today, today.add(3, 'day')])).toBeNull()
  })

  it('form shows required-field errors and an empty list', async () => {
    renderApp('/delegations')
    const user = userEvent.setup()
    expect(await screen.findByText('No delegations yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delegate' }))
    expect(await screen.findByText('Choose who approves for you.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Choose the first and last day.')).toBeInTheDocument())
  })
})
