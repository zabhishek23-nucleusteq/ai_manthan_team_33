import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../App'
import { QueryProvider } from '../../api/QueryProvider'
import { ThemeProvider } from '../../design/ThemeProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { resetSessionCacheForTests, setSession } from '../auth/session'
import { filtersFromParams, filtersToParams } from './auditQueueApi'

const fetchMock = vi.fn<typeof fetch>()
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const anita = { id: 3, name: 'Anita Auditor', email: 'auditor@expenseflow.test' }
const QUEUE = {
  items: [
    { claimId: 11, code: 'EXP-000011', title: 'Conference', employee: { id: 1, name: 'Priya Employee', email: 'p@x' }, department: { id: 1, name: 'Engineering' }, total: 26500, level: 'AUDITOR', violationCodes: ['HOTEL_NIGHTLY_LIMIT'], sampleAudit: false, submittedAt: '2026-11-25T10:00:00Z', ageHours: 50 },
    { claimId: 12, code: 'EXP-000012', title: 'Offsite', employee: { id: 7, name: 'Ravi Sales', email: 'r@x' }, department: { id: 2, name: 'Sales' }, total: 32000, level: 'AUDITOR', violationCodes: [], sampleAudit: false, submittedAt: '2026-11-26T10:00:00Z', ageHours: 26 },
    { claimId: 13, code: 'EXP-000013', title: 'Stationery', employee: { id: 1, name: 'Priya Employee', email: 'p@x' }, department: { id: 1, name: 'Engineering' }, total: 1200, level: 'AUDITOR', violationCodes: [], sampleAudit: true, submittedAt: '2026-11-24T10:00:00Z', ageHours: 70 },
  ],
  page: 0,
  size: 20,
  total: 3,
}

function LocationProbe() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname + l.search}</div>
}

function renderAt(path: string) {
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

let bulkBody: unknown = null
let sampleBody: unknown = null

beforeEach(() => {
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  setSession({
    accessToken: 'h.' + btoa(JSON.stringify({ sub: '3', roles: ['EMPLOYEE', 'FINANCE_AUDITOR'], exp: 9999999999 })) + '.s',
    expiresAt: Date.now() + 3_600_000,
    user: { ...anita, roles: ['EMPLOYEE', 'FINANCE_AUDITOR'] },
  })
  bulkBody = null
  sampleBody = null
  fetchMock.mockReset()
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.startsWith('/api/auth/me')) {
      // answer for whoever the token belongs to
      const auth = (init?.headers as Record<string, string> | undefined)?.Authorization ?? ''
      const sub = JSON.parse(atob(auth.split('.')[1] ?? 'e30=')).sub
      return sub === '3'
        ? json(200, { ...anita, roles: ['EMPLOYEE', 'FINANCE_AUDITOR'] })
        : json(200, { id: 1, name: 'Priya', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'] })
    }
    if (url.startsWith('/api/departments')) return json(200, [{ id: 1, name: 'Engineering' }, { id: 2, name: 'Sales' }])
    if (url.startsWith('/api/audit-queue/bulk-approve')) {
      bulkBody = JSON.parse(String(init?.body))
      return json(200, { results: [{ claimId: 11, ok: true, status: 'PENDING_HEAD' }, { claimId: 12, ok: false, code: 'CONCURRENT_MODIFICATION' }] })
    }
    if (url.includes('/sample-audit')) {
      sampleBody = JSON.parse(String(init?.body))
      return json(200, { id: 13, code: 'EXP-000013', status: 'REJECTED', total: 1200, lines: [] })
    }
    if (url.startsWith('/api/audit-queue')) return json(200, QUEUE)
    return json(404, { code: 'NOT_FOUND', message: url })
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe('audit queue', () => {
  it('filters sync to the URL and to the API query', async () => {
    renderAt('/audit-queue?sampleOnly=true&minAmount=1000&sort=-total')
    expect(await screen.findByText('EXP-000011')).toBeInTheDocument()
    const queueCall = fetchMock.mock.calls.map((c) => String(c[0])).find((u) => u.startsWith('/api/audit-queue?'))
    expect(queueCall).toContain('sampleOnly=true')
    expect(queueCall).toContain('minAmount=1000')
    expect(queueCall).toContain('sort=-total')

    await userEvent.click(screen.getByRole('switch', { name: 'Sample audits only' }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/audit-queue?minAmount=1000&sort=-total'))
  })

  it('round-trips filters through URL params', () => {
    const f = filtersFromParams(new URLSearchParams('departmentId=2&category=HOTEL&violation=WEEKEND_SPEND&olderThanHours=48&page=2&size=50&sort=total'))
    expect(f).toMatchObject({ departmentId: 2, category: 'HOTEL', violation: 'WEEKEND_SPEND', olderThanHours: 48, page: 2, size: 50, sort: 'total' })
    expect(filtersToParams(f).toString()).toBe('departmentId=2&category=HOTEL&violation=WEEKEND_SPEND&olderThanHours=48&sort=total&page=2&size=50')
    expect(filtersFromParams(new URLSearchParams('sort=bogus&size=999')).sort).toBeUndefined()
  })

  it('bulk approve shows the partial result per claim', async () => {
    renderAt('/audit-queue')
    const user = userEvent.setup()
    await screen.findByText('EXP-000011')
    await user.click(screen.getByRole('checkbox', { name: 'Select EXP-000011' }))
    await user.click(screen.getByRole('checkbox', { name: 'Select EXP-000012' }))
    expect(screen.getByRole('checkbox', { name: 'Select EXP-000013' })).toBeDisabled() // sample audit row
    await user.click(screen.getByRole('button', { name: /approve selected \(2\)/i }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('1 approved, 1 not approved')).toBeInTheDocument()
    expect(screen.getByText(/EXP-000012: changed by someone else/)).toBeInTheDocument()
    expect(bulkBody).toEqual({ claimIds: [11, 12] })
    const bulkCall = fetchMock.mock.calls.find((c) => String(c[0]).includes('bulk-approve'))!
    expect((bulkCall[1]!.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('sample audit exception requires a comment and posts the result', async () => {
    renderAt('/audit-queue')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Sample audit exception for EXP-000013' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Record exception' }))
    expect(await within(dialog).findByText('Explain the exception')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Comment'), 'Receipt is for a personal purchase.')
    await user.click(within(dialog).getByRole('button', { name: 'Record exception' }))
    await waitFor(() => expect(sampleBody).toEqual({ result: 'EXCEPTION', comment: 'Receipt is for a personal purchase.' }))
  })

  it('is forbidden for an employee', async () => {
    setSession({
      accessToken: 'h.' + btoa(JSON.stringify({ sub: '1', roles: ['EMPLOYEE'], exp: 9999999999 })) + '.s',
      expiresAt: Date.now() + 3_600_000,
      user: { id: 1, name: 'Priya', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'] },
    })
    renderAt('/audit-queue')
    expect(await screen.findByText('403 - No access')).toBeInTheDocument()
  })
})
