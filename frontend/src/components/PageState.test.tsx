import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { claimStatusMeta } from '../design/claimStatus'
import { ClaimStatusTag } from './ClaimStatusTag'
import { PageEmpty, PageError, PageLoading } from './PageState'

describe('page state wrappers', () => {
  it('PageLoading announces loading', () => {
    render(<PageLoading label="Loading claims…" />)
    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveTextContent('Loading claims…')
  })

  it('PageEmpty shows description and runs the action', async () => {
    const onAction = vi.fn()
    render(<PageEmpty description="No claims yet" actionLabel="New claim" onAction={onAction} />)
    expect(screen.getByText('No claims yet')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'New claim' }))
    expect(onAction).toHaveBeenCalledOnce()
  })

  it('PageEmpty without action renders no button', () => {
    render(<PageEmpty />)
    expect(screen.getByText('Nothing here yet')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('PageError is an alert with a working Retry', async () => {
    const onRetry = vi.fn()
    render(<PageError message="Could not load claims." onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load claims.')
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('PageError without onRetry hides Retry', () => {
    render(<PageError />)
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull()
  })
})

describe('claim status tag', () => {
  it('maps known statuses to plain words', () => {
    render(<ClaimStatusTag status="CLARIFICATION_REQUESTED" />)
    expect(screen.getByText('Clarification requested')).toBeInTheDocument()
  })

  it('falls back for unknown statuses', () => {
    expect(claimStatusMeta('ON_HOLD')).toEqual({ color: 'default', label: 'On hold' })
  })
})
