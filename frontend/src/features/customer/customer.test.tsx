import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import dayjs from 'dayjs'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../App'
import { resetMockState } from '../../api/mock/handlers'
import { setMockLatency } from '../../api/mock/router'
import { QueryProvider } from '../../api/QueryProvider'
import { ThemeProvider } from '../../design/ThemeProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { resetSessionCacheForTests, setSession } from '../auth/session'
import type { Role } from '../auth/types'
import { checkReceiptFile } from './claimsApi'
import { formToInput } from './editorModel'
import { lineHints } from './policyHints'

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function loginAs(id = 1, roles: Role[] = ['EMPLOYEE']) {
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'Priya Employee', email: 'employee@expenseflow.test', roles },
  })
}

function renderApp(path: string) {
  return render(
    <QueryProvider>
      <ThemeProvider initialMode="light">
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <AppRoutes />
            <LocationProbe />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryProvider>,
  )
}

const location = () => screen.getByTestId('location').textContent

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  setMockLatency(0)
  resetMockState()
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  loginAs()
})
afterEach(() => vi.unstubAllEnvs())

describe('My claims list', () => {
  it('shows claim cards on small screens with status tags, needs-reply first', async () => {
    renderApp('/claims')
    const cards = await screen.findAllByRole('link', { name: /^DEMO-10/ })
    expect(cards.length).toBe(10)
    expect(cards[0]).toHaveAccessibleName(/DEMO-1004 .*Clarification requested/)
    expect(screen.getAllByText('Draft').length).toBeGreaterThan(0)
  })

  it('filters by status through the URL', async () => {
    renderApp('/claims?status=PENDING_HEAD')
    const cards = await screen.findAllByRole('link', { name: /^DEMO-10/ })
    expect(cards).toHaveLength(1)
    expect(cards[0]).toHaveAccessibleName(/DEMO-1010/)
  })

  it('empty state offers a New claim button', async () => {
    loginAs(2, ['EMPLOYEE', 'MANAGER'])
    renderApp('/claims')
    expect(await screen.findByText('No claims yet')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Create your first claim' }))
    await waitFor(() => expect(location()).toBe('/claims/new'))
  })

  it('is forbidden for a user without EMPLOYEE role', async () => {
    loginAs(5, ['SUPER_ADMIN'])
    renderApp('/claims')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
  })
})

describe('claim editor', () => {
  it('validates required fields and lists lines with errors', async () => {
    renderApp('/claims/new')
    await userEvent.click(await screen.findByRole('button', { name: /submit/i }))
    expect(await screen.findByText('Enter a title, e.g. Client visit Pune')).toBeInTheDocument()
    expect(screen.getByText('Choose a category')).toBeInTheDocument()
    expect(screen.getByText('Choose the expense date')).toBeInTheDocument()
    expect(screen.getByText('Enter the amount')).toBeInTheDocument()
    expect(screen.getByText('Enter the vendor')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Title, Line 1')
  })

  it('adds and removes lines', async () => {
    renderApp('/claims/new')
    const user = userEvent.setup()
    expect(await screen.findByRole('group', { name: 'Line 1' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /remove line 1/i })).toBeNull() // last line cannot be removed
    await user.click(screen.getByRole('button', { name: /add line/i }))
    expect(screen.getByRole('group', { name: 'Line 2' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /remove line 2/i }))
    expect(screen.queryByRole('group', { name: 'Line 2' })).toBeNull()
  })

  it('submits a draft and shows every violation when auto-rejected', async () => {
    renderApp('/claims/1001/edit')
    const user = userEvent.setup()
    const amount = await screen.findByLabelText('Line 1 amount')
    await user.clear(amount)
    await user.type(amount, '900')
    // live hint before submit (no receipt above 500)
    expect(await screen.findByText(/receipt is required for amounts above 500/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /submit/i }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('1 line, 900 INR')
    await user.click(within(dialog).getByRole('button', { name: 'Submit' }))
    expect(await screen.findByText('Claim rejected by policy')).toBeInTheDocument()
    expect(screen.getByText(/Line 1 · Local cab 900 INR: Receipt required above 500 INR/)).toBeInTheDocument()
    expect(location()).toBe('/claims/1001/submitted')
    expect(screen.getByRole('button', { name: 'Copy to new draft' })).toBeInTheDocument()
  })

  it('resubmits a clarification claim and shows the routed result with the approval chain', async () => {
    renderApp('/claims/1004/edit')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /resubmit/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Resubmit' }))
    expect(await screen.findByText('Sent for approval')).toBeInTheDocument()
    expect(screen.getByText('Your manager')).toBeInTheDocument()
    expect(screen.getByText(/Line 1 · Hotel 9,000 INR: Hotel 4500 INR per night above 4000 INR limit/)).toBeInTheDocument()
  })

  it('shows a clear message for a claim that is no longer editable', async () => {
    renderApp('/claims/1006/edit')
    expect(await screen.findByText('This claim was already submitted')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View claim' })).toHaveAttribute('href', '/claims/1006')
  })

  it('shows not found for a claim that is not yours', async () => {
    loginAs(2, ['EMPLOYEE', 'MANAGER'])
    renderApp('/claims/1001/edit')
    expect(await screen.findByText('Claim not found')).toBeInTheDocument()
  })
})

describe('helpers', () => {
  it('maps the form to the contract ClaimInput (HOTEL fields only for hotels)', () => {
    const input = formToInput({
      title: '  Trip  ',
      lines: [
        { category: 'HOTEL', amount: 12000, expenseDate: dayjs('2026-11-20'), vendor: ' Inn ', city: 'Pune', nights: 2 },
        { category: 'MEAL', amount: 900, expenseDate: dayjs('2026-11-21'), vendor: 'Cafe', city: 'ignored', nights: 3 },
      ],
    })
    expect(input).toEqual({
      title: 'Trip',
      lines: [
        { category: 'HOTEL', amount: 12000, expenseDate: '2026-11-20', vendor: 'Inn', city: 'Pune', nights: 2 },
        { category: 'MEAL', amount: 900, expenseDate: '2026-11-21', vendor: 'Cafe' },
      ],
    })
  })

  it('policy hints cover receipt, limits at the boundary and weekends', () => {
    const codes = (l: Parameters<typeof lineHints>[0]) => lineHints(l).map((h) => h.code)
    expect(codes({ category: 'MEAL', amount: 500 })).toEqual([])
    expect(codes({ category: 'MEAL', amount: 500.01 })).toEqual(['RECEIPT_REQUIRED'])
    expect(codes({ category: 'MEAL', amount: 1500, hasReceipt: true })).toEqual([])
    expect(codes({ category: 'MEAL', amount: 1500.01, hasReceipt: true })).toEqual(['MEAL_DAILY_LIMIT'])
    expect(codes({ category: 'CAB', amount: 2001, hasReceipt: true })).toEqual(['CAB_TRIP_LIMIT'])
    expect(codes({ category: 'HOTEL', amount: 12000, nights: 2, city: 'Pune', hasReceipt: true })).toEqual([])
    expect(codes({ category: 'HOTEL', amount: 12000, nights: 2, city: 'Nagpur', hasReceipt: true })).toEqual(['HOTEL_NIGHTLY_LIMIT'])
    expect(codes({ category: 'OTHER', amount: 10, expenseDate: dayjs('2026-11-21') })).toEqual(['WEEKEND_SPEND']) // Saturday
  })

  it('receipt check allows JPG/PNG/PDF up to 5 MB', () => {
    expect(checkReceiptFile({ type: 'image/jpeg', size: 5 * 1024 * 1024 })).toBeNull()
    expect(checkReceiptFile({ type: 'image/png', size: 5 * 1024 * 1024 + 1 })).toMatch(/larger than 5 MB/)
    expect(checkReceiptFile({ type: 'image/gif', size: 10 })).toMatch(/JPG, PNG or PDF/)
  })
})
