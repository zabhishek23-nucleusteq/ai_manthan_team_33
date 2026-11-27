import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../App'
import { brandTokens, getThemeConfig } from './theme'
import { ThemeProvider } from './ThemeProvider'

describe('theme', () => {
  it('defines brand tokens for light mode', () => {
    const cfg = getThemeConfig('light')
    expect(cfg.token?.colorPrimary).toBe(brandTokens.colorPrimary)
    expect(cfg.token?.borderRadius).toBe(6) // docs/ux/design-tokens.md
    expect(cfg.token?.fontSize).toBe(14)
  })

  it('uses the dark algorithm and a lighter primary in dark mode', () => {
    const light = getThemeConfig('light')
    const dark = getThemeConfig('dark')
    expect(dark.algorithm).not.toBe(light.algorithm)
    expect(dark.token?.colorPrimary).not.toBe(light.token?.colorPrimary)
  })

  it('renders the /design page and toggles dark mode', async () => {
    render(
      <ThemeProvider initialMode="light">
        <MemoryRouter initialEntries={['/design']}>
          <AppRoutes />
        </MemoryRouter>
      </ThemeProvider>,
    )
    expect(screen.getByRole('heading', { name: 'ExpenseFlow design' })).toBeInTheDocument()
    expect(screen.getByText('Claim status tags')).toBeInTheDocument()
    expect(screen.getAllByText('DEMO-1001').length).toBeGreaterThan(0)
    expect(screen.getByLabelText('Title')).toBeInTheDocument()

    const toggle = screen.getByRole('switch', { name: 'Dark mode' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('shows a 404 result for unknown routes', () => {
    render(
      <ThemeProvider initialMode="light">
        <MemoryRouter initialEntries={['/nope']}>
          <AppRoutes />
        </MemoryRouter>
      </ThemeProvider>,
    )
    expect(screen.getByText('Page not found')).toBeInTheDocument()
  })
})
