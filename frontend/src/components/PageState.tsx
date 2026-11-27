import { Button, Empty, Result, Skeleton, Spin } from 'antd'
import type { ReactNode } from 'react'

/** Loading state: announced to screen readers, skeleton keeps the layout stable. */
export function PageLoading({ label = 'Loading…', rows = 4 }: { label?: string; rows?: number }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" style={{ padding: 24 }}>
      <Spin aria-hidden="true" />
      <span style={{ marginInlineStart: 12 }}>{label}</span>
      <Skeleton active paragraph={{ rows }} title={false} style={{ marginTop: 16 }} />
    </div>
  )
}

interface EmptyProps {
  description?: ReactNode
  actionLabel?: string
  onAction?: () => void
}

/** Empty state: says what is missing and, if possible, offers the next action. */
export function PageEmpty({ description = 'Nothing here yet', actionLabel, onAction }: EmptyProps) {
  return (
    <div role="status" aria-live="polite" style={{ padding: 24 }}>
      <Empty description={description}>
        {actionLabel && onAction ? (
          <Button type="primary" onClick={onAction}>
            {actionLabel}
          </Button>
        ) : null}
      </Empty>
    </div>
  )
}

interface ErrorProps {
  title?: ReactNode
  message?: ReactNode
  onRetry?: () => void
}

/** Error state: plain-words message plus a Retry button when the caller can retry. */
export function PageError({ title = 'Something went wrong', message = 'Please try again.', onRetry }: ErrorProps) {
  return (
    <div role="alert">
      <Result
        status="error"
        title={title}
        subTitle={message}
        extra={
          onRetry ? (
            <Button type="primary" onClick={onRetry}>
              Retry
            </Button>
          ) : undefined
        }
      />
    </div>
  )
}
