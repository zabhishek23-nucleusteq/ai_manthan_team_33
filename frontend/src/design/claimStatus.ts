/**
 * Claim status -> Tag colour + plain-words label.
 * PROVISIONAL until the claim state machine (docs/architecture) and contracts/openapi.yaml land;
 * align the keys with the contract enum then. Unknown statuses fall back to a neutral tag.
 */
export const CLAIM_STATUS: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: 'Draft' },
  SUBMITTED: { color: 'processing', label: 'Submitted' },
  PENDING_APPROVAL: { color: 'processing', label: 'Pending approval' },
  CLARIFICATION_REQUESTED: { color: 'warning', label: 'Clarification requested' },
  APPROVED: { color: 'success', label: 'Approved' },
  REJECTED: { color: 'error', label: 'Rejected' },
  PAID: { color: 'cyan', label: 'Paid' },
}

export function claimStatusMeta(status: string): { color: string; label: string } {
  return (
    CLAIM_STATUS[status] ?? {
      color: 'default',
      label: status
        .toLowerCase()
        .split('_')
        .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
        .join(' '),
    }
  )
}
