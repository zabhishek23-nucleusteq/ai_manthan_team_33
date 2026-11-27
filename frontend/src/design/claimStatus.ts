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

/**
 * Explicit text/background per tone and mode. antd 6 "filled" preset tags derive their background from the
 * status colour, which left warning and success tags below 4.5:1 (axe color-contrast, T-506). Every pair here is
 * checked >= 4.5:1 by claimStatus.test.ts.
 */
export const TAG_TONES: Record<'light' | 'dark', Record<string, { fg: string; bg: string }>> = {
  light: {
    default: { fg: '#262626', bg: '#f0f0f0' },
    processing: { fg: '#0958d9', bg: '#e6f4ff' },
    success: { fg: '#237804', bg: '#f6ffed' },
    warning: { fg: '#874d00', bg: '#fffbe6' },
    error: { fg: '#a8071a', bg: '#fff1f0' },
    purple: { fg: '#531dab', bg: '#f9f0ff' },
    cyan: { fg: '#006d75', bg: '#e6fffb' },
  },
  dark: {
    default: { fg: '#f0f0f0', bg: '#262626' },
    processing: { fg: '#69b1ff', bg: '#111a2c' },
    success: { fg: '#95de64', bg: '#162312' },
    warning: { fg: '#ffd666', bg: '#2b2111' },
    error: { fg: '#ff7875', bg: '#2a1215' },
    purple: { fg: '#b37feb', bg: '#1a1325' },
    cyan: { fg: '#5cdbd3', bg: '#112123' },
  },
}

/** WCAG 2 contrast ratio of two #rrggbb colours. */
export function contrastRatio(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => {
      const c = parseInt(hex.slice(i, i + 2), 16) / 255
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
    })
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m)
  return (x + 0.05) / (y + 0.05)
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
