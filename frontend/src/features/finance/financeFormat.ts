import { inr } from '../customer/format'
import { isRate, type PayoutBatchStatus, type PolicyAction, type PolicyRule } from './financeApi'

export const BATCH_STATUS: Record<PayoutBatchStatus, { label: string; color: string }> = {
  CREATED: { label: 'Ready to submit', color: 'blue' },
  SUBMITTED: { label: 'Sent to bank', color: 'processing' },
  PAID: { label: 'Paid', color: 'success' },
  SUBMIT_FAILED: { label: 'Submit failed', color: 'error' },
  CANCELLED: { label: 'Cancelled', color: 'default' },
}

export const UNIT_LABEL: Record<string, string> = {
  PER_DAY: 'per day',
  PER_NIGHT: 'per night',
  PER_TRIP: 'per trip',
  PER_LINE: 'per line',
  PER_CLAIM: 'per claim',
}

export const ACTION_META: Record<PolicyAction, { label: string; color: string }> = {
  ROUTE: { label: 'Route for approval', color: 'gold' },
  REJECT: { label: 'Reject claim', color: 'red' },
}

/** "1,500 INR per day", "10%" for the sample-audit rate, "-" when the rule has no limit. */
export function limitText(r: Pick<PolicyRule, 'code' | 'limitAmount' | 'unit'>): string {
  if (r.limitAmount === null || r.limitAmount === undefined) return '-'
  if (isRate(r)) return `${Math.round(r.limitAmount * 1000) / 10}%`
  return `${inr(r.limitAmount)}${r.unit && UNIT_LABEL[r.unit] ? ` ${UNIT_LABEL[r.unit]}` : ''}`
}
