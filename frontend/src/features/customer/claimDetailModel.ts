import type { ApprovalLevel, ApprovalStep, AuditEvent, Claim, Comment } from '../../api/types'

export const LEVEL_LABEL: Record<ApprovalLevel, string> = {
  MANAGER: 'Manager',
  AUDITOR: 'Finance auditor',
  HEAD: 'Finance head',
}

export const DECISION_LABEL: Record<ApprovalStep['decision'], string> = {
  PENDING: 'Waiting',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SENT_BACK: 'Sent back for clarification',
  SUPERSEDED: 'Superseded',
}

const ACTION_TEXT: Record<string, string> = {
  CLAIM_CREATED: 'Draft created',
  CLAIM_UPDATED: 'Draft edited',
  CLAIM_SUBMITTED: 'Submitted',
  CLAIM_RESUBMITTED: 'Resubmitted',
  POLICY_AUTO_APPROVED: 'Policy check: auto-approved',
  POLICY_ROUTED: 'Policy check: sent for approval',
  POLICY_AUTO_REJECTED: 'Policy check: rejected',
  STEP_APPROVED: 'Approved',
  STEP_REJECTED: 'Rejected',
  CLAIM_APPROVED: 'Claim approved',
  CLAIM_REJECTED: 'Claim rejected',
  CLAIM_SENT_BACK: 'Sent back for clarification',
  STEP_SUPERSEDED: 'Step superseded',
  COMMENT_ADDED: 'Comment added',
  SAMPLE_AUDIT_OK: 'Sample audit: OK',
  SAMPLE_AUDIT_EXCEPTION: 'Sample audit: exception',
  ADDED_TO_PAYOUT_BATCH: 'Added to a payout batch',
  CLAIM_PAID: 'Paid',
}

/** "CLAIM_SENT_BACK" -> "Sent back for clarification"; unknown actions are humanised, never shown raw. */
export function auditActionText(action: string): string {
  if (ACTION_TEXT[action]) return ACTION_TEXT[action]
  const s = action.toLowerCase().replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** Who did it, including delegation ("Meera Backup on behalf of Rahul Manager"). */
export function auditActor(e: Pick<AuditEvent, 'actor' | 'onBehalfOf' | 'action'>): string {
  if (!e.actor) return e.action.startsWith('POLICY_') ? 'Policy engine' : 'System'
  return e.onBehalfOf ? `${e.actor.name} on behalf of ${e.onBehalfOf.name}` : e.actor.name
}

export function auditDetail(e: AuditEvent): string | null {
  const after = e.after ?? {}
  if (typeof after.comment === 'string') return `"${after.comment}"`
  if (typeof after.text === 'string') return `"${after.text}"`
  if (typeof after.reason === 'string') return after.reason
  return null
}

/** The approver's question for a claim waiting on the employee (latest SENT_BACK step, else latest non-owner comment). */
export function clarificationQuestion(
  claim: Pick<Claim, 'status' | 'steps' | 'employee'>,
  comments: Comment[] = [],
): { text: string; by?: string; at?: string } | null {
  if (claim.status !== 'CLARIFICATION_REQUESTED') return null
  const step = [...(claim.steps ?? [])].reverse().find((s) => s.decision === 'SENT_BACK')
  if (step?.comment) return { text: step.comment, by: (step.actedBy ?? step.assignee)?.name, at: step.decidedAt }
  const fromApprover = [...comments].reverse().find((c) => c.author.email !== claim.employee?.email)
  if (fromApprover) return { text: fromApprover.text, by: fromApprover.author.name, at: fromApprover.createdAt }
  return { text: 'Your approver asked for more information. Check the comments, edit the claim and resubmit.' }
}
