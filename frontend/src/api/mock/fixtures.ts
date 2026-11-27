import type {
  ApprovalStep,
  AuditEvent,
  Claim,
  ClaimLine,
  ClaimStatus,
  ClaimSummary,
  Comment,
  Department,
  UserRef,
  Violation,
} from '../types'

/** Synthetic fixtures only (demo users from rbac.md §4). Dates are relative to "now". */
export const DEPARTMENTS: Department[] = [
  { id: 1, name: 'Engineering' },
  { id: 2, name: 'Sales' },
  { id: 3, name: 'Finance' },
  { id: 4, name: 'Operations' },
  { id: 5, name: 'Human Resources' },
]

export const PRIYA: UserRef = { id: 1, name: 'Priya Employee', email: 'employee@expenseflow.test' }
export const RAHUL: UserRef = { id: 2, name: 'Rahul Manager', email: 'manager@expenseflow.test' }
export const ANITA: UserRef = { id: 3, name: 'Anita Auditor', email: 'auditor@expenseflow.test' }

const DAY = 86_400_000
export const daysAgoIso = (d: number, hours = 10): string => {
  const t = new Date(Date.now() - d * DAY)
  t.setUTCHours(hours, 0, 0, 0)
  return t.toISOString()
}
export const daysAgoDate = (d: number): string => daysAgoIso(d).slice(0, 10)

const v = (code: Violation['code'], action: Violation['action'], message: string): Violation => ({ code, action, message })

let lineId = 1
const line = (l: Omit<ClaimLine, 'id'>): ClaimLine => ({ id: lineId++, violations: [], ...l })

interface Seed {
  code: string
  title: string
  status: ClaimStatus
  ageDays: number
  lines: Array<Omit<ClaimLine, 'id'>>
}

const seeds: Seed[] = [
  {
    code: 'DEMO-1001',
    title: 'Client visit taxi',
    status: 'DRAFT',
    ageDays: 0,
    lines: [{ category: 'CAB', amount: 450, expenseDate: daysAgoDate(0), vendor: 'City Cabs' }],
  },
  {
    code: 'DEMO-1002',
    title: 'Team lunch Pune',
    status: 'PENDING_MANAGER',
    ageDays: 1,
    lines: [
      {
        category: 'MEAL',
        amount: 1800,
        expenseDate: daysAgoDate(2),
        vendor: 'Cafe Blue',
        receiptId: 12,
        violations: [v('MEAL_DAILY_LIMIT', 'ROUTE', 'Meals above 1,500 INR for the day need manager approval')],
      },
    ],
  },
  {
    code: 'DEMO-1003',
    title: 'Conference Bengaluru',
    status: 'PENDING_AUDITOR',
    ageDays: 4,
    lines: [
      { category: 'TRAVEL', amount: 14500, expenseDate: daysAgoDate(6), vendor: 'Air Shakti', receiptId: 13 },
      {
        category: 'HOTEL',
        amount: 12000,
        expenseDate: daysAgoDate(6),
        vendor: 'Hotel Sunrise',
        city: 'Bengaluru',
        nights: 2,
        receiptId: 14,
      },
    ],
  },
  {
    code: 'DEMO-1004',
    title: 'Hotel stay Nagpur',
    status: 'CLARIFICATION_REQUESTED',
    ageDays: 6,
    lines: [
      {
        category: 'HOTEL',
        amount: 9000,
        expenseDate: daysAgoDate(9),
        vendor: 'Green Leaf Inn',
        city: 'Nagpur',
        nights: 2,
        receiptId: 15,
        violations: [v('HOTEL_NIGHTLY_LIMIT', 'ROUTE', 'Hotel above 4,000 INR per night outside tier-1 cities')],
      },
    ],
  },
  {
    code: 'DEMO-1005',
    title: 'Cab without receipt',
    status: 'AUTO_REJECTED',
    ageDays: 8,
    lines: [
      {
        category: 'CAB',
        amount: 900,
        expenseDate: daysAgoDate(9),
        vendor: 'Metro Taxi',
        violations: [v('RECEIPT_REQUIRED', 'REJECT', 'A receipt is required for amounts above 500 INR')],
      },
    ],
  },
  {
    code: 'DEMO-1006',
    title: 'Stationery',
    status: 'APPROVED',
    ageDays: 12,
    lines: [{ category: 'OTHER', amount: 1200, expenseDate: daysAgoDate(13), vendor: 'Paper Mart', receiptId: 16 }],
  },
  {
    code: 'DEMO-1007',
    title: 'Flight to Delhi',
    status: 'PAID',
    ageDays: 25,
    lines: [{ category: 'TRAVEL', amount: 7400, expenseDate: daysAgoDate(27), vendor: 'Air Shakti', receiptId: 17 }],
  },
  {
    code: 'DEMO-1008',
    title: 'Weekend client dinner',
    status: 'REJECTED',
    ageDays: 18,
    lines: [
      {
        category: 'MEAL',
        amount: 2600,
        expenseDate: daysAgoDate(20),
        vendor: 'Spice Route',
        receiptId: 18,
        violations: [v('WEEKEND_SPEND', 'ROUTE', 'Expense on a Saturday or Sunday')],
      },
    ],
  },
  {
    code: 'DEMO-1009',
    title: 'Sales offsite',
    status: 'IN_PAYOUT',
    ageDays: 15,
    lines: [{ category: 'TRAVEL', amount: 32000, expenseDate: daysAgoDate(17), vendor: 'Rail Connect', receiptId: 19 }],
  },
  {
    code: 'DEMO-1010',
    title: 'Board meeting travel',
    status: 'PENDING_HEAD',
    ageDays: 3,
    lines: [
      { category: 'TRAVEL', amount: 68000, expenseDate: daysAgoDate(5), vendor: 'Air Shakti', receiptId: 20 },
      {
        category: 'HOTEL',
        amount: 42000,
        expenseDate: daysAgoDate(5),
        vendor: 'Grand Palace',
        city: 'Mumbai',
        nights: 7,
        receiptId: 21,
      },
    ],
  },
]

