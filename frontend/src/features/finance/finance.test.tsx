import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
import { checkLimit, isSetting } from './financeApi'
import { setMockBankFailures, setMockEligible } from './financeMocks'
import { limitText } from './financeFormat'

function loginAs(roles: Role[], id = 4) {
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'Farhan Head', email: 'head@expenseflow.test', roles },
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

const HEAD: Role[] = ['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN']

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  setMockLatency(0)
  resetMockState()
  window.sessionStorage.clear()
  resetSessionCacheForTests()
})
afterEach(() => vi.unstubAllEnvs())

describe('Payout batches (T-031)', () => {
  it('lists batches with status tags', async () => {
    loginAs(HEAD)
    renderApp('/payouts')
    expect(await screen.findByText('PB-0003')).toBeInTheDocument()
    expect(screen.getByText('Submit failed')).toBeInTheDocument()
    expect(screen.getAllByText('Paid').length).toBe(2)
  })

  it('create batch: confirm shows count + total, then opens the new batch and submits it', async () => {
    loginAs(HEAD)
    renderApp('/payouts')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /create batch/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Create payout batch' })
    expect(await within(dialog).findByText('4 approved claims')).toBeInTheDocument()
    expect(within(dialog).getByText('21,480 INR')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Create batch (4)' }))

    expect(await screen.findByText('Batch PB-0004')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /submit to bank/i }))
    await user.click(await screen.findByRole('button', { name: 'Submit' }))
    await waitFor(() => expect(screen.getByText('MOCKBANK-8004')).toBeInTheDocument())
  })

  it('create batch with nothing approved shows the empty message and no create', async () => {
    setMockEligible({ count: 0, total: 0 })
    loginAs(HEAD)
    renderApp('/payouts')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /create batch/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Create payout batch' })
    expect(await within(dialog).findByText('No approved claims are waiting for payout')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Create batch' })).toBeDisabled()
  })

  it('submit failure shows the bank error and Retry pays the batch', async () => {
    setMockBankFailures(1)
    loginAs(HEAD)
    renderApp('/payouts?batch=3')
    const user = userEvent.setup()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Submit to bank failed')
    expect(alert).toHaveTextContent('Bank timeout after 5s')

    // first retry fails again (bank still down), second one goes through
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('can be retried'))
    await user.click(within(screen.getByRole('alert')).getByRole('button', { name: /retry/i }))
    await waitFor(() => expect(screen.getByText('MOCKBANK-8003')).toBeInTheDocument())
    expect(screen.queryByText('Submit to bank failed')).not.toBeInTheDocument()
  })

  it('auditor can read and download but not create or submit', async () => {
    loginAs(['EMPLOYEE', 'FINANCE_AUDITOR'], 3)
    renderApp('/payouts?batch=3')
    expect(await screen.findByRole('button', { name: /download csv/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create batch/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
  })

  it('employee gets the 403 page', async () => {
    loginAs(['EMPLOYEE'], 1)
    renderApp('/payouts')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
  })
})

describe('Policy rules (T-031)', () => {
  it('finance head edits a limit in the drawer and the table updates', async () => {
    loginAs(HEAD)
    renderApp('/policy-rules')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Edit MEAL_DAILY_LIMIT' }))
    const input = await screen.findByLabelText(/Limit \(INR, per day\)/)
    await user.clear(input)
    await user.type(input, '1800')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByText('Edit MEAL_DAILY_LIMIT')).not.toBeInTheDocument())
    expect(await screen.findByText('1,800 INR per day')).toBeInTheDocument()
  })

  it('rule edit validation: empty limit and rate above 1 are refused before saving', async () => {
    loginAs(HEAD)
    renderApp('/policy-rules')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Edit SAMPLE_AUDIT_RATE' }))
    const rate = await screen.findByLabelText('Rate (0 to 1)')
    await user.clear(rate)
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Enter a value')).toBeInTheDocument()

    expect(checkLimit({ code: 'SAMPLE_AUDIT_RATE' }, 1.5)).toBe('Sample audit rate must be between 0 and 1')
    expect(checkLimit({ code: 'SAMPLE_AUDIT_RATE' }, 1)).toBeNull()
    expect(checkLimit({ code: 'MEAL_DAILY_LIMIT' }, -1)).toBe('The value cannot be negative')
    expect(checkLimit({ code: 'MEAL_DAILY_LIMIT' }, 0)).toBeNull()
  })

  it('renders setting rows sent as action CONFIG by the real API (blank-page bug)', async () => {
    loginAs(HEAD)
    renderApp('/policy-rules')
    expect(await screen.findByRole('heading', { name: 'Policy rules' })).toBeInTheDocument()
    expect((await screen.findAllByText('Setting')).length).toBe(4)
    expect(screen.getByText('10%')).toBeInTheDocument()
    expect(isSetting({ action: 'CONFIG' })).toBe(true)
    expect(isSetting({ action: null })).toBe(true)
    expect(isSetting({ action: 'ROUTE' })).toBe(false)
  })

  it('manager sees the rules read-only', async () => {
    loginAs(['EMPLOYEE', 'MANAGER'], 2)
    renderApp('/policy-rules')
    expect(await screen.findByText('Read-only: only the finance head can change policy rules.')).toBeInTheDocument()
    expect((await screen.findAllByText('MEAL_DAILY_LIMIT')).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument()
  })

  it('formats limits', () => {
    const base = { description: '', enabled: true, action: null, unit: null }
    expect(limitText({ ...base, code: 'SAMPLE_AUDIT_RATE', limitAmount: 0.1 })).toBe('10%')
    expect(limitText({ ...base, code: 'CAB_TRIP_LIMIT', limitAmount: 2000, unit: 'PER_TRIP' })).toBe('2,000 INR per trip')
    expect(limitText({ ...base, code: 'DUPLICATE_LINE', limitAmount: null })).toBe('-')
  })
})
