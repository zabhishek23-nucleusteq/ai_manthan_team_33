# ADR-001: How approval chains are modelled

Status: Accepted (kickoff, S1) · Owner: L1

## Context
Routed claims go manager -> finance auditor (total > 25,000 INR) -> finance head (total > 1,00,000 INR). Approvers can comment, send back for clarification, or delegate while on leave. Thresholds may change; the chain must be auditable and must not allow bypass (self-approval, delegation to the claimant, editing after approval). See BR-10..BR-16 and failure-modes.md §2.

## Options
### A. Hard-coded state machine with levels in the claim status
`status = PENDING_MANAGER | PENDING_AUDITOR | PENDING_HEAD`, next level computed in code from the total.
- + Simplest, very readable, easy to test.
- - Who acted and on whose behalf is lost unless stored elsewhere; thresholds in code.

### B. Materialised approval steps (one row per level) created at submit
At submit we compute the required levels from the total and create `approval_steps` rows (level, assignee, decision). Claim status mirrors the current open step.
- + Full history per level (who, on behalf of whom, comment, time) - natural audit and "average approval time" source.
- + Inbox = `SELECT open steps WHERE assignee = me OR assignee has active delegation to me`.
- + Delegation and escalation are just a different `assignee`/`on_behalf_of` on the step.
- - Steps must be rebuilt on resubmit; status and steps must stay consistent (same transaction).

### C. Generic workflow engine (Flowable/Camunda BPMN)
- + Configurable chains, timers, escalations out of the box.
- - Heavy for a 3-hour build, new runtime and tables, hard to test quickly, team has no time to learn it.

## Decision
**Option B**, with thresholds read from configuration (`policy_rules` rows `CHAIN_AUDITOR_ABOVE` = 25000 and `CHAIN_HEAD_ABOVE` = 100000) and the claim status used as the coarse state (Option A's readability). Level steps are created lazily: only the current level is open; the next level is created when the current one approves. Assignee resolution:
1. MANAGER level: claimant's `manager_id`; AUDITOR: auditor with fewest open steps (not the claimant); HEAD: a FINANCE_HEAD not the claimant.
2. If the resolved user has an active delegation today -> assignee = delegate, `on_behalf_of` = delegator (one hop).
3. If the assignee is the claimant or missing -> escalate to the next level (BR-15).
Delegations created later also re-assign open steps of the delegator (FR-8).

## Consequences
- Every decision is a row -> audit timeline and BR-19 approval time are simple queries.
- Resubmit after send-back closes all open steps (`decision = SUPERSEDED`) and restarts at MANAGER.
- Residual risk: splitting a claim into several smaller claims to stay under 25,000 is not blocked by the chain itself (thresholds are per claim, as the statement defines). Mitigation: daily meal sum across claims, duplicate rules, and auditor queue filters; a future rule "sum per employee per 7 days" can be added as a `policy_rules` row.

## Amendment (T-190 integration, demo day)
The AUDITOR level is a **finance team pool**: the step is still assigned to the least-loaded auditor, but any FINANCE_AUDITOR (never the claimant) may decide it. The decision records `actedBy` = the acting auditor and `onBehalfOf` = the assigned auditor (step and audit event), exactly like a delegation. Reason: the auditor queue (FR-9) is a shared team queue; with strict assignment a claim was invisible to every auditor but one. MANAGER and HEAD levels stay strictly assigned.
