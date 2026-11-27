import dayjs, { type Dayjs } from 'dayjs'
import { ApiError } from '../../api/errors'
import type { Role } from '../auth/types'
import type { Range } from './adminApi'

export const ALL_ROLES: Role[] = ['EMPLOYEE', 'MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN']

export type Preset = '1D' | '7D' | '1M' | 'Custom'

/** [from, to) as ISO instants for a preset ending now (team standard: 1D / 7D / 1M / Custom). */
export function rangeFor(preset: Exclude<Preset, 'Custom'>, now: Dayjs = dayjs()): Range {
  const days = preset === '1D' ? 1 : preset === '7D' ? 7 : 30
  return { from: now.subtract(days, 'day').toISOString(), to: now.toISOString() }
}

/** Plain-words message for an admin API error. */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'CANNOT_REMOVE_OWN_ADMIN') return 'You cannot remove your own Super admin role or disable yourself.'
    if (e.code === 'EMAIL_TAKEN') return 'A user with this email already exists.'
    return e.message
  }
  return 'Something went wrong. Please try again.'
}
