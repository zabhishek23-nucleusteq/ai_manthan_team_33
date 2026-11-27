import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { ApprovalLevel, Claim, Department, UserRef, ViolationCode } from '../../api/types'

/** contracts/openapi.yaml ApprovalTask / ApprovalTaskPage / Delegation (T-019 backend). */
export interface ApprovalTask {
  claimId: number
  code: string
  title: string
  employee?: UserRef
  department?: Department
  total: number
  level: ApprovalLevel
  onBehalfOf?: UserRef | null
  violationCodes: ViolationCode[]
  sampleAudit: boolean
  submittedAt?: string
  ageHours: number
}

export interface ApprovalTaskPage {
  items: ApprovalTask[]
  page: number
  size: number
  total: number
}

export interface Delegation {
  id: number
  delegator: UserRef
  delegate: UserRef
  from: string
  to: string
  active: boolean
}

export interface DelegationInput {
  delegateId: number
  from: string
  to: string
}

export type Decision = 'approve' | 'reject' | 'send-back'

export const approvalKeys = {
  inbox: (page: number, size: number) => ['approvals', 'inbox', page, size] as const,
  claim: (id: number) => ['approvals', 'claim', id] as const,
  delegations: ['delegations'] as const,
  approvers: (q: string) => ['approvers', q] as const,
}

export function useInbox(page: number, size: number) {
  return useQuery({
    queryKey: approvalKeys.inbox(page, size),
    queryFn: () => api.get<ApprovalTaskPage>('/api/approvals/inbox', { page, size }),
    placeholderData: (prev) => prev,
  })
}

export function useApprovalClaim(id: number) {
  return useQuery({
    queryKey: approvalKeys.claim(id),
    queryFn: () => api.get<Claim>(`/api/claims/${id}`),
    enabled: Number.isFinite(id),
  })
}

export function useDecide(claimId: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ decision, comment, idempotencyKey }: { decision: Decision; comment?: string; idempotencyKey: string }) =>
      api.post<Claim>(`/api/approvals/${claimId}/${decision}`, comment ? { comment } : {}, { idempotencyKey }),
    onSuccess: (c) => {
      qc.setQueryData(approvalKeys.claim(claimId), c)
      void qc.invalidateQueries({ queryKey: ['approvals', 'inbox'] })
    },
  })
}

export function useDelegations() {
  return useQuery({ queryKey: approvalKeys.delegations, queryFn: () => api.get<Delegation[]>('/api/delegations') })
}

export function useCreateDelegation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ input, idempotencyKey }: { input: DelegationInput; idempotencyKey: string }) =>
      api.post<Delegation>('/api/delegations', input, { idempotencyKey }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: approvalKeys.delegations }),
  })
}

export function useRevokeDelegation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete(`/api/delegations/${id}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: approvalKeys.delegations }),
  })
}

export function useApprovers(q: string) {
  return useQuery({
    queryKey: approvalKeys.approvers(q),
    queryFn: () => api.get<UserRef[]>('/api/users/approvers', { q }),
  })
}

export const LEVEL_TEXT: Record<ApprovalLevel, string> = { MANAGER: 'Manager', AUDITOR: 'Finance auditor', HEAD: 'Finance head' }

/** Plain-words message for approval/delegation errors (API error codes from the contract). */
export function approvalErrorText(e: unknown): string {
  if (!(e instanceof ApiError)) return 'Something went wrong. Please try again.'
  switch (e.code) {
    case 'NOT_ASSIGNED_APPROVER':
      return 'You are not the assigned approver of this claim (it may have been reassigned or delegated).'
    case 'SELF_APPROVAL_FORBIDDEN':
      return 'You cannot decide on your own claim.'
    case 'ILLEGAL_STATE_TRANSITION':
      return 'This claim was already decided or is no longer waiting for you. Refresh to see its current state.'
    case 'CONCURRENT_MODIFICATION':
      return 'Someone else changed this claim at the same time. Refresh and try again.'
    case 'DELEGATION_INVALID':
      return `Delegation not allowed: ${e.message}`
    case 'VALIDATION_FAILED':
      return `Please check the form: ${e.message}`
    case 'NOT_FOUND':
      return 'Not found. It may have been removed or you no longer have access.'
    default:
      return e.message || 'Something went wrong. Please try again.'
  }
}
