import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import type { Kpis } from './dashboardApi'
import { rangeFromParams } from './range'

const get = vi.fn()
const post = vi.fn()
vi.mock('../../api/client', () => ({
  api: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) },
}))

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <DashboardPage title="My dashboard" />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const kpis = (over: Partial<Kpis> = {}): Kpis => ({
  range: { from: '2026-11-20T00:00:00Z', to: '2026-11-27T00:00:00Z', bucket: 'DAY' },
  scope: 'OWN',
  totals: { spend: 1500, claims: 2, pendingApprovals: 1, violations: 3, autoApprovedRate: 0.5, avgApprovalHours: 17 },
  spendByCategory: [{ category: 'MEAL', spend: 400 }, { category: 'HOTEL', spend: 600 }],
  spendByDepartment: [],
  spendByMonth: [{ month: '2026-11', spend: 1500 }],
  series: [{ start: '2026-11-20T00:00:00Z', spend: 1500, claims: 2 }],
  violationsByRule: [{ code: 'MEAL_DAILY_LIMIT', count: 3 }],
  approvalTimeByLevel: [{ level: 'MANAGER', avgHours: 17 }],
  ...over,
})

const EMPTY: Kpis = kpis({
  totals: { spend: 0, claims: 0, pendingApprovals: 0, violations: 0, autoApprovedRate: 0, avgApprovalHours: null },
  spendByCategory: [],
  spendByMonth: [],
  series: [],
  violationsByRule: [],
  approvalTimeByLevel: [],
})

beforeEach(() => {
  get.mockReset()
  post.mockReset()
  post.mockResolvedValue(undefined)
})

describe('role dashboard', () => {
  it('kpi cards render with values, and the page load is reported', async () => {
    get.mockResolvedValue(kpis())
    renderAt('/me/dashboard')
    expect(await screen.findByTestId('kpi-spend')).toHaveTextContent('₹1,500')
    expect(screen.getByTestId('kpi-claims')).toHaveTextContent('2')
    expect(screen.getByTestId('kpi-pending')).toHaveTextContent('1')
    expect(screen.getByTestId('kpi-violations')).toHaveTextContent('3')
    expect(screen.getByTestId('kpi-approval-hours')).toHaveTextContent('17 h')
    expect(screen.getByRole('list', { name: 'Spend by category' })).toHaveTextContent('Hotel') // plain-words category label
    expect(screen.queryByText('Spend by department')).toBeNull() // OWN scope
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/telemetry/page-load', expect.objectContaining({ route: '/me/dashboard' })),
    )
  })

  it('defaults to 7 days, and a preset switch updates the URL and the query', async () => {
    get.mockResolvedValue(kpis())
    renderAt('/me/dashboard')
    expect(screen.getByTestId('range-in-words')).toHaveTextContent('Last 7 days')
    await userEvent.click(screen.getByText('1 Day'))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('range=1D'))
    expect(screen.getByTestId('range-in-words')).toHaveTextContent('Last 24 hours')
    await waitFor(() => {
      const [, q] = get.mock.calls.at(-1) as [string, { from: string; to: string }]
      expect(new Date(q.to).getTime() - new Date(q.from).getTime()).toBe(24 * 3600 * 1000)
    })
  })

  it('empty range shows "No data between X and Y" and the widen button moves 7D -> 1M', async () => {
    get.mockResolvedValue(EMPTY)
    renderAt('/me/dashboard?range=7D')
    expect(await screen.findByText(/No data between/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Widen the range' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('range=1M'))
  })

  it('error shows an alert with a working Retry', async () => {
    get.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(kpis())
    renderAt('/me/dashboard')
    expect(await screen.findByText('Could not load the dashboard')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByTestId('kpi-spend')).toBeInTheDocument()
  })
})

describe('range from URL', () => {
  const now = new Date('2026-11-27T12:34:56Z')
  it('presets end at the current minute', () => {
    const r = rangeFromParams(new URLSearchParams('range=1M'), now)
    expect(r.to).toBe('2026-11-27T12:34:00.000Z')
    expect(r.from).toBe('2026-10-28T12:34:00.000Z')
  })
  it('custom range is kept; invalid or > 366 days falls back to 7D', () => {
    expect(rangeFromParams(new URLSearchParams('range=CUSTOM&from=2026-11-01T00:00:00Z&to=2026-11-10T00:00:00Z'), now))
      .toMatchObject({ preset: 'CUSTOM', from: '2026-11-01T00:00:00.000Z' })
    expect(rangeFromParams(new URLSearchParams('range=CUSTOM&from=2024-01-01&to=2026-11-10'), now).preset).toBe('7D')
    expect(rangeFromParams(new URLSearchParams('range=CUSTOM&from=x&to=y'), now).preset).toBe('7D')
    expect(rangeFromParams(new URLSearchParams(''), now).preset).toBe('7D')
  })
})
