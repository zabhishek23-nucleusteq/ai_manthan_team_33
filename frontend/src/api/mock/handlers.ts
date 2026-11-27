import type { Claim, ClaimInput } from '../types'
import { buildClaims, DEPARTMENTS, toSummary } from './fixtures'
import { err, mockRoute, type MockResponse } from './router'

/** In-memory state for the mock backend; reset with resetMockState(). */
let claims: Claim[] = buildClaims()
let nextClaimId = 2001
const idempotency = new Map<string, { claimId: number; response: MockResponse }>()

export function resetMockState(): void {
  claims = buildClaims()
  nextClaimId = 2001
  idempotency.clear()
}

const EDITABLE = new Set(['DRAFT', 'CLARIFICATION_REQUESTED'])

function ownClaim(userId: number | null, id: string): Claim | undefined {
  // Non-owners get 404, never 403 (no enumeration; rbac.md).
  return claims.find((c) => String(c.id) === id && c.employee?.id === userId)
}

function validateInput(body: unknown): MockResponse | ClaimInput {
  const b = body as Partial<ClaimInput> | undefined
  const fields: Record<string, string> = {}
  if (!b?.title || b.title.trim().length < 3 || b.title.length > 120) fields.title = 'Title must be 3 to 120 characters'
  if (!Array.isArray(b?.lines)) fields.lines = 'lines is required'
  else if (b.lines.length > 50) fields.lines = 'At most 50 lines'
  else
    b.lines.forEach((l, i) => {
      if (!(l.amount > 0)) fields[`lines[${i}].amount`] = 'Amount must be greater than 0'
      if (!l.vendor) fields[`lines[${i}].vendor`] = 'Vendor is required'
      if (!l.expenseDate) fields[`lines[${i}].expenseDate`] = 'Date is required'
    })
  if (Object.keys(fields).length) return err(400, 'VALIDATION_FAILED', 'Validation failed', { fields })
  return b as ClaimInput
}

function applyInput(c: Claim, input: ClaimInput): void {
  let id = Math.max(0, ...claims.flatMap((x) => x.lines.map((l) => l.id))) + 1
  c.title = input.title.trim()
  c.lines = input.lines.map((l) => ({ ...l, id: id++, violations: [] }))
  c.total = Math.round(c.lines.reduce((s, l) => s + l.amount, 0) * 100) / 100
}

export function registerDefaultMocks(): void {
  mockRoute('GET', '/api/departments', () => ({ status: 200, body: DEPARTMENTS }))

  mockRoute('GET', '/api/claims', ({ userId, query }) => {
    const status = query.get('status')
    const page = Math.max(0, Number(query.get('page') ?? 0))
    const size = Math.min(100, Math.max(1, Number(query.get('size') ?? 20)))
    const mine = claims
      .filter((c) => c.employee?.id === userId && (!status || c.status === status))
      .sort((a, b) => (b.submittedAt ?? b.createdAt ?? '').localeCompare(a.submittedAt ?? a.createdAt ?? ''))
    return {
      status: 200,
      body: { items: mine.slice(page * size, page * size + size).map(toSummary), page, size, total: mine.length },
    }
  })

  mockRoute('POST', '/api/claims', ({ userId, body }) => {
    const input = validateInput(body)
    if ('status' in input) return input
    const c: Claim = {
      id: nextClaimId,
      code: `EXP-${String(nextClaimId).padStart(6, '0')}`,
      title: '',
      status: 'DRAFT',
      total: 0,
      employee: { id: userId!, name: 'You', email: '' },
      lines: [],
      steps: [],
      sampleAudit: 'NONE',
      createdAt: new Date().toISOString(),
      version: 1,
    }
    nextClaimId++
    applyInput(c, input)
    claims.push(c)
    return { status: 201, body: c }
  })

  mockRoute('GET', '/api/claims/{id}', ({ userId, params }) => {
    const c = ownClaim(userId, params.id)
    return c ? { status: 200, body: c } : err(404, 'NOT_FOUND', 'Claim not found')
  })

  mockRoute('PUT', '/api/claims/{id}', ({ userId, params, body }) => {
    const c = ownClaim(userId, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot edit`, { from: c.status, action: 'edit' })
    const input = validateInput(body)
    if ('status' in input) return input
    applyInput(c, input)
    c.version = (c.version ?? 1) + 1
    return { status: 200, body: c }
  })

  mockRoute('DELETE', '/api/claims/{id}', ({ userId, params }) => {
    const c = ownClaim(userId, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot delete`, { from: c.status, action: 'delete' })
    claims = claims.filter((x) => x !== c)
    return { status: 204 }
  })

  mockRoute('POST', '/api/claims/{id}/submit', ({ userId, params, headers }) => {
    const key = headers['Idempotency-Key']
    if (!key || key.length < 8 || key.length > 64)
      return err(400, 'VALIDATION_FAILED', 'Idempotency-Key header is required (8-64 chars)')
    const seen = idempotency.get(key)
    if (seen) {
      return String(seen.claimId) === params.id
        ? seen.response
        : err(422, 'IDEMPOTENCY_KEY_REUSED', 'This Idempotency-Key was used for another request')
    }
    const c = ownClaim(userId, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot submit`, { from: c.status, action: 'submit' })
    if (c.lines.length === 0) return err(422, 'CLAIM_EMPTY', 'Add at least one expense line before submitting')
    // Simplified policy for the mock: real rules (BR-1..12) live in the backend.
    const needsReceipt = c.lines.some((l) => l.amount > 500 && !l.receiptId)
    c.status = needsReceipt ? 'AUTO_REJECTED' : c.total <= 5000 ? 'APPROVED' : 'PENDING_MANAGER'
    c.submittedAt = new Date().toISOString()
    c.version = (c.version ?? 1) + 1
    const response: MockResponse = { status: 200, body: structuredClone(c) }
    idempotency.set(key, { claimId: c.id, response })
    return response
  })
}
