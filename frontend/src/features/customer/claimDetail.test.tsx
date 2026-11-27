import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
import { auditActionText, auditActor, clarificationQuestion } from './claimDetailModel'

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname}</div>
}

function loginAs(id = 1, email = 'employee@expenseflow.test', roles: Role[] = ['EMPLOYEE']) {
  setSession({
    accessToken: `mock.${btoa(JSON.stringify({ sub: String(id), email, roles, exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'Priya Employee', email, roles },
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

beforeEach(() => {
  vi.stubEnv('VITE_MOCK', '1')
  setMockLatency(0)
  resetMockState()
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  loginAs()
})
afterEach(() => vi.unstubAllEnvs())

describe('claim detail', () => {
  it('renders the approval chain with levels, approvers and delegation', async () => {
    renderApp('/claims/1010')
    expect(await screen.findByRole('heading', { name: 'DEMO-1010' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Approval' }))
    expect(await screen.findByText('Manager: Approved')).toBeInTheDocument()
    expect(screen.getByText(/Meera Backup on behalf of Rahul Manager/)).toBeInTheDocument()
    expect(screen.getByText('Finance auditor: Approved')).toBeInTheDocument()
    expect(screen.getByText('Finance head: Waiting')).toBeInTheDocument()
  })

  it('shows lines with violation tags and the audit timeline in plain words', async () => {
    renderApp('/claims/1004')
    // a claim waiting on the employee opens on Comments; switch to Lines
    await userEvent.click(await screen.findByRole('tab', { name: 'Lines' }))
    expect(await screen.findByText('Over hotel limit')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Timeline' }))
    expect(await screen.findByText('Draft created')).toBeInTheDocument()
    expect(screen.getByText('Policy check: sent for approval')).toBeInTheDocument()
    // banner + timeline both mention the send-back
    expect(screen.getAllByText(/Sent back for clarification/).length).toBeGreaterThan(0)
  })

  it('clarification: banner with the approver question, edit and resubmit, timeline keeps both rounds', async () => {
    renderApp('/claims/1004')
    const user = userEvent.setup()
    expect(await screen.findByText('Rahul Manager needs more information')).toBeInTheDocument()
    expect(screen.getAllByText(/Please attach the hotel bill for line 1/).length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: /edit and resubmit/i }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/claims/1004/edit'))
    expect(await screen.findByText('Rahul Manager needs more information')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /resubmit/i }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Resubmit' }))
    expect(await screen.findByText('Sent for approval')).toBeInTheDocument()

    // back to the detail page: no banner any more, timeline shows the first round and the resubmission
    await user.click(screen.getByRole('button', { name: 'My claims' }))
    await user.click(await screen.findByRole('link', { name: /^DEMO-1004/ }))
    expect(await screen.findByRole('heading', { name: 'DEMO-1004' })).toBeInTheDocument()
    expect(screen.queryByText('Rahul Manager needs more information')).toBeNull()
    await user.click(screen.getByRole('tab', { name: 'Timeline' }))
    const timeline = await screen.findByRole('tabpanel', { name: 'Timeline' })
    expect(await within(timeline).findByText('Sent back for clarification')).toBeInTheDocument()
    expect(within(timeline).getAllByText('Submitted')).toHaveLength(2)
  })

  it('adds a comment', async () => {
    renderApp('/claims/1002')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: /Comments/ }))
    expect(await screen.findByText('No comments yet')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send comment' }))
    expect(await screen.findByText('Write a comment first')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Add a comment'), 'Receipt is attached to line 1.')
    await user.click(screen.getByRole('button', { name: 'Send comment' }))
    expect(await screen.findByText('Receipt is attached to line 1.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Comments (1)' })).toBeInTheDocument()
  })

  it('draft shows Edit draft and "Not submitted yet"', async () => {
    renderApp('/claims/1001')
    expect(await screen.findByRole('button', { name: /edit draft/i })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Approval' }))
    expect(await screen.findByText('Not submitted yet.')).toBeInTheDocument()
  })

  it('another user gets "Claim not found"', async () => {
    loginAs(2, 'manager@expenseflow.test', ['EMPLOYEE', 'MANAGER'])
    renderApp('/claims/1004')
    expect(await screen.findByText('Claim not found')).toBeInTheDocument()
  })
})

describe('detail helpers', () => {
  it('maps audit actions and actors to plain words', () => {
    expect(auditActionText('CLAIM_SENT_BACK')).toBe('Sent back for clarification')
    expect(auditActionText('SOMETHING_NEW_HAPPENED')).toBe('Something new happened')
    const rahul = { id: 2, name: 'Rahul Manager', email: 'm@x' }
    const meera = { id: 6, name: 'Meera Backup', email: 'm2@x' }
    expect(auditActor({ action: 'STEP_APPROVED', actor: meera, onBehalfOf: rahul })).toBe('Meera Backup on behalf of Rahul Manager')
    expect(auditActor({ action: 'POLICY_ROUTED' })).toBe('Policy engine')
  })

  it('clarification question falls back to the latest approver comment', () => {
    const employee = { id: 1, name: 'Priya', email: 'p@x' }
    const q = clarificationQuestion({ status: 'CLARIFICATION_REQUESTED', steps: [], employee }, [
      { id: 1, author: employee, text: 'mine', createdAt: '2026-11-20T10:00:00Z' },
      { id: 2, author: { id: 2, name: 'Rahul', email: 'r@x' }, text: 'Which client?', createdAt: '2026-11-21T10:00:00Z' },
    ])
    expect(q).toEqual({ text: 'Which client?', by: 'Rahul', at: '2026-11-21T10:00:00Z' })
    expect(clarificationQuestion({ status: 'APPROVED', steps: [], employee })).toBeNull()
  })
})
