# Requirements - ExpenseFlow (expense claims + policy-driven approvals)

Source: `docs/problem/problem-statement.md` + `squad doc team-standards`. Ids are stable: tasks, tests and PRs cite them. Money is INR, `numeric(12,2)`; "above X" means strictly greater than X.

## 1. Personas
| Id | Persona | Role | Goal |
|---|---|---|---|
| P1 | Employee | EMPLOYEE | Capture expenses on a phone, submit, see status and reasons |
| P2 | Line manager | MANAGER | Approve team claims fast with receipts side by side; delegate while on leave |
| P3 | Finance auditor | FINANCE_AUDITOR | Work a filtered review queue, bulk approve, sample-audit auto-approved claims |
| P4 | Finance head (also admin) | FINANCE_HEAD (+ SUPER_ADMIN) | Final approval of big claims, payout batches, policy rules |
| P5 | Super admin | SUPER_ADMIN | Users and roles, system overview, audit log, health |

## 2. Functional requirements
| Id | Requirement | Acceptance criteria |
|---|---|---|
| FR-1 Auth | Sign-up, login, JWT, role routing | Signup creates EMPLOYEE (409 `EMAIL_TAKEN`); login returns JWT with `roles`; no token -> 401; wrong role -> 403; each role lands on its dashboard (rbac.md §2) |
| FR-2 Draft claim | Employee creates a claim with 1..50 line items (category, amount, expense date, vendor, description, city + nights for HOTEL, receipt) and saves a draft | `POST /api/claims` 201 status DRAFT; `PUT` replaces lines while DRAFT/CLARIFICATION_REQUESTED, else 409 `ILLEGAL_STATE_TRANSITION`; amount > 0, date not in the future (400 `VALIDATION_FAILED`) |
| FR-3 Receipt upload | Upload PDF/JPG/PNG <= 5 MB, store SHA-256 | `POST /api/receipts` 201 `{id,fileName,contentType,size,sha256}`; other type 415 `UNSUPPORTED_FILE_TYPE`; > 5 MB 413 `RECEIPT_TOO_LARGE` |
| FR-4 Submit + policy engine | Submit runs every policy rule on every line and returns one outcome | `POST /api/claims/{id}/submit` with `Idempotency-Key` -> 200 claim with `policyResult {outcome: AUTO_APPROVED|ROUTED|AUTO_REJECTED, lineResults[{lineId, violations[{code,message,action}]}], reasons[]}`; same key replay returns same body; empty claim 422 `CLAIM_EMPTY` |
| FR-5 Policy as configuration | Rules live in table `policy_rules` (seeded by Flyway), not in code | `GET /api/policy-rules`; `PUT /api/policy-rules/{code}` (FINANCE_HEAD) changes limit/action/enabled, audited, next submit uses new value |
| FR-6 Approval chain | Routed claims go manager -> auditor (> 25,000) -> head (> 1,00,000) | Inbox shows only steps assigned to me; approve moves to next level or APPROVED; reject -> REJECTED with reason; only assigned approver may act (403 `NOT_ASSIGNED_APPROVER`) |
| FR-7 Comment / send back | Approvers comment or send back for clarification | `send-back` -> CLARIFICATION_REQUESTED with mandatory comment; employee edits and resubmits -> policy re-run, chain restarts at manager |
| FR-8 Delegation | Approver delegates to another approver for a date range while on leave | `POST /api/delegations {delegateId, from, to}`; while active, new and open steps of delegator show in delegate's inbox; action records `onBehalfOf` |
| FR-9 Audit queue | Auditor queue with filters + bulk approve | Filters: department, category, amount min/max, violation code, sample-only, age; paginated; `bulk-approve {claimIds[]}` -> 200 `{results[{claimId, ok, code}]}` (partial success) |
| FR-10 Sample audit | Random 10% of AUTO_APPROVED claims flagged for sample audit | `sampleAudit=PENDING` on ~10% (seed proves 9-11% of 3,000); auditor records OK or EXCEPTION with comment; EXCEPTION -> claim REJECTED (`SAMPLE_AUDIT_EXCEPTION`) |
| FR-11 Payout batch | Finance head batches approved claims, exports mock bank CSV, claims -> PAID | `POST /api/payout-batches` (Idempotency-Key) takes all eligible claims -> IN_PAYOUT (422 `NO_ELIGIBLE_CLAIMS` if none); `GET .../export.csv` in BR-17 format; `POST .../submit` sends file to mock bank -> batch PAID + claims PAID; bank failure -> batch SUBMIT_FAILED, retry allowed, no double payment |
| FR-12 Dashboard | Spend by category, department, month; policy violations; average approval time | `GET /api/dashboard/kpis?from&to` (NFR-DASH) scoped by role (rbac.md); UI cards + charts with 1D/7D/1M/Custom filter |
| FR-13 Audit trail | Append-only record of every action | Every create/update/submit/policy result/approve/reject/send-back/delegate/bulk/payout/rule change/admin change writes `audit_events`; DB trigger rejects UPDATE/DELETE; `GET /api/claims/{id}/audit` timeline |
| FR-14 Own claims only | Employee sees only own claims | Other user's claim id -> 404 `NOT_FOUND` (no enumeration) |
| FR-15 Super admin | Users, overview, audit log, health | rbac.md; create staff user, assign/revoke roles, enable/disable; cannot remove own SUPER_ADMIN (409 `CANNOT_REMOVE_OWN_ADMIN`) |
| FR-16 Receipt viewer | Approver sees lines and the receipt side by side | Approval screen: lines table left, receipt (img/pdf) right; >= 1024px split, < 768px tabs |

