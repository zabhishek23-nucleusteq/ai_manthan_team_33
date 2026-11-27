import { useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { DateRange } from './range'

/** contracts/openapi.yaml: Kpis (GET /api/dashboard/kpis, T-027). */
export interface Kpis {
  range: { from: string; to: string; bucket: 'HOUR' | 'DAY' | 'WEEK' }
  scope: 'OWN' | 'TEAM' | 'ALL'
  totals: {
    spend: number
    claims: number
    pendingApprovals: number
    violations: number
    autoApprovedRate: number
    avgApprovalHours: number | null
  }
  spendByCategory: { category: string; spend: number }[]
  spendByDepartment: { department: string; spend: number }[]
  spendByMonth: { month: string; spend: number }[]
  series: { start: string; spend: number; claims: number }[]
  violationsByRule: { code: string; count: number }[]
  approvalTimeByLevel: { level: string; avgHours: number }[]
}

export function useKpis(range: DateRange) {
  return useQuery({
    queryKey: ['dashboard', 'kpis', range.from, range.to],
    queryFn: () => api.get<Kpis>('/api/dashboard/kpis', { from: range.from, to: range.to }),
    placeholderData: (prev) => prev,
  })
}

/** Fire-and-forget page load report (NFR-OBS histogram page_load_seconds{route}); never breaks the page. */
export function reportPageLoad(route: string, ms: number): void {
  const clamped = Math.max(0, Math.min(120_000, Math.round(ms)))
  api.post('/api/telemetry/page-load', { route, ms: clamped }).catch(() => undefined)
}

export function isEmpty(k: Kpis): boolean {
  return k.totals.claims === 0 && k.totals.pendingApprovals === 0 && k.violationsByRule.length === 0
}
