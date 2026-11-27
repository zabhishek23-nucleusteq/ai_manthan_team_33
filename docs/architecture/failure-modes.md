# Failure modes (before code) - ExpenseFlow

Each row: what can go wrong -> guard -> the test that proves it (owner task). Includes the skeptical-finance-auditor review "how could someone bypass approvals" (problem statement, Architecture phase).

## 1. Per transition
| Transition | Failure | Guard | Proving test (task) |
|---|---|---|---|
| DRAFT -> submit | Double click / retry after timeout | `Idempotency-Key` table (key, user, request hash, response) unique; replay returns stored response | `submit_sameKeyTwice_returnsSameResultAndOneAuditEvent` (T-018, T-016) |
| DRAFT -> submit | Two tabs submit the same claim with different keys | `@Version` on claim + state check `status in (DRAFT, CLARIFICATION_REQUESTED)` | `submit_concurrent_onlyOneSucceeds_other409` (T-016) |
| DRAFT -> submit | Empty claim / zero or negative amount / future date | Bean Validation + `CLAIM_EMPTY` | `submit_emptyClaim_422` (T-016) |
| submit -> policy | Meal split across two claims same day to stay under 1,500 | BR-1 sums across all non-rejected claims of the employee for the date | `br1_mealSplitAcrossClaims_isViolation` (T-015) |
| submit -> policy | Hotel 3 nights entered as 1 line with nights=1 | per-night = amount / nights; nights required >= 1 for HOTEL, max 30 | `br2_hotelPerNightComputed` (T-015) |
| submit -> policy | Same receipt re-uploaded (renamed file) | SHA-256 of content, not name | `br7_sameBytesDifferentName_duplicate` (T-015) |
| submit -> policy | Vendor "Uber " vs "uber" | trim + lowercase before compare | `br6_vendorCaseInsensitive` (T-015) |
| submit -> policy | Boundaries | `>` vs `>=` exactly as BR table; BigDecimal compareTo | `br1_exactly1500_ok`, `br1_1500_01_violation`, same for BR-2..BR-5, BR-11, BR-12 (T-015, T-019) |
| submit -> policy | Rule edited mid-flight | Engine reads rules per evaluation; result stored with rule snapshot in `policy_result` jsonb | `ruleChange_appliesToNextSubmitOnly` (T-015) |
| PENDING_x -> approve | Approve twice in parallel / double click | Idempotency-Key + `@Version` -> 409 `CONCURRENT_MODIFICATION` | `approve_concurrent_onlyOneSucceeds` (T-019) |
| PENDING_x -> approve | Wrong person approves (other manager, by URL) | service checks `step.assignee == me` (or active delegation) -> 403 `NOT_ASSIGNED_APPROVER` | `approve_notAssigned_403` (T-019) |
| PENDING_x -> approve | Approve a claim in another state (already APPROVED / REJECTED) | state check -> 409 `ILLEGAL_STATE_TRANSITION` | `approve_rejectedClaim_409` (T-019) |
| any -> send-back | Employee edits amounts after manager approved | Edit only in DRAFT/CLARIFICATION; resubmit re-runs policy and restarts at manager | `resubmit_restartsChainAtManager` (T-019) |
| delegation | Delegate is the claimant; delegation chain loops | BR-14: delegate != claimant else escalate; one hop only | `delegation_toClaimant_escalates`, `delegation_noChain` (T-019) |
| bulk approve | One claim in the batch changed state meanwhile | per-item transaction, result per id, no all-or-nothing | `bulkApprove_partialSuccess` (T-020) |
| APPROVED -> IN_PAYOUT | Two finance heads create batches at once -> claim in two batches | unique (claim_id) on `payout_items` + `SELECT ... FOR UPDATE SKIP LOCKED` | `createBatch_concurrent_noClaimTwice` (T-017) |
| IN_PAYOUT -> PAID | Bank timeout, retry pays twice | batch has `bank_reference`; mock bank idempotent on `batchId`; submit only from CREATED/SUBMIT_FAILED | `submit_bankTimeout_thenRetry_paidOnce` (T-017) |
| IN_PAYOUT -> PAID | Bank 500 | batch SUBMIT_FAILED, claims stay IN_PAYOUT, metric `expenseflow_bank_submit_total{result="error"}`, alert | `submit_bank500_batchFailed` (T-017), drill (T-036) |
| sample audit | Auto-approved claim with open sample audit gets paid | BR-18 payable query excludes PENDING/EXCEPTION | `payable_excludesPendingSampleAudit` (T-017) |
| audit trail | Someone edits or deletes history | DB trigger raising on UPDATE/DELETE of `audit_events`; no repository delete method | `auditEvents_updateRejectedByDb` (T-018) |
| admin | Admin removes own SUPER_ADMIN, locks everyone out | 409 `CANNOT_REMOVE_OWN_ADMIN` | `removeOwnAdmin_409` (T-024) |
| dashboard | `from >= to`, 5-year range | 400 `INVALID_RANGE` | `kpis_invalidRange_400`, `kpis_24h_7d_30d_boundaries` (T-027) |

## 2. Skeptical finance auditor: ways to bypass approvals, and our guard
| # | Bypass attempt | Guard |
|---|---|---|
| 1 | Split one 30,000 claim into two 15,000 claims to avoid the auditor | Thresholds are per claim (statement); mitigation: BR-1 daily meal sum across claims, duplicate detection, and dashboard "claims per employee per day" in auditor queue filter (age/amount). Documented as residual risk in ADR-001 |
| 2 | Clean but large claim auto-approved | A-1: auto-approve only <= 5,000 |
| 3 | Manager approves own claim | BR-15 no self-approval, escalate |
| 4 | Manager delegates to their own report who then approves the manager's claim | delegate must hold same-or-higher approver role and is never the claimant |
| 5 | Employee edits a claim after approval | edits only in DRAFT/CLARIFICATION; resubmit restarts chain |
| 6 | Re-use one receipt across claims or colleagues | SHA-256 duplicate across all claims |
| 7 | Change role via API or JWT tampering | HS256 signature verified; roles loaded from token signed by server; signup never assigns staff roles |
| 8 | Read colleague's claims by guessing ids | owner check -> 404 |
| 9 | Finance head lowers a policy limit, submits, restores | rule changes audited (before/after) and visible in admin audit log; claim stores rule snapshot |
| 10 | Pay a claim twice | unique claim in payout items, batch state machine, bank idempotency on batchId |
| 11 | Delete audit evidence | append-only trigger, no delete API |
