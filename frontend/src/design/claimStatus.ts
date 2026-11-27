/**
 * Claim status -> Tag colour + plain-words label.
 * Keys = ClaimStatus enum in contracts/openapi.yaml (state machine: CLAUDE.md §6).
 * Unknown statuses fall back to a neutral tag.
 */
export const CLAIM_STATUS: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: 'Draft' },
  AUTO_REJECTED: { color: 'error', label: 'Auto-rejected' },
  PENDING_MANAGER: { color: 'processing', label: 'Pending manager' },
  PENDING_AUDITOR: { color: 'processing', label: 'Pending auditor' },
  PENDING_HEAD: { color: 'processing', label: 'Pending finance head' },
  CLARIFICATION_REQUESTED: { color: 'warning', label: 'Clarification requested' },
  APPROVED: { color: 'success', label: 'Approved' },
  REJECTED: { color: 'error', label: 'Rejected' },
  IN_PAYOUT: { color: 'purple', label: 'In payout' },
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
