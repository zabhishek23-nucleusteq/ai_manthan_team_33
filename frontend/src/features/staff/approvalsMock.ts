import type { Claim, UserRef } from '../../api/types'
import { err, mockRoute, type MockResponse } from '../../api/mock/router'
import type { ApprovalTask, Delegation } from './approvalsApi'

/** Mock backend (VITE_MOCK=1) for approvals + delegations; shapes follow contracts/openapi.yaml (T-019). */
const APPROVERS: UserRef[] = [
  { id: 2, name: 'Rahul Manager', email: 'manager@expenseflow.test' },
  { id: 3, name: 'Anita Auditor', email: 'auditor@expenseflow.test' },
  { id: 4, name: 'Meera Backup', email: 'manager2@expenseflow.test' },
  { id: 5, name: 'Harish Head', email: 'head@expenseflow.test' },
]

const LEVEL_BY_STATUS: Record<string, ApprovalTask['level']> = {
  PENDING_MANAGER: 'MANAGER',
  PENDING_AUDITOR: 'AUDITOR',
  PENDING_HEAD: 'HEAD',
}

let delegations: Delegation[] = []
let nextDelegationId = 1
const seenKeys = new Map<string, MockResponse>()

export function resetApprovalMocks(): void {
  delegations = []
  nextDelegationId = 1
  seenKeys.clear()
}

const ageHours = (iso?: string) => (iso ? Math.round((Date.now() - Date.parse(iso)) / 360000) / 10 : 0)

export function registerApprovalMocks(getClaims: () => Claim[]): void {
  mockRoute('GET', '/api/approvals/inbox', () => {
    const items: ApprovalTask[] = getClaims()
      .filter((c) => LEVEL_BY_STATUS[c.status])
      .map((c) => ({
        claimId: c.id,
        code: c.code,
        title: c.title,
        employee: c.employee,
        department: c.department,
        total: c.total,
        level: LEVEL_BY_STATUS[c.status],
        onBehalfOf: null,
        violationCodes: [...new Set(c.lines.flatMap((l) => (l.violations ?? []).map((v) => v.code)))],
        sampleAudit: c.sampleAudit === 'PENDING',
        submittedAt: c.submittedAt,
        ageHours: ageHours(c.submittedAt),
      }))
    return { status: 200, body: { items, page: 0, size: items.length, total: items.length } }
  })

  for (const decision of ['approve', 'reject', 'send-back'] as const) {
    mockRoute('POST', `/api/approvals/{id}/${decision}`, ({ params, headers, body }) => {
      const key = headers['Idempotency-Key']
      if (!key) return err(400, 'VALIDATION_FAILED', 'Idempotency-Key header is required')
      const seen = seenKeys.get(key)
      if (seen) return seen
      const comment = (body as { comment?: string } | undefined)?.comment?.trim() ?? ''
      if (decision !== 'approve' && comment.length < 3)
        return err(400, 'VALIDATION_FAILED', 'A comment of at least 3 characters is required')
      const c = getClaims().find((x) => String(x.id) === params.id)
      if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
      const level = LEVEL_BY_STATUS[c.status]
      if (!level) return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim is ${c.status}`)
      const now = new Date().toISOString()
      c.steps = (c.steps ?? []).map((s) =>
        s.decision === 'PENDING'
          ? { ...s, decision: decision === 'approve' ? 'APPROVED' : decision === 'reject' ? 'REJECTED' : 'SENT_BACK', comment, decidedAt: now }
          : s,
      )
      const req = c.policyResult?.requiredLevels ?? ['MANAGER']
      if (decision === 'reject') c.status = 'REJECTED'
      else if (decision === 'send-back') c.status = 'CLARIFICATION_REQUESTED'
      else if (level === 'MANAGER' && req.includes('AUDITOR')) c.status = 'PENDING_AUDITOR'
      else if (level !== 'HEAD' && req.includes('HEAD')) c.status = 'PENDING_HEAD'
      else c.status = 'APPROVED'
      const res: MockResponse = { status: 200, body: structuredClone(c) }
      seenKeys.set(key, res)
      return res
    })
  }

  mockRoute('GET', '/api/delegations', () => ({ status: 200, body: delegations }))

  mockRoute('POST', '/api/delegations', ({ body, userId, email }) => {
    const b = body as { delegateId?: number; from?: string; to?: string } | undefined
    const delegate = APPROVERS.find((u) => u.id === b?.delegateId)
    if (!delegate || !b?.from || !b?.to) return err(422, 'DELEGATION_INVALID', 'Choose a delegate and a date range')
    if (delegate.id === userId) return err(422, 'DELEGATION_INVALID', 'You cannot delegate to yourself')
    const days = (Date.parse(b.to) - Date.parse(b.from)) / 86_400_000
    if (days < 0) return err(422, 'DELEGATION_INVALID', "'to' must not be before 'from'")
    if (days > 30) return err(422, 'DELEGATION_INVALID', 'A delegation lasts at most 30 days')
    const me = APPROVERS.find((u) => u.id === userId) ?? { id: userId ?? 0, name: 'You', email: email ?? '' }
    const d: Delegation = { id: nextDelegationId++, delegator: me, delegate, from: b.from, to: b.to, active: true }
    delegations = [d, ...delegations]
    return { status: 201, body: d }
  })

  mockRoute('DELETE', '/api/delegations/{id}', ({ params }) => {
    const d = delegations.find((x) => String(x.id) === params.id)
    if (!d) return err(404, 'NOT_FOUND', 'Delegation not found')
    d.active = false
    return { status: 204 }
  })

  mockRoute('GET', '/api/users/approvers', ({ query }) => {
    const q = (query.get('q') ?? '').toLowerCase()
    return { status: 200, body: APPROVERS.filter((u) => `${u.name} ${u.email}`.toLowerCase().includes(q)) }
  })
}
