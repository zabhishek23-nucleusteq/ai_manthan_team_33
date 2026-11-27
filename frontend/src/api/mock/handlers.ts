import type { ApprovalLevel, Claim, ClaimInput, Violation } from '../types'
import { buildClaims, DEPARTMENTS, toSummary } from './fixtures'
import { err, mockRoute, type MockRequest, type MockResponse } from './router'

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

type Who = Pick<MockRequest, 'userId' | 'email'>

/**
 * Owner = same email when both sides have one (fixtures belong to the demo employee in mock and real-auth mode;
 * real backend ids can collide with fixture ids), otherwise same user id.
 */
function isOwner(c: Claim, who: Who): boolean {
  if (!c.employee) return false
  if (who.email && c.employee.email) return c.employee.email.toLowerCase() === who.email.toLowerCase()
  return c.employee.id === who.userId
}

function ownClaim(who: Who, id: string): Claim | undefined {
  // Non-owners get 404, never 403 (no enumeration; rbac.md).
  return claims.find((c) => String(c.id) === id && isOwner(c, who))
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

const TIER1 = ['mumbai', 'delhi', 'bengaluru', 'chennai', 'kolkata', 'hyderabad', 'pune', 'ahmedabad']

/**
 * Simplified per-line policy (BR-4..9 shapes) so the UI shows realistic results in mock mode.
 * The backend policy engine (T-015) is the real authority.
 */
function applyMockPolicy(c: Claim): void {
  const lineResults = c.lines.map((l) => {
    const vs: Violation[] = []
    if (l.amount > 500 && !l.receiptId)
      vs.push({ code: 'RECEIPT_REQUIRED', action: 'REJECT', message: `Receipt required above 500 INR (amount ${l.amount})` })
    if (l.category === 'MEAL' && l.amount > 1500)
      vs.push({ code: 'MEAL_DAILY_LIMIT', action: 'ROUTE', message: `Meals ${l.amount} INR above 1,500 INR daily limit` })
    if (l.category === 'CAB' && l.amount > 2000)
      vs.push({ code: 'CAB_TRIP_LIMIT', action: 'ROUTE', message: `Cab ${l.amount} INR above 2,000 INR per trip` })
    if (l.category === 'HOTEL' && l.nights) {
      const limit = TIER1.includes((l.city ?? '').trim().toLowerCase()) ? 6000 : 4000
      const perNight = l.amount / l.nights
      if (perNight > limit)
        vs.push({ code: 'HOTEL_NIGHTLY_LIMIT', action: 'ROUTE', message: `Hotel ${Math.round(perNight)} INR per night above ${limit} INR limit` })
    }
    const day = new Date(`${l.expenseDate}T00:00:00Z`).getUTCDay()
    if (day === 0 || day === 6) vs.push({ code: 'WEEKEND_SPEND', action: 'ROUTE', message: 'Expense on a weekend' })
    l.violations = vs
    return { lineId: l.id, violations: vs }
  })
  const all = lineResults.flatMap((r) => r.violations)
  const outcome = all.some((v) => v.action === 'REJECT')
    ? 'AUTO_REJECTED'
    : all.length || c.total > 5000
      ? 'ROUTED'
      : 'AUTO_APPROVED'
  const requiredLevels: ApprovalLevel[] =
    outcome === 'ROUTED' ? ['MANAGER', ...(c.total > 25000 ? ['AUDITOR' as const] : []), ...(c.total > 100000 ? ['HEAD' as const] : [])] : []
  c.policyResult = { outcome, reasons: [...new Set(all.map((v) => v.code))], lineResults, requiredLevels }
  c.status = outcome === 'AUTO_REJECTED' ? 'AUTO_REJECTED' : outcome === 'AUTO_APPROVED' ? 'APPROVED' : 'PENDING_MANAGER'
  c.steps = outcome === 'ROUTED' ? [{ level: 'MANAGER', decision: 'PENDING', createdAt: new Date().toISOString() }] : []
}

let nextReceiptId = 500

export function registerDefaultMocks(): void {
  mockRoute('POST', '/api/receipts', ({ body }) => {
    const file = body instanceof FormData ? body.get('file') : null
    if (!(file instanceof Blob)) return err(400, 'VALIDATION_FAILED', 'file is required')
    if (!['image/jpeg', 'image/png', 'application/pdf'].includes(file.type))
      return err(415, 'UNSUPPORTED_FILE_TYPE', 'Only PDF, JPG or PNG receipts are accepted')
    if (file.size > 5 * 1024 * 1024) return err(413, 'RECEIPT_TOO_LARGE', 'Receipt is larger than 5 MB')
    return {
      status: 201,
      body: {
        id: nextReceiptId++,
        fileName: (file as File).name ?? 'receipt',
        contentType: file.type,
        size: file.size,
        sha256: `mock-${nextReceiptId}-${file.size}`,
      },
    }
  })

  mockRoute('GET', '/api/departments', () => ({ status: 200, body: DEPARTMENTS }))

  mockRoute('GET', '/api/claims', (req) => {
    const { query } = req
    const status = query.get('status')
    const page = Math.max(0, Number(query.get('page') ?? 0))
    const size = Math.min(100, Math.max(1, Number(query.get('size') ?? 20)))
    const mine = claims
      .filter((c) => isOwner(c, req) && (!status || c.status === status))
      .sort((a, b) => (b.submittedAt ?? b.createdAt ?? '').localeCompare(a.submittedAt ?? a.createdAt ?? ''))
    return {
      status: 200,
      body: { items: mine.slice(page * size, page * size + size).map(toSummary), page, size, total: mine.length },
    }
  })

  mockRoute('POST', '/api/claims', ({ userId, email, body }) => {
    const input = validateInput(body)
    if ('status' in input) return input
    const c: Claim = {
      id: nextClaimId,
      code: `EXP-${String(nextClaimId).padStart(6, '0')}`,
      title: '',
      status: 'DRAFT',
      total: 0,
      employee: { id: userId!, name: 'You', email: email ?? '' },
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

  mockRoute('GET', '/api/claims/{id}', (req) => {
    const { params } = req
    const c = ownClaim(req, params.id)
    return c ? { status: 200, body: c } : err(404, 'NOT_FOUND', 'Claim not found')
  })

  mockRoute('PUT', '/api/claims/{id}', (req) => {
    const { params, body } = req
    const c = ownClaim(req, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot edit`, { from: c.status, action: 'edit' })
    const input = validateInput(body)
    if ('status' in input) return input
    applyInput(c, input)
    c.version = (c.version ?? 1) + 1
    return { status: 200, body: c }
  })

  mockRoute('DELETE', '/api/claims/{id}', (req) => {
    const { params } = req
    const c = ownClaim(req, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot delete`, { from: c.status, action: 'delete' })
    claims = claims.filter((x) => x !== c)
    return { status: 204 }
  })

  mockRoute('POST', '/api/claims/{id}/submit', (req) => {
    const { params, headers } = req
    const key = headers['Idempotency-Key']
    if (!key || key.length < 8 || key.length > 64)
      return err(400, 'VALIDATION_FAILED', 'Idempotency-Key header is required (8-64 chars)')
    const seen = idempotency.get(key)
    if (seen) {
      return String(seen.claimId) === params.id
        ? seen.response
        : err(422, 'IDEMPOTENCY_KEY_REUSED', 'This Idempotency-Key was used for another request')
    }
    const c = ownClaim(req, params.id)
    if (!c) return err(404, 'NOT_FOUND', 'Claim not found')
    if (!EDITABLE.has(c.status))
      return err(409, 'ILLEGAL_STATE_TRANSITION', `Claim ${c.code} is ${c.status}; cannot submit`, { from: c.status, action: 'submit' })
    if (c.lines.length === 0) return err(422, 'CLAIM_EMPTY', 'Add at least one expense line before submitting')
    applyMockPolicy(c)
    c.submittedAt = new Date().toISOString()
    c.version = (c.version ?? 1) + 1
    const response: MockResponse = { status: 200, body: structuredClone(c) }
    idempotency.set(key, { claimId: c.id, response })
    return response
  })
}