## 3. Business rules (every number in the statement)
Policy rules are rows in `policy_rules(code, category, limit_amount, unit, params jsonb, action ROUTE|REJECT, enabled)`.

| Id | Rule | Exact values / boundary | Violation code -> action |
|---|---|---|---|
| BR-1 | Meals per day | Sum of MEAL lines for the same employee and expense date across all non-rejected claims <= **1,500**. 1,500.00 ok, 1,500.01 violates | `MEAL_DAILY_LIMIT` -> ROUTE |
| BR-2 | Hotel tier-1 city | amount / nights <= **6,000** per night when city in tier-1 list (Mumbai, Delhi, Bengaluru, Chennai, Kolkata, Hyderabad, Pune, Ahmedabad - config `params.tier1Cities`) | `HOTEL_NIGHTLY_LIMIT` -> ROUTE |
| BR-3 | Hotel other cities | amount / nights <= **4,000** per night | `HOTEL_NIGHTLY_LIMIT` -> ROUTE |
| BR-4 | Local cab | <= **2,000** per line (one line = one trip) | `CAB_TRIP_LIMIT` -> ROUTE |
| BR-5 | Receipt | Mandatory when amount > **500** (500.00 without receipt ok, 500.01 without receipt violates) | `RECEIPT_REQUIRED` -> REJECT |
| BR-6 | Duplicate line | Same vendor (trimmed, case-insensitive) + amount + expense date as an earlier line in any non-rejected claim | `DUPLICATE_LINE` -> REJECT |
| BR-7 | Duplicate receipt | Same receipt SHA-256 as a receipt on an earlier line in any non-rejected claim | `DUPLICATE_RECEIPT` -> REJECT |
| BR-8 | Weekend spend | Expense date is Saturday or Sunday -> flagged for manager attention | `WEEKEND_SPEND` -> ROUTE (shown as a tag in the inbox) |
| BR-9 | Outcome | Any REJECT violation -> AUTO_REJECTED (all reasons listed). Else any ROUTE violation -> ROUTED. Else total <= **5,000** (A-1, config `AUTO_APPROVE_MAX`) -> AUTO_APPROVED. Else ROUTED | - |
| BR-10 | Level 1 | Every ROUTED claim goes to the claimant's line manager | - |
| BR-11 | Level 2 | Total > **25,000** also needs FINANCE_AUDITOR (25,000.00 manager only; 25,000.01 manager + auditor) | - |
| BR-12 | Level 3 | Total > **1,00,000** also needs FINANCE_HEAD (1,00,000.00 stops at auditor) | - |
| BR-13 | Sample audit | **10%** of AUTO_APPROVED claims (random, `java.security.SecureRandom`, rate in config `SAMPLE_AUDIT_RATE=0.10`) | - |
| BR-14 | Delegation | Delegate must hold an approver role at the same level or higher, must not be the claimant; one hop (a delegate's own delegation is not followed); `to >= from`; max 30 days (A-4) | `DELEGATION_INVALID` 422 |
| BR-15 | No self-approval | Approver never equals claimant. If the resolved approver is the claimant or missing, the step escalates to the next level (manager -> any FINANCE_AUDITOR not the claimant -> FINANCE_HEAD) | `SELF_APPROVAL_FORBIDDEN` 403 |
| BR-16 | Send back | Resubmit re-runs the policy engine on all lines and restarts at level 1 | - |
| BR-17 | Bank file | CSV, UTF-8, `,` separator. Header `RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION`; one `D` row per claim (AMOUNT 2 decimals, CURRENCY `INR`, NARRATION = claim code); last row `T,<batchId>,<valueDate>,<count>,<total>` | - |
| BR-18 | Payable | Only APPROVED claims whose sample audit is not PENDING/EXCEPTION and not in another batch | - |
| BR-19 | Approval time | Average hours from `submitted_at` to final decision (APPROVED/REJECTED by a person) for claims decided in the range | - |
| BR-20 | Receipt file | PDF/JPG/PNG, max **5 MB** (A-2) | - |
| BR-21 | Seed volume | **150** employees, **8** departments, reporting hierarchy, **3,000** historical claims incl. planted duplicates and limit breaches | - |

## 4. Claim state machine
`DRAFT -> (submit) -> AUTO_REJECTED | APPROVED (auto) | PENDING_MANAGER`
`PENDING_MANAGER -> PENDING_AUDITOR (total > 25,000) | APPROVED | REJECTED | CLARIFICATION_REQUESTED`
`PENDING_AUDITOR -> PENDING_HEAD (total > 1,00,000) | APPROVED | REJECTED | CLARIFICATION_REQUESTED`
`PENDING_HEAD -> APPROVED | REJECTED | CLARIFICATION_REQUESTED`
`CLARIFICATION_REQUESTED -> (resubmit) -> same as submit`
`APPROVED -> IN_PAYOUT -> PAID`; `IN_PAYOUT -> APPROVED` only if the batch is cancelled after SUBMIT_FAILED.
`APPROVED (sample audit EXCEPTION) -> REJECTED`. Terminal: AUTO_REJECTED, REJECTED, PAID.

## 5. Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-SEC | JWT HS256 (8 h), BCrypt, `@PreAuthorize` per rbac.md, row-level checks, no secrets in git, input validation, file type sniffing on upload |
| NFR-IDEM | `Idempotency-Key` header on every state-changing POST (submit, approve, reject, send-back, bulk-approve, payout create/submit): same key + same body -> stored response; same key + other body -> 422 `IDEMPOTENCY_KEY_REUSED` |
| NFR-CONC | Claims and payout batches have `@Version`; concurrent action -> 409 `CONCURRENT_MODIFICATION` |
| NFR-AUDIT | Append-only `audit_events` (actor, onBehalfOf, action, entityType, entityId, before/after jsonb, at) |
| NFR-ERR | Errors `{code, message, details}` with stable codes (contract) |
| NFR-DASH | Every dashboard/KPI endpoint accepts `from`/`to` (ISO-8601 date-time, from inclusive, to exclusive), 400 `INVALID_RANGE` if `from >= to` or > 366 days, returns the range used; UI offers **1D / 7D / 1M / Custom**, default **7D**, range in URL, one range for all widgets; buckets 1D -> hour, 7D/1M -> day, longer -> week |
| NFR-UX | Ant Design everywhere; loading/empty/error/success on every screen; labels + keyboard; 360px (claim capture mobile-first); dense tables with bulk actions |
| NFR-A11Y | axe scan with no serious/critical issues on key screens |
| NFR-OBS | Metrics: `expenseflow_approval_queue_size{level}`, `expenseflow_oldest_pending_approval_seconds{level}`, `expenseflow_page_load_seconds{route}` (histogram from the UI), `http_server_requests_seconds`, `expenseflow_bank_submit_total{result}`; Grafana dashboard; alert on oldest pending approval > 48 h and bank submit failures |
| NFR-DEPLOY | `./start.ps1` / `./start.sh` -> full seeded stack; CI runs tests + dependency check |

## 6. Seed spec (L2, profile `seed`, Datafaker fixed seed 42, idempotent)
- 8 departments: Engineering, Sales, Marketing, Finance, HR, Operations, Support, Legal.
- 150 users total incl. the 6 demo logins (rbac.md §4); hierarchy: 1 finance head, 3 auditors, 1 manager per department + 1-2 team leads (MANAGER) reporting to them, the rest employees; every non-head user has a manager. Each user: employee code `EMP0001..`, synthetic bank account + IFSC.
- 3,000 claims, 1-6 lines each, dates relative to now: spread over last 60 days, upward trend, fewer on weekends; 3-5% in the last 24 h (some in last 2 h).
- State mix: PAID 40%, APPROVED 12%, IN_PAYOUT 3%, PENDING_MANAGER 10%, PENDING_AUDITOR 5%, PENDING_HEAD 2%, CLARIFICATION_REQUESTED 3%, REJECTED 5%, AUTO_REJECTED 8%, DRAFT 12%; 10% of auto-approved have sample audit flag.
- Planted: >= 60 duplicate lines (vendor+amount+date), >= 30 duplicate receipt hashes, >= 150 limit breaches, boundary records exactly at each limit and 0.01 above (1,500 / 1,500.01 meals, 6,000 / 6,000.01 tier-1 hotel, 4,000 / 4,000.01 other hotel, 2,000 / 2,000.01 cab, 500 / 500.01 receipt, 25,000 / 25,000.01, 1,00,000 / 1,00,000.01 totals), weekend lines, some pending approvals older than 48 h (alert demo).
- Named demo claims `DEMO-1001`..`DEMO-1010` for `employee@expenseflow.test`: 1001 draft, 1002 auto-approved, 1003 auto-rejected (missing receipt), 1004 pending manager with weekend flag, 1005 pending auditor (30,000), 1006 pending head (1,20,000), 1007 clarification requested, 1008 approved (payable), 1009 paid, 1010 duplicate receipt rejected.
- Summary in `docs/testing/seed-summary.md` (counts per state, per rule, per department).

## 7. Assumptions
- A-1 Clean claims are auto-approved only up to 5,000 INR total; larger clean claims are routed (otherwise a 90,000 claim could skip the chain). Configurable.
- A-2 Receipts max 5 MB, PDF/JPG/PNG, stored in Postgres (`bytea`) for simplicity.
- A-3 Duplicates and missing receipts auto-reject (with reason) instead of only flagging; action is configurable per rule (`action`), so finance can switch them to ROUTE.
- A-4 Delegation max 30 days.
- A-5 One claim -> one bank payment row; currency INR only.
- A-6 Mock bank = `services/mock-bank`, accepts the CSV, can be broken with `/admin/faults`.
- A-7 Manager dashboard scope = direct reports.

## 8. Out of scope
Multi-currency, OCR of receipts, mileage/per-diem calculators, real bank/SSO integration, email notifications (shown as in-app inbox only), mobile native app.

## 9. Traceability (FR -> tasks -> sprint)
| FR / BR | Backend | Frontend | Test / Ops | Sprint |
|---|---|---|---|---|
| FR-1 | T-004 | T-005, T-006 | T-029, T-035 | S1 |
| FR-2, FR-3, FR-14 | T-012, T-016 | T-022, T-023 | T-029, T-035 | S1-S2 |
| FR-4, FR-5, BR-1..BR-9 | T-015 | T-022 | T-015 unit tests | S2 |
| FR-6, FR-7, FR-8, FR-16, BR-10..BR-16 | T-019 | T-025 | T-019 tests, T-035 | S2 |
| FR-9, FR-10, BR-13 | T-020 | T-026 | T-035 | S2 |
| FR-11, BR-17, BR-18 | T-017, T-007 | T-031 | T-035, T-036 | S3 |
| FR-12, NFR-DASH, BR-19 | T-027 | T-033 | T-035 | S3 |
| FR-13, NFR-AUDIT, NFR-IDEM | T-018 | T-023 | T-018 tests | S1 |
| FR-15 | T-024 | T-034 | T-029 | S3 |
| BR-21 seed | T-013 | - | T-901/T-902 | S2 |
| NFR-OBS | T-027 | T-033 (page-load) | T-030, T-036 | S2-S3 |
