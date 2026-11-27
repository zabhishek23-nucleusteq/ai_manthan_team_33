# Written test cases (UI end to end)

Specs live in `tests/e2e/ui/`. Run: start the stack (`./start.ps1`), then in `tests/e2e`: `npm ci; npm run test:ui`
(`PW_CHANNEL=msedge` uses the installed Edge when the Playwright browser download is blocked).
Desktop (1280 wide) and 360 px projects both run the auth and a11y specs.

Status: **Automated** = spec passes against the compose stack. **Blocked** = written, spec is `test.fixme` until the named task merges (visible as skipped, never counted as passed).

| ID | Persona | Preconditions | Steps | Expected | FR / BR | Spec | Status |
|---|---|---|---|---|---|---|---|
| TC-01 | each of 5 demo users | seeded demo users, password `Demo@123` | open `/login`, enter email + password, Log in | lands on own dashboard (employee `/me/dashboard`, manager `/manager/dashboard`, auditor `/finance/dashboard`, head and admin `/admin`), greeting shows the user's name | FR-1, rbac.md s2 | `auth.spec.ts` TC-01 (x5) | Automated |
| TC-02 | any | - | log in with a wrong password | error "Email or password is incorrect", stays on `/login`, no hint whether the email exists | FR-1 | `auth.spec.ts` TC-02 | Automated |
| TC-03 | new employee | unused email | `/signup`, fill name, email, password twice, Create account, then log in | redirected to `/login?created=1` with success banner; login lands on `/me/dashboard` | FR-1 (sign-up -> EMPLOYEE only) | `auth.spec.ts` TC-03 | Automated |
| TC-04 | new user | - | sign-up with different password and confirmation (boundary: mismatch) | stays on `/signup`, mismatch message, nothing created | FR-1 | `auth.spec.ts` TC-04 | Automated |
| TC-05 | employee | logged out | open `/claims` directly, log in | redirected to `/login?next=%2Fclaims`, after login returns to `/claims` | FR-1, rbac.md | `auth.spec.ts` TC-05 | Automated |
| TC-06 | employee | logged in | create a claim with one small meal (<= 1,500 INR/day, <= 500 INR so no receipt), submit | outcome page shows auto-approved | policy: meals limit, receipt > 500 | `flows.spec.ts` TC-06 | Automated |
| TC-07 | employee | logged in | submit a meal of 800 INR with no receipt (boundary: 500 INR) | auto-rejected with reason "receipt required above 500 INR" | policy: receipts | `flows.spec.ts` TC-07 | Automated |
| TC-08 | employee, manager, auditor | claim of 30,000 INR (boundary: 25,000) | employee submits, manager approves, auditor approves | chain manager -> auditor; final status Approved; audit trail shows both steps | approval chain, BR thresholds | `flows.spec.ts` TC-08 | Blocked (T-019, T-025) |
| TC-09 | employee, manager | submitted claim | manager sends back with comment; employee edits and resubmits | status Clarification requested then Submitted; comment visible; trail records both | approval chain: send back | `flows.spec.ts` TC-09 | Blocked (T-019, T-025, T-023) |
| TC-10 | manager, manager2 | manager has a pending approval | manager delegates to Meera while on leave; Meera approves | delegate sees the step in her inbox and can approve; trail names delegator | approval chain: delegation | `flows.spec.ts` TC-10 | Blocked (T-019, T-025) |
| TC-11 | employee | logged in | open `/admin` directly | 403 page "403 - No access" with button back to own dashboard; URL unchanged | rbac.md s2 | `auth.spec.ts` TC-11 | Automated |
| TC-12 | auditor | claims in queue | filter queue, select several, bulk approve | selected claims approved, others untouched, sample-audit flag visible | auditor queue, 10% sample | `flows.spec.ts` TC-12 | Blocked (T-020, T-026) |
| TC-13 | finance head | approved claims | create payout batch, download CSV | batch created, claims move to Paid, CSV header matches BR-17 bank format | payout export | `flows.spec.ts` TC-13 | **Unblocked** (T-017, T-031 merged). Checked through the API on main 2026-11-27: `GET /api/payout-batches` shows batch PB-0011 PAID with 339 claims, `/eligible` answers; spec still `test.fixme`, automation pending |
| TC-14 | any dashboard user | seeded history | switch preset 1D / 7D / 1M | numbers change with the range | dashboard date filter | `flows.spec.ts` TC-14 | Automated (auditor dashboard: claims count 1 Day < 7 Days < 1 Month) |
| TC-A11Y | any | - | axe scan (WCAG 2 A/AA) on login, sign-up, landing, 403, My claims | no serious or critical violations | NFR-A11Y | `a11y.spec.ts` | Automated - clean after T-506 fix (desktop and 360 px) |
| TC-KEY | any | - | complete login using only the keyboard | login succeeds | NFR-A11Y keyboard | `a11y.spec.ts` keyboard | Automated |
| TC-RBAC | all roles | seeded users | API matrix from rbac.md | 401 / allowed / 403 per endpoint | rbac.md s3 | `tests/e2e/api/rbac.spec.ts`, results in [rbac-matrix.md](rbac-matrix.md) | Automated (63 passed, 60 skipped until endpoints land) |

Counts: 14 business cases written (TC-01..TC-14) plus the a11y, keyboard and RBAC cases. Automated: 9 business cases (TC-01..TC-07, TC-11, TC-14) plus TC-KEY = **10 distinct passing UI cases** (14 UI tests; TC-01 runs once per role), and TC-A11Y (clean since the T-506 fix). Still `test.fixme`: TC-08, TC-09, TC-10 (approver inbox, T-025), TC-12 (auditor queue UI merged in T-026, automation pending), TC-13 (payout screens, T-031).
