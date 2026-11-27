import dayjs, { type Dayjs } from 'dayjs'

/** Client-side BR-14 checks; the server repeats them (422 DELEGATION_INVALID). */
export function validateRange(range?: [Dayjs, Dayjs] | null): string | null {
  if (!range || !range[0] || !range[1]) return 'Choose the first and last day.'
  if (range[1].isBefore(range[0], 'day')) return 'The last day must not be before the first day.'
  if (range[1].diff(range[0], 'day') > 30) return 'A delegation lasts at most 30 days.'
  if (range[1].isBefore(dayjs(), 'day')) return 'The period is already over.'
  return null
}