const round2 = (n: number) => Math.round(n * 100) / 100

const FARHAN: UserRef = { id: 4, name: 'Farhan Head', email: 'head@expenseflow.test' }
const MEERA: UserRef = { id: 6, name: 'Meera Backup', email: 'manager2@expenseflow.test' }

export const CLARIFICATION_QUESTION = 'Please attach the hotel bill for line 1 and confirm the number of nights.'

/** Approval steps that match each status (ADR-001 chain: MANAGER -> AUDITOR > 25,000 -> HEAD > 1,00,000). */
function stepsFor(status: ClaimStatus, ageDays: number, total: number): ApprovalStep[] {
  const at = (d: number) => daysAgoIso(Math.max(0, ageDays - d), 14)
  const done = (level: ApprovalStep['level'], who: UserRef, d: number, extra: Partial<ApprovalStep> = {}): ApprovalStep => ({
    level,
    assignee: who,
    actedBy: who,
    decision: 'APPROVED',
    createdAt: at(d - 1),
    decidedAt: at(d),
    ...extra,
  })
  const pending = (level: ApprovalStep['level'], who: UserRef, d: number): ApprovalStep => ({
    level,
    assignee: who,
    decision: 'PENDING',
    createdAt: at(d),
  })
  switch (status) {
    case 'PENDING_MANAGER':
      return [pending('MANAGER', RAHUL, 0)]
    case 'PENDING_AUDITOR':
      return [done('MANAGER', RAHUL, 1), pending('AUDITOR', ANITA, 1)]
    case 'PENDING_HEAD':
      // Rahul was on leave: Meera approved on his behalf (delegation).
      return [
        done('MANAGER', RAHUL, 1, { actedBy: MEERA, onBehalfOf: RAHUL, comment: 'Approved while Rahul is on leave' }),
        done('AUDITOR', ANITA, 2),
        pending('HEAD', FARHAN, 2),
      ]
    case 'CLARIFICATION_REQUESTED':
      return [done('MANAGER', RAHUL, 1, { decision: 'SENT_BACK', comment: CLARIFICATION_QUESTION })]
    case 'REJECTED':
      return [done('MANAGER', RAHUL, 2, { decision: 'REJECTED', comment: 'Client dinner was not pre-approved.' })]
    case 'APPROVED':
    case 'IN_PAYOUT':
    case 'PAID':
      return total > 5000
        ? [done('MANAGER', RAHUL, 1), ...(total > 25000 ? [done('AUDITOR', ANITA, 2)] : [])]
        : []
    default:
      return []
  }
}

