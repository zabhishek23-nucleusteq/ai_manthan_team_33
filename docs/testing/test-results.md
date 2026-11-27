# Test results - 2026-11-27 18:25 IST

Plan: [test-plan.md](test-plan.md) · Cases: [test-cases.md](test-cases.md) · RBAC: [rbac-matrix.md](rbac-matrix.md) · Bugs: [defect-log.md](defect-log.md)

Run by L6 on `main` @ `117de3e` (#69), stack rebuilt with `docker compose up --build -d`.

## Summary
| Suite | Passed | Failed | Skipped | Notes |
|---|---|---|---|---|
| Backend (`mvn -B verify`, JUnit + Testcontainers, Docker `maven:3-eclipse-temurin-17`) | BE_PASS | BE_FAIL | BE_SKIP | BE_NOTE |
| E2E API + UI desktop + UI 360 px (Playwright, Edge) | 150 | 2 | 11 | failures are test-data, see below |
| CI on main | - | - | - | last completed green run: [46001210158](https://github.com/zabhishek23-nucleusteq/ai_manthan_team_33/actions/runs/46001210158) (`3266c5e`, #66). Later main runs were cancelled by newer pushes or were still running at review time |

## E2E failures
| Test | Project | Cause | Action |
|---|---|---|---|
| TC-01 admin -> /admin | ui, ui-mobile | expects "Logged in as Abhishek Admin". PR #66 renamed the seeded admin, but the seed runs only on an empty database, and the review stack reused an older Postgres volume that still has "Sam Admin". | Not a product bug. A fresh start (`docker compose down -v; ./start.ps1`) seeds the new name. Re-run after the volume reset. |

## Skipped (11)
`test.fixme` cases waiting for features or automation: TC-08, TC-09, TC-10 (approver inbox/delegation, T-025), TC-12 (auditor queue, T-026), TC-13 and TC-14 (UIs merged, specs not yet automated), plus the same flow cases in other projects. Skipped cases are never counted as passed.

## Manual / API checks for the newly merged features
- **TC-13 payout (T-017/T-031):** `GET /api/payout-batches` as head returns PB-0011 `PAID`, 339 claims, 4,705,514.58 INR; `GET /api/payout-batches/eligible` returns `{count: 2, total: 500.00}`; `/finance/payouts` page returns 200.
- **TC-14 dashboards (T-033):** dashboard page merged; TC-01 asserts the real dashboards for employee, manager, auditor and head (all passed).

## Open risks
- Main CI keeps cancelling queued main runs (concurrency group per ref). The fix is in PR #64.
- The stack was stopped by someone else while this review was running. The results above were captured before that.
