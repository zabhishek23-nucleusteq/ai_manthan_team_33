import dayjs from 'dayjs'
import type { Category, ClaimStatus } from '../../api/types'

export const EDITABLE_STATUSES: readonly ClaimStatus[] = ['DRAFT', 'CLARIFICATION_REQUESTED']

/** Editable claims open in the editor, others in the read-only view. */
export const claimHref = (c: { id: number; status: ClaimStatus }): string =>
  EDITABLE_STATUSES.includes(c.status) ? `/claims/${c.id}/edit` : `/claims/${c.id}`

const inrFmt = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2, minimumFractionDigits: 0 })

/** "32,450 INR" (en-IN grouping, per wireframes §7). */
export const inr = (n: number | undefined | null): string => `${inrFmt.format(n ?? 0)} INR`

/** "18 Nov 2026". */
export const fmtDate = (iso?: string | null): string => (iso ? dayjs(iso).format('DD MMM YYYY') : '-')

export const CATEGORY_LABEL: Record<Category, string> = {
  MEAL: 'Meals',
  HOTEL: 'Hotel',
  CAB: 'Local cab',
  TRAVEL: 'Travel',
  OTHER: 'Other',
}

export const CATEGORY_OPTIONS = (Object.keys(CATEGORY_LABEL) as Category[]).map((value) => ({
  value,
  label: CATEGORY_LABEL[value],
}))