export function buildClaims(): Claim[] {
  lineId = 1
  return seeds.map((s, i) => {
    const lines = s.lines.map(line)
    const total = round2(lines.reduce((sum, l) => sum + l.amount, 0))
    const submitted = s.status !== 'DRAFT'
    return {
      id: 1001 + i,
      code: s.code,
      title: s.title,
      status: s.status,
      total,
      employee: PRIYA,
      department: DEPARTMENTS[0],
      lines,
      steps: stepsFor(s.status, s.ageDays, total),
      sampleAudit: 'NONE',
      createdAt: daysAgoIso(s.ageDays + 1),
      submittedAt: submitted ? daysAgoIso(s.ageDays) : undefined,
      version: 1,
    }
  })
}

/** Seed comments: the manager's clarification question on DEMO-1004. */
export function buildComments(claims: Claim[]): Map<number, Comment[]> {
  const m = new Map<number, Comment[]>()
  const c1004 = claims.find((c) => c.code === 'DEMO-1004')
  if (c1004) {
    const sentBack = c1004.steps?.[0]?.decidedAt ?? daysAgoIso(5)
    m.set(c1004.id, [{ id: 1, author: RAHUL, text: CLARIFICATION_QUESTION, createdAt: sentBack }])
  }
  return m
}

/** Audit timeline derived from a claim (the real backend reads its append-only audit table). */
export function auditFor(c: Claim, extra: AuditEvent[] = []): AuditEvent[] {
  const ev: AuditEvent[] = []
  let id = c.id * 100
  const push = (at: string | undefined, action: string, actor?: UserRef, more: Partial<AuditEvent> = {}) => {
    if (at) ev.push({ id: id++, at, action, actor, entityType: 'CLAIM', entityId: String(c.id), ...more })
  }
  push(c.createdAt, 'CLAIM_CREATED', c.employee)
  if (c.submittedAt) {
    push(c.submittedAt, 'CLAIM_SUBMITTED', c.employee)
    const outcome =
      c.policyResult?.outcome ??
      (c.status === 'AUTO_REJECTED' ? 'AUTO_REJECTED' : (c.steps ?? []).length ? 'ROUTED' : 'AUTO_APPROVED')
    push(c.submittedAt, `POLICY_${outcome}`, undefined, { after: { outcome } })
  }
  for (const s of c.steps ?? []) {
    if (s.decision === 'PENDING' || !s.decidedAt) continue
    const action = { APPROVED: 'STEP_APPROVED', REJECTED: 'STEP_REJECTED', SENT_BACK: 'CLAIM_SENT_BACK', SUPERSEDED: 'STEP_SUPERSEDED' }[
      s.decision
    ]
    push(s.decidedAt, action ?? `STEP_${s.decision}`, s.actedBy ?? s.assignee, {
      onBehalfOf: s.onBehalfOf,
      after: { level: s.level, ...(s.comment ? { comment: s.comment } : {}) },
    })
  }
  if (c.status === 'IN_PAYOUT' || c.status === 'PAID') push(c.decidedAt ?? c.submittedAt, 'ADDED_TO_PAYOUT_BATCH', FARHAN)
  if (c.status === 'PAID') push(c.decidedAt ?? c.submittedAt, 'CLAIM_PAID')
  return [...ev, ...extra].sort((a, b) => a.at.localeCompare(b.at))
}

export function toSummary(c: Claim): ClaimSummary {
  const codes = new Set(c.lines.flatMap((l) => (l.violations ?? []).map((x) => x.code)))
  return {
    id: c.id,
    code: c.code,
    title: c.title,
    status: c.status,
    total: c.total,
    lineCount: c.lines.length,
    violationCodes: [...codes],
    submittedAt: c.submittedAt,
    updatedAt: c.submittedAt ?? c.createdAt,
  }
}
