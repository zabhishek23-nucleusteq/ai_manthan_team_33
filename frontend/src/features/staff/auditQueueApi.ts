import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Query } from '../../api/client'
import { newIdempotencyKey } from '../../api/idempotency'
import type { Category, Claim, ClaimStatus, Department, ViolationCode } from '../../api/types'
import type { ApprovalTaskPage } from './approvalsApi'

/** Query params of GET /api/audit-queue (contracts/openapi.yaml, FR-9). */
export interface AuditQueueFilters {
  departmentId?: number
  category?: Category
  minAmount?: number
  maxAmount?: number
  violation?: ViolationCode
  sampleOnly?: boolean
  olderThanHours?: number
  sort?: 'submittedAt' | '-submittedAt' | 'total' | '-total'
  page: number
  size: number
}

export interface BulkResult {
  claimId: number
  ok: boolean
  code?: string
  status?: ClaimStatus
}

const num = (v: string | null): number | undefined => {
  if (v === null || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

/** URL <-> filters, so a refresh or a shared link keeps the auditor's view. */
export function filtersFromParams(p: URLSearchParams): AuditQueueFilters {
  const sort = p.get('sort')
  return {
    departmentId: num(p.get('departmentId')),
    category: (p.get('category') || undefined) as Category | undefined,
    minAmount: num(p.get('minAmount')),
    maxAmount: num(p.get('maxAmount')),
    violation: (p.get('violation') || undefined) as ViolationCode | undefined,
    sampleOnly: p.get('sampleOnly') === 'true' || undefined,
    olderThanHours: num(p.get('olderThanHours')),
    sort: sort === 'submittedAt' || sort === '-submittedAt' || sort === 'total' || sort === '-total' ? sort : undefined,
    page: Math.max(0, num(p.get('page')) ?? 0),
    size: Math.min(100, Math.max(1, num(p.get('size')) ?? 20)),
  }
}

export function filtersToParams(f: AuditQueueFilters): URLSearchParams {
  const p = new URLSearchParams()
  const set = (k: string, v: unknown) => {
    if (v !== undefined && v !== null && v !== '' && v !== false) p.set(k, String(v))
  }
  set('departmentId', f.departmentId)
  set('category', f.category)
  set('minAmount', f.minAmount)
  set('maxAmount', f.maxAmount)
  set('violation', f.violation)
  set('sampleOnly', f.sampleOnly)
  set('olderThanHours', f.olderThanHours)
  set('sort', f.sort)
  if (f.page > 0) set('page', f.page)
  if (f.size !== 20) set('size', f.size)
  return p
}

export function useAuditQueue(f: AuditQueueFilters) {
  return useQuery({
    queryKey: ['audit-queue', f],
    queryFn: () => api.get<ApprovalTaskPage>('/api/audit-queue', { ...f } as Query),
    placeholderData: (prev) => prev,
  })
}

export function useDepartments() {
  return useQuery({
    queryKey: ['departments'],
    queryFn: () => api.get<Department[]>('/api/departments'),
    staleTime: 10 * 60_000,
  })
}

export function useBulkApprove() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ claimIds, comment }: { claimIds: number[]; comment?: string }) =>
      api.post<{ results: BulkResult[] }>(
        '/api/audit-queue/bulk-approve',
        { claimIds, ...(comment ? { comment } : {}) },
        { idempotencyKey: newIdempotencyKey() },
      ),
    onSettled: () => void qc.invalidateQueries({ queryKey: ['audit-queue'] }),
  })
}

export function useSampleAudit() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ claimId, result, comment }: { claimId: number; result: 'OK' | 'EXCEPTION'; comment?: string }) =>
      api.post<Claim>(`/api/audit-queue/${claimId}/sample-audit`, { result, ...(comment ? { comment } : {}) }),
    onSettled: () => void qc.invalidateQueries({ queryKey: ['audit-queue'] }),
  })
}

/** Plain words for per-item bulk failure codes. */
export const BULK_CODE_TEXT: Record<string, string> = {
  CONCURRENT_MODIFICATION: 'changed by someone else - reload and retry',
  ILLEGAL_STATE_TRANSITION: 'no longer waiting for the auditor',
  NOT_ASSIGNED_APPROVER: 'not assigned to you',
  SELF_APPROVAL_FORBIDDEN: 'your own claim - another auditor must approve it',
  NOT_FOUND: 'not found',
}
