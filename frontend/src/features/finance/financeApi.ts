import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, api } from '../../api/client'
import type { Category, Page, UserRef } from '../../api/types'

/** Mirrors backend PayoutController / PolicyRuleController DTOs (contracts/openapi.yaml, FR-11, FR-5). */
export type PayoutBatchStatus = 'CREATED' | 'SUBMITTED' | 'PAID' | 'SUBMIT_FAILED' | 'CANCELLED'

export interface PayoutItem {
  claimId: number
  claimCode: string
  employee?: UserRef
  amount: number
}

export interface PayoutBatch {
  id: number
  code: string
  status: PayoutBatchStatus
  itemCount: number
  total: number
  valueDate?: string
  bankReference?: string | null
  lastError?: string | null
  createdAt?: string
  submittedAt?: string | null
  items?: PayoutItem[] | null
}

export interface PayoutEligible {
  count: number
  total: number
}

export type PolicyAction = 'ROUTE' | 'REJECT'

export interface PolicyRule {
  code: string
  description: string
  category?: Category | null
  limitAmount?: number | null
  unit?: string | null
  params?: Record<string, unknown> | null
  action?: PolicyAction | null
  enabled: boolean
  updatedAt?: string
}

/** Null / missing fields stay unchanged on the server. */
export interface PolicyRuleInput {
  limitAmount?: number
  action?: PolicyAction
  enabled?: boolean
}

/** A row without an action is a setting (BR-9/11/12/13 thresholds), not a rule that flags lines. */
export const isSetting = (r: Pick<PolicyRule, 'action'>): boolean => !r.action

/** SAMPLE_AUDIT_RATE is a share (0..1), every other limit is INR. */
export const isRate = (r: Pick<PolicyRule, 'code'>): boolean => r.code === 'SAMPLE_AUDIT_RATE'

/** Client-side check before PUT (server repeats it: 422 POLICY_RULE_INVALID). Returns an error message or null. */
export function checkLimit(rule: Pick<PolicyRule, 'code'>, value: number | null | undefined): string | null {
  if (value === null || value === undefined || Number.isNaN(value)) return 'Enter a value'
  if (value < 0) return 'The value cannot be negative'
  if (isRate(rule) && value > 1) return 'Sample audit rate must be between 0 and 1'
  return null
}

export const financeKeys = {
  batches: (page: number) => ['payout-batches', 'list', page] as const,
  batch: (id: number) => ['payout-batches', 'detail', id] as const,
  eligible: ['payout-batches', 'eligible'] as const,
  rules: ['policy-rules'] as const,
}

export function usePayoutBatches(page: number, size: number) {
  return useQuery({
    queryKey: financeKeys.batches(page),
    queryFn: () => api.get<Page<PayoutBatch>>('/api/payout-batches', { page, size }),
    placeholderData: (prev) => prev,
  })
}

export function usePayoutBatch(id: number | undefined) {
  return useQuery({
    queryKey: financeKeys.batch(id ?? -1),
    queryFn: () => api.get<PayoutBatch>(`/api/payout-batches/${id}`),
    enabled: id !== undefined,
  })
}

export function usePayoutEligible(enabled: boolean) {
  return useQuery({
    queryKey: financeKeys.eligible,
    queryFn: () => api.get<PayoutEligible>('/api/payout-batches/eligible'),
    enabled,
  })
}

function invalidateBatches(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ['payout-batches'] })
}

export function useCreateBatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (idempotencyKey: string) => api.post<PayoutBatch>('/api/payout-batches', undefined, { idempotencyKey }),
    onSuccess: (b) => {
      qc.setQueryData(financeKeys.batch(b.id), b)
      invalidateBatches(qc)
    },
  })
}

/** 200 = bank took the file; 502 BANK_UNAVAILABLE = batch is SUBMIT_FAILED and can be retried (new key). */
export function useSubmitBatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, idempotencyKey }: { id: number; idempotencyKey: string }) =>
      api.post<PayoutBatch>(`/api/payout-batches/${id}/submit`, undefined, { idempotencyKey }),
    onSettled: () => invalidateBatches(qc),
  })
}

/** Downloads the bank file with the Bearer token (a plain link would not send it). */
export async function downloadBatchCsv(b: Pick<PayoutBatch, 'id' | 'code'>): Promise<void> {
  const csv = await apiFetch<string>(`/api/payout-batches/${b.id}/export.csv`, { headers: { Accept: 'text/csv' } })
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `${b.code}.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function usePolicyRules() {
  return useQuery({ queryKey: financeKeys.rules, queryFn: () => api.get<PolicyRule[]>('/api/policy-rules') })
}

export function useUpdatePolicyRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ code, input }: { code: string; input: PolicyRuleInput }) =>
      api.put<PolicyRule>(`/api/policy-rules/${encodeURIComponent(code)}`, input),
    onSuccess: (r) => {
      qc.setQueryData<PolicyRule[]>(financeKeys.rules, (old) => old?.map((x) => (x.code === r.code ? r : x)))
    },
  })
}
