import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../App'
import { ThemeProvider } from '../design/ThemeProvider'
import { DEMO_USERS, DemoSessionProvider, type SessionUser } from './session'

const demo = (id: string): SessionUser => {
  const u = DEMO_USERS.find((d) => d.id === id)
  if (!u) throw new Error(id)
  return u
}

function renderAt(path: string, user: SessionUser | null) {
  return render(
    <ThemeProvider initialMode="light">
      <DemoSessionProvider initialUser={user}>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </DemoSessionProvider>
    </ThemeProvider>,
  )
}

describe('app shell', () => {
  it.each([
    ['demo-employee', 'My dashboard'],
    ['demo-manager', 'Team dashboard'],
    ['demo-auditor', 'Finance dashboard'],
    ['demo-head', 'Admin overview'],
    ['demo-admin', 'Admin overview'],
  ])('%s lands on %s', async (id, title) => {
    renderAt('/', demo(id))
    expect(await screen.findByRole('heading', { level: 3, name: title })).toBeInTheDocument()
    expect(within(screen.getByTestId('current-user')).getByText(demo(id).name)).toBeInTheDocument()
  })

  it('shows every KPI with its definition', async () => {
    renderAt('/manager/dashboard', demo('demo-manager'))
    expect(await screen.findByLabelText(/^Waiting for me: Approval steps assigned to you now/)).toBeInTheDocument()
  })

  it('direct URL to a forbidden page shows 403 inside the shell', async () => {
    renderAt('/payouts', demo('demo-manager'))
    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to my dashboard' })).toBeInTheDocument()
  })

  it('employee cannot open admin pages', async () => {
    renderAt('/admin/users', demo('demo-employee'))
    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument()
  })

  it('no session -> demo sign in, then back to the requested page', async () => {
    renderAt('/approvals', null)
    expect(await screen.findByRole('heading', { name: /demo sign in/ })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in as Rahul Manager' }))
    expect(await screen.findByText('This screen is being built (T-025).')).toBeInTheDocument()
  })

  it('logout returns to sign in', async () => {
    renderAt('/me/dashboard', demo('demo-employee'))
    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }))
    expect(await screen.findByRole('heading', { name: /demo sign in/ })).toBeInTheDocument()
  })

  it('on small screens the menu opens in a drawer with only the role items', async () => {
    renderAt('/me/dashboard', demo('demo-employee'))
    await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }))
    const menu = await screen.findByRole('menu', { name: 'Main menu' })
    expect(within(menu).getByText('My claims')).toBeInTheDocument()
    expect(within(menu).queryByText('Approvals')).not.toBeInTheDocument()
    await userEvent.click(within(menu).getByText('My claims'))
    expect(await screen.findByText('This screen is being built (T-022).')).toBeInTheDocument()
  })

  it('unknown route still shows 404', () => {
    renderAt('/nope/really', demo('demo-employee'))
    expect(screen.getByText('Page not found')).toBeInTheDocument()
  })
})
