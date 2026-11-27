import { Button, Card, Flex, Result, Steps, Typography } from 'antd'
import { useEffect, useRef, type ReactNode } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import type { ApprovalLevel, Claim, Violation } from '../../api/types'
import { ClaimStatusTag } from '../../components/ClaimStatusTag'
import { PageError, PageLoading } from '../../components/PageState'
import { useClaim } from './claimsApi'
import { CustomerPage } from './CustomerPage'
import { CATEGORY_LABEL, inr } from './format'
import { VIOLATION_TEXT } from './policyHints'

type Outcome = 'AUTO_APPROVED' | 'ROUTED' | 'AUTO_REJECTED'

const LEVEL_TEXT: Record<ApprovalLevel, string> = {
  MANAGER: 'Your manager',
  AUDITOR: 'Finance auditor (above 25,000 INR)',
  HEAD: 'Finance head (above 1,00,000 INR)',
}

function outcomeOf(c: Claim): Outcome | null {
  if (c.policyResult?.outcome) return c.policyResult.outcome
  if (c.status === 'AUTO_REJECTED') return 'AUTO_REJECTED'
  if (c.status.startsWith('PENDING_')) return 'ROUTED'
  if (c.status === 'APPROVED' && !(c.steps ?? []).length) return 'AUTO_APPROVED'
  return null
}

/** Every violation, named by line, category and amount (wireframes §3). */
function violationRows(c: Claim): Array<{ key: string; text: string; action: Violation['action'] }> {
  const byLine = new Map<number, Violation[]>()
  for (const lr of c.policyResult?.lineResults ?? []) byLine.set(lr.lineId, lr.violations ?? [])
  return c.lines.flatMap((l, i) => {
    const vs = byLine.get(l.id) ?? l.violations ?? []
    return vs.map((v, j) => ({
      key: `${l.id}-${j}`,
      action: v.action,
      text: `Line ${i + 1} · ${CATEGORY_LABEL[l.category]} ${inr(l.amount)}: ${v.message || VIOLATION_TEXT[v.code] || v.code}`,
    }))
  })
}

export function ClaimOutcome({ claim }: { claim: Claim }) {
  const navigate = useNavigate()
  const titleRef = useRef<HTMLSpanElement>(null)
  useEffect(() => titleRef.current?.focus(), [claim.id])

  const outcome = outcomeOf(claim)
  const rows = violationRows(claim)
  const summary = `${claim.code} · ${inr(claim.total)}`
  const title = (text: string) => (
    <span ref={titleRef} tabIndex={-1} style={{ outline: 'none' }}>
      {text}
    </span>
  )
  const reasons = rows.length ? (
    <ul style={{ textAlign: 'left', paddingInlineStart: 20, margin: 0 }}>
      {rows.map((r) => (
        <li key={r.key}>
          <Typography.Text type={r.action === 'REJECT' ? 'danger' : 'warning'}>{r.text}</Typography.Text>
        </li>
      ))}
    </ul>
  ) : null

  const actions = (primary: ReactNode) => (
    <Flex gap={8} wrap justify="center">
      {primary}
      <Button onClick={() => navigate('/claims')}>My claims</Button>
    </Flex>
  )

  // Details (chain, reasons) go below the Result, not inside it: Result's inner box is too narrow at 360px.
  let content
  let details: ReactNode = null
  if (outcome === 'AUTO_APPROVED') {
    content = (
      <Result
        status="success"
        title={title('Claim auto-approved')}
        subTitle={`${summary}. All ${claim.lines.length} line${claim.lines.length === 1 ? ' is' : 's are'} within policy. It will be added to the next payout batch.`}
        extra={actions(
          <Button type="primary" onClick={() => navigate('/claims/new')}>
            New claim
          </Button>,
        )}
      />
    )
  } else if (outcome === 'ROUTED') {
    const levels: ApprovalLevel[] = claim.policyResult?.requiredLevels?.length
      ? claim.policyResult.requiredLevels
      : ['MANAGER', ...(claim.total > 25000 ? (['AUDITOR'] as const) : []), ...(claim.total > 100000 ? (['HEAD'] as const) : [])]
    const current = { PENDING_MANAGER: 0, PENDING_AUDITOR: 1, PENDING_HEAD: 2 }[claim.status as string] ?? 0
    content = (
      <Result
        status="info"
        title={title('Sent for approval')}
        subTitle={summary}
        extra={actions(
          <Button type="primary" onClick={() => navigate('/claims/new')}>
            New claim
          </Button>,
        )}
      />
    )
    details = (
      <>
        <Typography.Title level={5}>Approval chain</Typography.Title>
        <Steps
          direction="vertical"
          size="small"
          current={Math.min(current, levels.length - 1)}
          items={levels.map((l) => ({ title: LEVEL_TEXT[l] }))}
        />
        {reasons ? (
          <>
            <Typography.Title level={5}>Why it needs approval</Typography.Title>
            {reasons}
          </>
        ) : null}
      </>
    )
  } else if (outcome === 'AUTO_REJECTED') {
    content = (
      <Result
        status="error"
        title={title('Claim rejected by policy')}
        subTitle={summary}
        extra={actions(
          <Button type="primary" onClick={() => navigate('/claims/new', { state: { copyFrom: claim } })}>
            Copy to new draft
          </Button>,
        )}
      />
    )
    details = (
      <>
        <Typography.Title level={5}>Reasons</Typography.Title>
        {reasons ?? <Typography.Text>{(claim.policyResult?.reasons ?? []).join('; ') || 'Policy rule violated.'}</Typography.Text>}
      </>
    )
  } else {
    content = (
      <Result
        status="info"
        title={title(claim.title)}
        subTitle={
          <span>
            {summary} · <ClaimStatusTag status={claim.status} />
          </span>
        }
        extra={actions(null)}
      />
    )
    details = reasons
  }

  return (
    <Card>
      <div aria-live="polite">
        {content}
        {details ? <div style={{ maxWidth: 560, margin: '0 auto' }}>{details}</div> : null}
      </div>
    </Card>
  )
}

/** /claims/:id/submitted (result after submit) and, until T-023 ships the detail page, /claims/:id. */
export function ClaimOutcomePage() {
  const { id } = useParams()
  const location = useLocation()
  const fromState = (location.state as { claim?: Claim } | null)?.claim
  const claimId = Number(id)
  const stateMatches = fromState?.id === claimId
  const q = useClaim(stateMatches ? undefined : claimId)
  const claim = stateMatches ? fromState : q.data
  const back = { to: '/claims', label: 'My claims' }

  if (!claim && q.isError) {
    const notFound = isApiError(q.error) && q.error.status === 404
    return (
      <CustomerPage title="Claim" back={back}>
        <PageError
          title={notFound ? 'Claim not found' : 'We could not load the claim'}
          message={notFound ? 'It does not exist or is not yours.' : 'Please try again.'}
          onRetry={notFound ? undefined : () => void q.refetch()}
        />
      </CustomerPage>
    )
  }
  if (!claim)
    return (
      <CustomerPage title="Claim" back={back}>
        <PageLoading label="Loading claim…" />
      </CustomerPage>
    )
  return (
    <CustomerPage title={claim.code} back={back}>
      <ClaimOutcome claim={claim} />
    </CustomerPage>
  )
}
