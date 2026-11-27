import type { Claim, ClaimLine, ClaimStatus, ClaimSummary, Department, UserRef, Violation } from '../types'

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
      steps: submitted && s.status !== 'AUTO_REJECTED' ? [{ level: 'MANAGER', assignee: RAHUL, decision: 'PENDING' }] : [],
      sampleAudit: 'NONE',
      createdAt: daysAgoIso(s.ageDays + 1),
      submittedAt: submitted ? daysAgoIso(s.ageDays) : undefined,
      version: 1,
    }
  })
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
