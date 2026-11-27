import { Alert } from 'antd'
import type { ReactNode } from 'react'
import { fmtDate } from './format'

export function ClarificationBanner({
  question,
  action,
}: {
  question: { text: string; by?: string; at?: string }
  action?: ReactNode
}) {
  return (
    <Alert
      type="warning"
      showIcon
      role="status"
      style={{ marginBottom: 16 }}
      message={`${question.by ?? 'Your approver'} needs more information`}
      description={
        <>
          <div>“{question.text}”</div>
          {question.at ? <div style={{ opacity: 0.8 }}>{fmtDate(question.at)}</div> : null}
        </>
      }
      action={action}
    />
  )
}
