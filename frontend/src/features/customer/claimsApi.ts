import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { AuditEvent, Claim, ClaimInput, ClaimStatus, ClaimSummaryPage, Comment, Receipt } from '../../api/types'

export const claimKeys = {
  all: ['claims'] as const,
  list: (status: ClaimStatus | undefined, page: number, size: number) => ['claims', 'list', status ?? 'ALL', page, size] as const,
  detail: (id: number) => ['claims', 'detail', id] as const,
}

export function useMyClaims(status: ClaimStatus | undefined, page: number, size: number) {
  return useQuery({
    queryKey: claimKeys.list(status, page, size),
    queryFn: () => api.get<ClaimSummaryPage>('/api/claims', { status, page, size }),
    placeholderData: (prev) => prev,
  })
}

export function useClaim(id: number | undefined) {
  return useQuery({
    queryKey: claimKeys.detail(id ?? -1),
    queryFn: () => api.get<Claim>(`/api/claims/${id}`),
    enabled: id !== undefined && Number.isFinite(id),
  })
}

/** Create (no id) or replace (id) a draft. */
export function useSaveClaim() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: ClaimInput }) =>
      id ? api.put<Claim>(`/api/claims/${id}`, input) : api.post<Claim>('/api/claims', input),
    onSuccess: (c) => {
      qc.setQueryData(claimKeys.detail(c.id), c)
      void qc.invalidateQueries({ queryKey: ['claims', 'list'] })
    },
  })
}

export function useSubmitClaim() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, idempotencyKey }: { id: number; idempotencyKey: string }) =>
      api.post<Claim>(`/api/claims/${id}/submit`, undefined, { idempotencyKey }),
    onSuccess: (c) => {
      qc.setQueryData(claimKeys.detail(c.id), c)
      void qc.invalidateQueries({ queryKey: ['claims', 'list'] })
    },
  })
}

export function useClaimAudit(id: number) {
  return useQuery({
    queryKey: ['claims', 'audit', id],
    queryFn: () => api.get<AuditEvent[]>(`/api/claims/${id}/audit`),
  })
}

export function useClaimComments(id: number | undefined, enabled = true) {
  return useQuery({
    queryKey: ['claims', 'comments', id ?? -1],
    queryFn: () => api.get<Comment[]>(`/api/claims/${id}/comments`),
    enabled: enabled && id !== undefined && Number.isFinite(id),
  })
}

export function useAddComment(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (text: string) => api.post<Comment>(`/api/claims/${id}/comments`, { text }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['claims', 'comments', id] })
      void qc.invalidateQueries({ queryKey: ['claims', 'audit', id] })
    },
  })
}

export const RECEIPT_MAX_BYTES = 5 * 1024 * 1024
export const RECEIPT_TYPES = ['image/jpeg', 'image/png', 'application/pdf']

/** Client-side check before upload (server repeats it: 413 / 415). Returns an error message or null. */
export function checkReceiptFile(f: { type: string; size: number }): string | null {
  if (!RECEIPT_TYPES.includes(f.type)) return 'Only JPG, PNG or PDF files can be used as a receipt.'
  if (f.size > RECEIPT_MAX_BYTES) return 'The file is larger than 5 MB. Take a smaller photo or compress the PDF.'
  return null
}

export function uploadReceipt(file: File): Promise<Receipt> {
  const fd = new FormData()
  fd.append('file', file)
  return api.post<Receipt>('/api/receipts', fd)
}
