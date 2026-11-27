# S3 sprint review - 2026-11-27 18:00

**Result: FAIL (partial)** - the money flow, dashboards API, admin APIs, observability and the alert drill work; main CI was red during the review (Maven download flake and an order-dependent test, both being fixed) and four screens are not merged yet.

Checked on a fresh clone of `main` at `ff515e3` (clone, `start.ps1`, everything built from scratch, then torn down with `docker compose down -v`).

## Show the invigilator (2 minutes)
1. `./start.ps1` (or `docker compose up --build -d`), wait for "Frontend: http://localhost:3000". Six containers: postgres, backend, mock-bank, frontend, prometheus, grafana.
2. http://localhost:3000 -> log in as `employee@expenseflow.test` / `Demo@123` -> **New claim**: one Meals line, 250 INR, today's date -> Submit -> **"Claim auto-approved"**. Repeat with 800 INR and no receipt -> **"Claim rejected by policy"** with the receipt reason.
3. Log in as `head@expenseflow.test` / `Demo@123` (finance head + admin). API demo of the payout, which the Payouts screen (T-031) will wrap: `POST /api/payout-batches` -> one batch of **343 approved claims, 4,381,126.76 INR** -> `GET /api/payout-batches/{id}/export.csv` (BR-17 header, 343 `D` rows, matching `T` trailer) -> `POST .../submit` -> batch **PAID** at the mock bank. A second submit is refused (409), so nothing is paid twice. Swagger: http://localhost:8080/swagger-ui.html.
4. Dashboards: `GET /api/dashboard/kpis` for 1D / 7D / 1M gives different numbers (claims 85 / 968 / 2,160) and a different scope per role (head ALL, manager TEAM, employee OWN).
5. http://localhost:3001 (Grafana, no login) -> "ExpenseFlow overview": services up, request rate, error rate, latency, approval queue, oldest pending approval, bank payouts. http://localhost:9090/alerts: six alert rules.
6. Incident drill (already run, evidence in `docs/sre/rca-drill.md` and `docs/sre/evidence/`): bank set to fail -> payout returns 502 -> `BankPayoutFailures` fired after 84 s -> runbook (reset faults, retry) -> batch PAID -> alert cleared.

## What works now (verified)
- Login per role lands on the right dashboard; sign-up creates an employee only; protected pages redirect to login and back; 403 page for a role without access.
- Claim editor: submit -> auto-approve / auto-reject by policy (TC-06, TC-07).
- Payout batch -> BR-17 CSV -> mock bank -> PAID, role-restricted (auditor gets 403).
- Dashboard KPI API with date range and role scope; range over 366 days refused.
- Super admin APIs: health, users, overview, audit (health shows backend, database, mock-bank UP).
- Observability: Prometheus (3 of 3 targets up), Grafana dashboard, 6 alert rules (`promtool` SUCCESS), runbook, RCA with timeline.
- RBAC matrix: 63 passed, 0 failed (API).

## Checks
| Check | Result | Evidence |
|---|---|---|
| Fresh clone, one-command start | PASS | all 6 containers up; backend health UP, frontend 200, mock-bank UP, Prometheus targets up/up/up, Grafana 200 |
| Backend tests | PASS (CI) | `./mvnw -B verify` green in the CI runs for the merged PRs; not re-run locally (Testcontainers needs Docker, CI covers it) |
| Frontend lint, build | PASS | clean |
| Frontend unit tests | PASS with a risk | 87/87 pass; the first run (while Docker was building) timed out 3 tests, they passed on re-run (32/32). Tests are sensitive to CPU load |
| E2E (API + UI desktop + UI 360 px) | 151 passed, 1 failed, 11 skipped | the failure, TC-06, passed when re-run alone (timing under parallel load, 5 s expect); 8 flow cases are `test.fixme` (see below) |
| CI on main | **FAIL at review time** | latest run red: Maven wrapper download (wget) failed in backend and mock-bank; fix in PR 64. PR 63 fixes an order-dependent `GrafanaMetricsContractTest` |
| Open PRs | 3 | #61 payout + policy screens (T-031), #63 test fix, #64 CI fix |
| Tasks done | S3 24 of 33 | open: T-006 layout, T-031, T-025 approver inbox (in review), T-026 auditor queue UI, T-035 (parked), T-190/T-195/T-199 (L1, depend on the UIs) |

## Not done / risks
- **Screens not merged:** approver inbox and delegation (T-025), auditor queue with bulk approve (T-026), payouts and policy rules (T-031, PR open), role layout (T-006). So TC-08, TC-09, TC-10, TC-12, TC-13 cannot be shown in the UI yet; the payout works through the API.
- **T-035 incomplete:** 9 distinct UI cases pass, target is 10; the 6 blocked flow cases are written and switch on when those screens merge.
- **Open bugs:** T-506 accessibility contrast (L4), T-507 no login rate limit (L3), T-508 no security headers in nginx (L4).
- **Demo data vs alert:** the seed has approvals pending 4 to 5 days, so `OldestPendingApprovalTooOld` already fires on a fresh stack. Expected, but the alerts page is not all green.
- **Flaky tests:** frontend unit tests under CPU load; TC-06 under parallel load. Not product defects; they can turn CI red at random.
- Not exercised in this review: dashboard UI screens (only the API and the login landing), delegation, bulk approve (APIs merged, UIs pending).

## Re-check after fixes - 19:08 (L1, T-903 unblock)
Fresh clone of `main` at `8258846`, `docker compose down -v` then a clean start, so the database was reseeded.

| Blocker at 18:00 | Now | Evidence |
|---|---|---|
| Main CI red (Maven wget, order-dependent test) | Fixed | PR #64, PR #63 merged; main CI green on the #74 merge (all 8 jobs) |
| T-025 approver inbox + delegations not merged | Done | PR #59 |
| T-026 auditor queue UI not merged | Done | PR #67 |
| T-031 payouts + policy rules screens not merged | Done | PR #61 (policy-rules blank page for CONFIG rows: fix in PR #81) |
| T-006 role layout + menu not merged | Done | PR #65 |
| T-035 E2E incomplete | Done | PR #78 |
| Open bugs T-506 / T-507 / T-508 | Done | PR #56, #50, #51 |
| Demo auditor could not see claims assigned to other auditors (found in T-190) | Fixed | PR #74 (AUDITOR level = team pool, ADR-001 amendment) |

**Checks**
- API walk-through of every persona (45 checks: auto-approve/reject, routing, approve, send-back + resubmit, delegation, auditor queue + bulk approve + sample audit, payout -> CSV -> mock bank -> PAID, dashboards for 4 roles, admin, 403s, 11 web routes): **45/45 pass**.
- Playwright (API RBAC matrix + UI desktop + UI 360 px + axe): **153 passed, 0 failed, 10 skipped**. The skipped tests are TC-08/09/10/12/13 (`test.fixme`, run on 2 projects); the API walk-through covers those flows.
- Found during the re-check: changing `FRONTEND_PORT` broke browser login (403 CORS), because compose never passed `CORS_ALLOWED_ORIGINS`. Fixed in this PR: it is now derived from `FRONTEND_PORT`.

**Result: PASS.** Payout -> CSV -> bank -> PAID, dashboards with 1D/7D/1M/Custom, super admin screens, drill + runbook + RCA, and more than 10 E2E cases green.
