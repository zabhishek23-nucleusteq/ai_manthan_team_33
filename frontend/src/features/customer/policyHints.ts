import dayjs, { type Dayjs } from 'dayjs'
import type { Category, ViolationCode } from '../../api/types'

/**
 * Plain-words text for every violation code (microcopy; used when the server message is missing).
 * Server decision is final; hints below are only a client-side preview (BR-1..BR-8 in CLAUDE.md §6).
 */
export const VIOLATION_TEXT: Record<ViolationCode, string> = {
  MEAL_DAILY_LIMIT: 'Meals are above 1,500 INR for this day, so your manager must approve.',
  HOTEL_NIGHTLY_LIMIT: 'Hotel is above the nightly limit (6,000 INR tier-1 city, 4,000 INR other cities).',
  CAB_TRIP_LIMIT: 'Cab trip is above 2,000 INR, so your manager must approve.',
  RECEIPT_REQUIRED: 'A receipt is required for amounts above 500 INR.',
  DUPLICATE_LINE: 'Same vendor, amount and date as an earlier claim line.',
  DUPLICATE_RECEIPT: 'This receipt file was already used on an earlier claim line.',
  WEEKEND_SPEND: 'Expense on a Saturday or Sunday is flagged for your manager.',
}

/** Short flag labels for lists and tags. */
export const VIOLATION_SHORT: Record<ViolationCode, string> = {
  MEAL_DAILY_LIMIT: 'Over meal limit',
  HOTEL_NIGHTLY_LIMIT: 'Over hotel limit',
  CAB_TRIP_LIMIT: 'Over cab limit',
  RECEIPT_REQUIRED: 'Receipt missing',
  DUPLICATE_LINE: 'Duplicate line',
  DUPLICATE_RECEIPT: 'Duplicate receipt',
  WEEKEND_SPEND: 'Weekend spend',
}

export const TIER1_CITIES = ['Mumbai', 'Delhi', 'Bengaluru', 'Chennai', 'Kolkata', 'Hyderabad', 'Pune', 'Ahmedabad']
export const RECEIPT_THRESHOLD = 500

export interface LineDraft {
  category?: Category
  amount?: number | null
  expenseDate?: Dayjs | null
  city?: string
  nights?: number | null
  hasReceipt?: boolean
}

export interface Hint {
  code: ViolationCode
  text: string
  severity: 'warning' | 'danger'
}

export function lineHints(l: LineDraft): Hint[] {
  const out: Hint[] = []
  const amount = l.amount ?? 0
  const add = (code: ViolationCode, severity: Hint['severity'], text = VIOLATION_TEXT[code]) =>
    out.push({ code, severity, text })

  if (amount > RECEIPT_THRESHOLD && !l.hasReceipt) add('RECEIPT_REQUIRED', 'danger')
  if (l.category === 'MEAL' && amount > 1500) add('MEAL_DAILY_LIMIT', 'warning')
  if (l.category === 'CAB' && amount > 2000) add('CAB_TRIP_LIMIT', 'warning')
  if (l.category === 'HOTEL' && amount > 0 && l.nights && l.nights > 0) {
    const tier1 = TIER1_CITIES.some((c) => c.toLowerCase() === (l.city ?? '').trim().toLowerCase())
    const limit = tier1 ? 6000 : 4000
    const perNight = amount / l.nights
    if (perNight > limit)
      add(
        'HOTEL_NIGHTLY_LIMIT',
        'warning',
        `Hotel is ${Math.round(perNight).toLocaleString('en-IN')} INR per night, above the ${limit.toLocaleString('en-IN')} INR limit${tier1 ? ' for tier-1 cities' : ''}.`,
      )
  }
  if (l.expenseDate) {
    const d = dayjs(l.expenseDate).day()
    if (d === 0 || d === 6) add('WEEKEND_SPEND', 'warning')
  }
  return out
}
