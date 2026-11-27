# S1 sprint review - 2026-11-27 17:05
Result: PASS (with notes)

Checked against `main` @ 8602ccc. Live stack = `docker compose` already running on the L6 laptop (not a from-scratch `start.ps1` run, see Risks).

## Show the invigilator (2 minutes)
1. http://localhost:3000/ -> sign up a new employee, then log in -> lands on the app (FR-1 sign-up + JWT login).
2. http://localhost:3000/design -> Ant Design theme page with tokens and state wrappers.
3. Log in as `employee`, `manager`, `auditor`, `head`, `admin` @expenseflow.test / `Demo@123` -> each gets its own roles (`/api/auth/me`).
4. `curl http://localhost:8080/api/auth/me` without a token -> 401 JSON.
5. http://localhost:8080/actuator/health -> UP; Prometheus :9090 and Grafana :3001 respond; mock bank :8081 UP.
6. Open `docs/architecture/`, `docs/adr/` (ADR-001, ADR-002), `docs/ux/`, `contracts/openapi.yaml`.

## What works now (generated in this sprint)
- One-command stack: postgres, backend, frontend, mock bank, Prometheus, Grafana all up.
- Sign-up, login, `/me` with JWT; 6 demo logins return the right roles; no token -> 401.
- Frontend scaffold + theme + `/design` page, login/sign-up pages, route guards.
- Domain schema (Flyway), audit trail + idempotency base, mock bank with fault injection.
- Architecture pack: RBAC matrix, 2 ADRs, failure modes, contract, wireframes, SRE handbook.
- Already ahead of S1: policy engine, claims API, employee claim editor, KPI API.

## Checks
| Check | Result | Evidence |
|---|---|---|
| Stack up | PASS | 6 containers Up, postgres healthy |
| Health | PASS | backend `{"status":"UP"}`, frontend 200, `/design` 200, mock bank 200, Prometheus 200, Grafana 200 |
| Login per role | PASS | 6/6 demo users, expected roles |
| Sign-up | PASS | new user created with role EMPLOYEE |
| No token | PASS | `/api/auth/me` -> 401 |
| Backend tests | PASS | 125/125 (Docker maven:3-eclipse-temurin-17), 0 fail/err/skip |
| Frontend | PASS | lint clean, vitest 61/61, build OK (chunk-size warning only) |
| CI on main | PASS | latest completed run success (#42); one earlier run was cancelled by a newer push |
| Open PRs | PASS | none |
| Tasks done | PASS | all S1 tasks done; T-006 was moved to S3 |
| E2E | NOT RUN | Playwright specs not merged yet (T-029/T-035) |

## Bugs filed
None.

## Not done / risks
- Not a from-scratch `start.ps1` run: ports 3000/3001/8080 were held by the running stack. Tests ran on a fresh clone instead.
- Local JDK on this laptop is 11, so `mvnw` fails with "release version 17 not supported". Tests need Docker or JDK 17 on PATH (CLAUDE.md says JDK 17 is on every laptop; this one differs).
- T-006 (role menu + per-role landing dashboard) is not done, so "each role on its own dashboard" is verified at API level only, not in the UI.
- T-901 was fast-tracked as skipped on the board; this report is the S1 review run late.
