import { Alert, Button, Col, Descriptions, Grid, Input, List, Modal, Row, Space, Tabs, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useIdempotencyKey } from '../../api/idempotency'
import type { Claim, ClaimLine } from '../../api/types'
import { ClaimStatusTag } from '../../components/ClaimStatusTag'
import { PageError, PageLoading } from '../../components/PageState'
import { CATEGORY_LABEL, fmtDate, inr } from '../customer/format'
import { VIOLATION_SHORT } from '../customer/policyHints'
import { approvalErrorText, LEVEL_TEXT, useApprovalClaim, useDecide, type Decision } from './approvalsApi'
import { ReceiptViewer } from './ReceiptViewer'

const PENDING = new Set(['PENDING_MANAGER', 'PENDING_AUDITOR', 'PENDING_HEAD'])

const DECISION_TEXT: Record<Decision, { title: string; ok: string; done: string }> = {
  approve: { title: 'Approve claim', ok: 'Approve', done: 'Claim approved.' },
  reject: { title: 'Reject claim', ok: 'Reject', done: 'Claim rejected.' },
  'send-back': { title: 'Send back for clarification', ok: 'Send back', done: 'Sent back to the employee.' },
}

function Lines({ claim, selected, onSelect }: { claim: Claim; selected: number; onSelect: (i: number) => void }) {
  return (
    <List
      aria-label="Expense lines"
      dataSource={claim.lines}
      renderItem={(l: ClaimLine, i) => (
        <List.Item
          onClick={() => onSelect(i)}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect(i)}
          tabIndex={0}
          aria-current={i === selected}
          style={{ cursor: 'pointer', background: i === selected ? 'rgba(22,119,255,0.08)' : undefined, paddingInline: 8 }}
        >
          <List.Item.Meta
            title={
              <Space wrap>
                <span>
                  {CATEGORY_LABEL[l.category]} · {l.vendor}
                </span>
                {(l.violations ?? []).map((v) => (
                  <Tag key={v.code} color={v.code === 'WEEKEND_SPEND' ? 'gold' : 'orange'}>
                    {VIOLATION_SHORT[v.code] ?? v.code}
                  </Tag>
                ))}
              </Space>
            }
            description={`${fmtDate(l.expenseDate)}${l.city ? ` · ${l.city}` : ''}${l.nights ? ` · ${l.nights} night(s)` : ''}${l.receipt ? '' : ' · no receipt'}`}
          />
          <strong>{inr(l.amount)}</strong>
        </List.Item>
      )}
    />
  )
}

