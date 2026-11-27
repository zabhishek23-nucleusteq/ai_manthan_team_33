import { err, mockRoute, type MockRequest, type MockResponse } from '../../api/mock/router'
// Same payload as the real API: settings have action CONFIG, SAMPLE_AUDIT_RATE has unit RATE (T-031 blank-page bug).
// Registered from api/mock/handlers.ts registerDefaultMocks(); state reset by resetMockState().
import type { PayoutBatch, PayoutEligible, PolicyRule } from './financeApi'

/** VITE_MOCK=1 backend for payout batches + policy rules (same rules and error codes as the Spring Boot API). */

const PRIYA = { id: 1, name: 'Priya Employee', email: 'employee@expenseflow.test' }
const ARJUN = { id: 6, name: 'Arjun Rao', email: 'arjun.rao@expenseflow.test' }

const daysAgo = (d: number) => new Date(Date.now() - d * 86_400_000).toISOString()

function buildBatches(): PayoutBatch[] {
  return [
    {
      id: 3, code: 'PB-0003', status: 'SUBMIT_FAILED', itemCount: 2, total: 9400, valueDate: daysAgo(1).slice(0, 10),
      lastError: 'Bank timeout after 5s', createdAt: daysAgo(1), submittedAt: daysAgo(1),
      items: [
        { claimId: 1011, claimCode: 'EXP-50011', employee: PRIYA, amount: 4200 },
        { claimId: 1012, claimCode: 'EXP-50012', employee: ARJUN, amount: 5200 },
      ],
    },
    {
      id: 2, code: 'PB-0002', status: 'PAID', itemCount: 3, total: 18750.5, valueDate: daysAgo(6).slice(0, 10),
      bankReference: 'MOCKBANK-7781', createdAt: daysAgo(6), submittedAt: daysAgo(6),
      items: [
        { claimId: 1005, claimCode: 'EXP-50005', employee: PRIYA, amount: 6250.5 },
        { claimId: 1006, claimCode: 'EXP-50006', employee: ARJUN, amount: 7000 },
        { claimId: 1007, claimCode: 'EXP-50007', employee: ARJUN, amount: 5500 },
      ],
    },
    {
      id: 1, code: 'PB-0001', status: 'PAID', itemCount: 1, total: 3200, valueDate: daysAgo(20).slice(0, 10),
      bankReference: 'MOCKBANK-6610', createdAt: daysAgo(20), submittedAt: daysAgo(20),
      items: [{ claimId: 1001, claimCode: 'EXP-50001', employee: PRIYA, amount: 3200 }],
    },
  ]
}

function buildRules(): PolicyRule[] {
  const at = daysAgo(1)
  const r = (code: string, description: string, limitAmount: number | null, unit: string | null, action: PolicyRule['action'], category: PolicyRule['category'] = null): PolicyRule =>
    ({ code, description, category, limitAmount, unit, params: null, action, enabled: true, updatedAt: at })
  return [
    r('MEAL_DAILY_LIMIT', 'BR-1 Meals up to 1,500 INR per employee per day', 1500, 'PER_DAY', 'ROUTE', 'MEAL'),
    { ...r('HOTEL_NIGHTLY_LIMIT', 'BR-2/BR-3 Hotel up to 6,000 INR per night in tier-1 cities, 4,000 elsewhere', 6000, 'PER_NIGHT', 'ROUTE', 'HOTEL'),
      params: { otherCitiesLimit: 4000, tier1Cities: ['Mumbai', 'Delhi', 'Bengaluru'] } },
    r('CAB_TRIP_LIMIT', 'BR-4 Local cab up to 2,000 INR per trip (one line = one trip)', 2000, 'PER_TRIP', 'ROUTE', 'CAB'),
    r('RECEIPT_REQUIRED', 'BR-5 Receipt mandatory above 500 INR', 500, 'PER_LINE', 'REJECT'),
    r('DUPLICATE_LINE', 'BR-6 Same vendor + amount + date as an earlier line', null, 'PER_LINE', 'REJECT'),
    r('DUPLICATE_RECEIPT', 'BR-7 Same receipt file (SHA-256) as an earlier line', null, 'PER_LINE', 'REJECT'),
    r('WEEKEND_SPEND', 'BR-8 Weekend spend flagged for manager attention', null, 'PER_LINE', 'ROUTE'),
    r('AUTO_APPROVE_MAX', 'BR-9 Clean claims up to this total are auto-approved (INR)', 5000, 'PER_CLAIM', 'CONFIG'),
    r('CHAIN_AUDITOR_ABOVE', 'BR-11 Finance auditor approval needed above this total (INR)', 25000, 'PER_CLAIM', 'CONFIG'),
    r('CHAIN_HEAD_ABOVE', 'BR-12 Finance head approval needed above this total (INR)', 100000, 'PER_CLAIM', 'CONFIG'),
    r('SAMPLE_AUDIT_RATE', 'BR-13 Share of auto-approved claims flagged for sample audit (0-1)', 0.1, 'RATE', 'CONFIG'),
  ]
}

