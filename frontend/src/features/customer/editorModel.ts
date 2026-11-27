import dayjs, { type Dayjs } from 'dayjs'
import type { Category, Claim, ClaimInput, Receipt } from '../../api/types'

export interface LineForm {
  category?: Category
  amount?: number | null
  expenseDate?: Dayjs | null
  vendor?: string
  description?: string
  city?: string
  nights?: number | null
  receipt?: Receipt
}

export interface EditorForm {
  title: string
  lines: LineForm[]
}

export const MAX_LINES = 50
export const emptyLine = (): LineForm => ({ category: undefined, amount: null, expenseDate: null, vendor: '' })

export function claimToForm(c: Pick<Claim, 'title' | 'lines'>): EditorForm {
  return {
    title: c.title ?? '',
    lines: c.lines.map((l) => ({
      category: l.category,
      amount: l.amount,
      expenseDate: l.expenseDate ? dayjs(l.expenseDate) : null,
      vendor: l.vendor,
      description: l.description,
      city: l.city,
      nights: l.nights,
      receipt:
        l.receipt ??
        (l.receiptId ? { id: l.receiptId, fileName: `Receipt #${l.receiptId}`, contentType: '', size: 0, sha256: '' } : undefined),
    })),
  }
}

export function formToInput(v: EditorForm): ClaimInput {
  return {
    title: v.title.trim(),
    lines: (v.lines ?? []).map((l) => ({
      category: l.category!,
      amount: Number(l.amount),
      expenseDate: l.expenseDate!.format('YYYY-MM-DD'),
      vendor: (l.vendor ?? '').trim(),
      ...(l.description?.trim() ? { description: l.description.trim() } : {}),
      ...(l.category === 'HOTEL' ? { city: l.city?.trim(), nights: l.nights ?? undefined } : {}),
      ...(l.receipt ? { receiptId: l.receipt.id } : {}),
    })),
  }
}