/** /approvals/:claimId (FR-6, FR-7, FR-16): lines left, receipt right (tabs on small screens), decide with Idempotency-Key. */
export function ApprovalDetailPage() {
  const id = Number(useParams().claimId)
  const claim = useApprovalClaim(id)
  const decide = useDecide(id)
  const idem = useIdempotencyKey()
  const screens = Grid.useBreakpoint()
  const [selected, setSelected] = useState(0)
  const [open, setOpen] = useState<Decision | null>(null)
  const [comment, setComment] = useState('')
  const [touched, setTouched] = useState(false)
  const [done, setDone] = useState<string | null>(null)

  const c = claim.data
  const canDecide = !!c && PENDING.has(c.status) && !done
  const needsComment = open === 'reject' || open === 'send-back'
  const commentError = needsComment && comment.trim().length < 3 ? 'Write at least 3 characters so the employee knows why.' : null

  const start = (d: Decision) => {
    decide.reset()
    idem.reset()
    setComment('')
    setTouched(false)
    setOpen(d)
  }

  // Keyboard shortcuts: A approve, R reject, S send back (not while typing).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (!canDecide || open || e.ctrlKey || e.metaKey || e.altKey) return
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      const map: Record<string, Decision> = { a: 'approve', r: 'reject', s: 'send-back' }
      const d = map[e.key.toLowerCase()]
      if (d) {
        e.preventDefault()
        start(d)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canDecide, open])

  const submit = () => {
    setTouched(true)
    if (!open || commentError) return
    const d = open
    decide.mutate(
      { decision: d, comment: comment.trim() || undefined, idempotencyKey: idem.key() },
      {
        onSuccess: () => {
          setDone(DECISION_TEXT[d].done)
          setOpen(null)
          idem.reset()
        },
      },
    )
  }

  const openStep = useMemo(() => c?.steps?.find((s) => s.decision === 'PENDING'), [c])

  if (claim.isPending) return <PageLoading label="Loading claim…" />
  if (claim.isError || !c)
    return (
      <main style={{ padding: 16 }}>
        <PageError title="Could not load the claim" message={approvalErrorText(claim.error)} onRetry={() => void claim.refetch()} />
        <Link to="/approvals">Back to approvals</Link>
      </main>
    )

  const line = c.lines[selected]
  const linesPane = <Lines claim={c} selected={selected} onSelect={setSelected} />
  const receiptPane = <ReceiptViewer key={line?.receipt?.id ?? 'none'} receipt={line?.receipt} />
  const wide = !!screens.lg

  return (
    <main style={{ padding: 16, maxWidth: 1400, margin: '0 auto' }}>
      <Link to="/approvals">← Back to approvals</Link>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginTop: 8 }} wrap>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {c.code}
        </Typography.Title>
        <ClaimStatusTag status={c.status} />
      </Space>
      <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }} style={{ marginTop: 8 }}>
        <Descriptions.Item label="Employee">{c.employee?.name ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="Department">{c.department?.name ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="Total">{inr(c.total)}</Descriptions.Item>
        <Descriptions.Item label="Waiting for">{openStep ? LEVEL_TEXT[openStep.level] : '-'}</Descriptions.Item>
      </Descriptions>
      <Typography.Paragraph type="secondary">{c.title}</Typography.Paragraph>

      <div aria-live="polite">
        {done ? <Alert type="success" showIcon message={done} action={<Link to="/approvals">Next claim</Link>} style={{ marginBottom: 12 }} /> : null}
        {decide.isError && !open ? <Alert type="error" showIcon role="alert" message={approvalErrorText(decide.error)} style={{ marginBottom: 12 }} /> : null}
      </div>

      {canDecide ? (
        <Space wrap style={{ marginBottom: 12 }}>
          <Button type="primary" onClick={() => start('approve')} aria-keyshortcuts="A">
            Approve (A)
          </Button>
          <Button danger onClick={() => start('reject')} aria-keyshortcuts="R">
            Reject (R)
          </Button>
          <Button onClick={() => start('send-back')} aria-keyshortcuts="S">
            Send back (S)
          </Button>
        </Space>
      ) : !done ? (
        <Alert type="info" showIcon message={`This claim is ${c.status.replaceAll('_', ' ').toLowerCase()} and needs no decision from you.`} style={{ marginBottom: 12 }} />
      ) : null}

      {wide ? (
        <Row gutter={16}>
          <Col span={11}>{linesPane}</Col>
          <Col span={13}>{receiptPane}</Col>
        </Row>
      ) : (
        <Tabs items={[{ key: 'lines', label: 'Lines', children: linesPane }, { key: 'receipt', label: 'Receipt', children: receiptPane }]} />
      )}

      <Modal
        open={!!open}
        title={open ? DECISION_TEXT[open].title : ''}
        okText={open ? DECISION_TEXT[open].ok : 'OK'}
        okButtonProps={{ danger: open === 'reject', loading: decide.isPending }}
        onOk={submit}
        onCancel={() => setOpen(null)}
        destroyOnHidden
      >
        <label htmlFor="decision-comment" style={{ display: 'block', marginBottom: 4 }}>
          {needsComment ? 'Reason (required)' : 'Comment (optional)'}
        </label>
        <Input.TextArea
          id="decision-comment"
          rows={3}
          maxLength={2000}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          status={touched && commentError ? 'error' : undefined}
          aria-invalid={!!(touched && commentError)}
          aria-describedby="decision-comment-error"
          autoFocus
        />
        <div id="decision-comment-error" role="alert" style={{ minHeight: 22, color: '#cf1322' }}>
          {touched && commentError ? commentError : decide.isError ? approvalErrorText(decide.error) : ''}
        </div>
      </Modal>
    </main>
  )
}