let batches = buildBatches()
let rules = buildRules()
let eligible: PayoutEligible = { count: 4, total: 21480 }
let nextBatchId = 4
/** Next submit attempts the mock bank fails (tests: setMockBankFailures(1)). */
let bankFailures = 0
const idem = new Map<string, MockResponse>()

export function resetFinanceMocks(): void {
  batches = buildBatches()
  rules = buildRules()
  eligible = { count: 4, total: 21480 }
  nextBatchId = 4
  bankFailures = 0
  idem.clear()
}

export function setMockBankFailures(n: number): void {
  bankFailures = n
}

export function setMockEligible(e: PayoutEligible): void {
  eligible = e
}

const has = (req: MockRequest, ...roles: string[]) => roles.some((r) => req.roles.includes(r))
const forbidden = () => err(403, 'FORBIDDEN', 'You do not have access to this action')
const summary = (b: PayoutBatch): PayoutBatch => ({ ...b, items: null })

function withIdempotency(req: MockRequest, run: () => MockResponse): MockResponse {
  const key = req.headers['Idempotency-Key']
  if (!key) return err(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header is required')
  const k = `${req.method} ${req.path} ${key}`
  const hit = idem.get(k)
  if (hit) return hit
  const out = run()
  idem.set(k, out)
  return out
}

export function registerFinanceMocks(): void {
  const readRoles = ['FINANCE_AUDITOR', 'FINANCE_HEAD']

  mockRoute('GET', '/api/payout-batches', (req) => {
    if (!has(req, ...readRoles)) return forbidden()
    const page = Number(req.query.get('page') ?? 0)
    const size = Number(req.query.get('size') ?? 20)
    const items = batches.slice(page * size, page * size + size).map(summary)
    return { status: 200, body: { items, page, size, total: batches.length } }
  })

  mockRoute('GET', '/api/payout-batches/eligible', (req) =>
    has(req, ...readRoles) ? { status: 200, body: eligible } : forbidden(),
  )

  mockRoute('GET', '/api/payout-batches/{id}/export.csv', (req) => {
    if (!has(req, ...readRoles)) return forbidden()
    const b = batches.find((x) => String(x.id) === req.params.id)
    if (!b) return err(404, 'NOT_FOUND', 'Payout batch not found')
    const rows = (b.items ?? []).map((i) => `D,${b.code},${b.valueDate},E${i.employee?.id},${i.employee?.name},XXXX1234,MOCK0001,${i.amount.toFixed(2)},INR,${i.claimCode}`)
    const csv = ['RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION', ...rows].join('\n')
    return { status: 200, body: csv, headers: { 'Content-Type': 'text/csv' } }
  })

  mockRoute('GET', '/api/payout-batches/{id}', (req) => {
    if (!has(req, ...readRoles)) return forbidden()
    const b = batches.find((x) => String(x.id) === req.params.id)
    return b ? { status: 200, body: b } : err(404, 'NOT_FOUND', 'Payout batch not found')
  })

  mockRoute('POST', '/api/payout-batches', (req) => {
    if (!has(req, 'FINANCE_HEAD')) return forbidden()
    return withIdempotency(req, () => {
      if (eligible.count === 0) return err(422, 'NO_ELIGIBLE_CLAIMS', 'No approved claims are waiting for payout')
      const id = nextBatchId++
      const share = Math.round((eligible.total / eligible.count) * 100) / 100
      const items = Array.from({ length: eligible.count }, (_, i) => ({
        claimId: 1100 + i, claimCode: `EXP-${50100 + i}`, employee: i % 2 ? ARJUN : PRIYA,
        amount: i === eligible.count - 1 ? Math.round((eligible.total - share * (eligible.count - 1)) * 100) / 100 : share,
      }))
      const b: PayoutBatch = {
        id, code: `PB-${String(id).padStart(4, '0')}`, status: 'CREATED', itemCount: eligible.count, total: eligible.total,
        valueDate: new Date().toISOString().slice(0, 10), createdAt: new Date().toISOString(), items,
      }
      batches = [b, ...batches]
      eligible = { count: 0, total: 0 }
      return { status: 201, body: b }
    })
  })

  mockRoute('POST', '/api/payout-batches/{id}/submit', (req) => {
    if (!has(req, 'FINANCE_HEAD')) return forbidden()
    return withIdempotency(req, () => {
      const b = batches.find((x) => String(x.id) === req.params.id)
      if (!b) return err(404, 'NOT_FOUND', 'Payout batch not found')
      if (b.status !== 'CREATED' && b.status !== 'SUBMIT_FAILED')
        return err(409, 'ILLEGAL_STATE_TRANSITION', `Batch ${b.code} is ${b.status} and cannot be submitted`)
      b.submittedAt = new Date().toISOString()
      if (bankFailures > 0) {
        bankFailures--
        b.status = 'SUBMIT_FAILED'
        b.lastError = 'Bank timeout after 5s'
        return err(502, 'BANK_UNAVAILABLE', 'The bank did not take the file; the batch is SUBMIT_FAILED and can be retried. Bank timeout after 5s')
      }
      b.status = 'PAID'
      b.lastError = null
      b.bankReference = `MOCKBANK-${8000 + b.id}`
      return { status: 200, body: b }
    })
  })

  mockRoute('GET', '/api/policy-rules', (req) =>
    has(req, 'MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN') ? { status: 200, body: rules } : forbidden(),
  )

  mockRoute('PUT', '/api/policy-rules/{code}', (req) => {
    if (!has(req, 'FINANCE_HEAD')) return forbidden()
    const rule = rules.find((r) => r.code === req.params.code)
    if (!rule) return err(404, 'POLICY_RULE_NOT_FOUND', `No policy rule ${req.params.code}`)
    const b = (req.body ?? {}) as { limitAmount?: number; action?: 'ROUTE' | 'REJECT'; enabled?: boolean }
    if (b.limitAmount !== undefined && b.limitAmount < 0)
      return err(400, 'VALIDATION_FAILED', 'Validation failed', { fields: { limitAmount: 'must be greater than or equal to 0' } })
    if (rule.code === 'SAMPLE_AUDIT_RATE' && b.limitAmount !== undefined && b.limitAmount > 1)
      return err(422, 'POLICY_RULE_INVALID', 'SAMPLE_AUDIT_RATE must be between 0 and 1')
    if (rule.action === 'CONFIG' && b.action) return err(422, 'POLICY_RULE_INVALID', `${rule.code} is a setting and has no action`)
    if (rule.action === 'CONFIG' && b.enabled === false) return err(422, 'POLICY_RULE_INVALID', `${rule.code} cannot be disabled`)
    Object.assign(rule, {
      ...(b.limitAmount !== undefined ? { limitAmount: b.limitAmount } : {}),
      ...(b.action ? { action: b.action } : {}),
      ...(b.enabled !== undefined ? { enabled: b.enabled } : {}),
      updatedAt: new Date().toISOString(),
    })
    return { status: 200, body: { ...rule } }
  })
}
