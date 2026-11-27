import dayjs from 'dayjs'

/** NFR-DASH: one range for every widget, kept in the URL. Presets end "now"; Custom uses explicit from/to. */
export type Preset = '1D' | '7D' | '1M' | 'CUSTOM'

export const DEFAULT_PRESET: Exclude<Preset, 'CUSTOM'> = '7D'

export const PRESET_LABELS: Record<Preset, string> = {
  '1D': '1 Day',
  '7D': '7 Days',
  '1M': '1 Month',
  CUSTOM: 'Custom',
}

const PRESET_DAYS: Record<Exclude<Preset, 'CUSTOM'>, number> = { '1D': 1, '7D': 7, '1M': 30 }
const MAX_DAYS = 366

export interface DateRange {
  preset: Preset
  from: string // ISO instant, inclusive
  to: string // ISO instant, exclusive
}

/** Ranges end at the current minute so React Query keys stay stable within a minute. */
function nowMinute(now: Date): dayjs.Dayjs {
  return dayjs(now).second(0).millisecond(0)
}

export function presetRange(preset: Exclude<Preset, 'CUSTOM'>, now: Date = new Date()): DateRange {
  const to = nowMinute(now)
  return { preset, from: to.subtract(PRESET_DAYS[preset], 'day').toISOString(), to: to.toISOString() }
}

/** Reads ?range=1D|7D|1M or ?range=CUSTOM&from=..&to=..; anything invalid falls back to the 7D default. */
export function rangeFromParams(params: URLSearchParams, now: Date = new Date()): DateRange {
  const raw = (params.get('range') ?? '').toUpperCase()
  if (raw === 'CUSTOM') {
    const from = dayjs(params.get('from'))
    const to = dayjs(params.get('to'))
    if (from.isValid() && to.isValid() && from.isBefore(to) && to.diff(from, 'day', true) <= MAX_DAYS) {
      return { preset: 'CUSTOM', from: from.toISOString(), to: to.toISOString() }
    }
  }
  if (raw === '1D' || raw === '7D' || raw === '1M') {
    return presetRange(raw as Exclude<Preset, 'CUSTOM'>, now)
  }
  return presetRange(DEFAULT_PRESET, now)
}

export function rangeToParams(range: DateRange, current: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(current)
  next.set('range', range.preset)
  if (range.preset === 'CUSTOM') {
    next.set('from', range.from)
    next.set('to', range.to)
  } else {
    next.delete('from')
    next.delete('to')
  }
  return next
}

const fmt = (iso: string) => dayjs(iso).format('D MMM YYYY, HH:mm')

/** The range in words, e.g. "Last 7 days: 20 Nov 2026, 17:40 – 27 Nov 2026, 17:40". */
export function describeRange(range: DateRange): string {
  const span = `${fmt(range.from)} – ${fmt(range.to)}`
  switch (range.preset) {
    case '1D':
      return `Last 24 hours: ${span}`
    case '7D':
      return `Last 7 days: ${span}`
    case '1M':
      return `Last 30 days: ${span}`
    default:
      return `Custom range: ${span}`
  }
}

export { MAX_DAYS }
