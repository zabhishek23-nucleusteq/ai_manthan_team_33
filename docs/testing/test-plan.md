# Test plan - ExpenseFlow

Owner: L6 (QA/DevOps/SRE). Results of the latest run: [test-results.md](test-results.md).

## Scope
- **In scope:** auth and RBAC (FR-1, rbac.md), the policy engine (BR-1..BR-9), the approval chain (BR-10..BR-16), the auditor queue and sample audit (BR-13), payout batch, CSV export and mock bank (BR-17, BR-18), dashboard KPIs with a date range (NFR-DASH), super admin APIs, audit trail and idempotency (invariants 6 and 7), UI states and accessibility (NFR-A11Y, 360 px).
- **Out of scope:** load testing, real bank integration and real customer data (synthetic seed data only).

## Test levels
| Level | Tool | Where | What it proves | Run |
|---|---|---|---|---|
| Backend unit + integration | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL) | `backend/src/test/java` | policy rules incl. boundaries, state machine, chain, payout, idempotency, audit trigger, seed | `./mvnw -B verify` (or Docker `maven:3-eclipse-temurin-17`) |
| Mock bank | JUnit 5 | `services/mock-bank/src/test` | BR-17 file format, fault injection, idempotent on `BATCH_ID` | `./mvnw -B verify` |
| Frontend unit | Vitest + Testing Library | `frontend/src/**/*.test.tsx` | components, forms, UI states, auth context, API client | `npm run lint; npm test -- --run; npm run build` |
| API E2E (RBAC matrix) | Playwright `request` | `tests/e2e/api/rbac.spec.ts` | every endpoint: no token 401, allowed role passes, other role 403; row rules (others' claim 404, sign-up never gives a staff role) | `npm run test:api` |
| UI E2E | Playwright (desktop 1280 + mobile 360) | `tests/e2e/ui/` | written cases TC-01..TC-14, keyboard, axe a11y | `npm run test:ui` |
| CI | GitHub Actions | `.github/workflows/ci.yml` | all of the above on every PR and on main, plus image build and Maven dependency scan | automatic |

## Test design
- **Boundaries** for every money rule: at, just above and just below the limit (500 receipt, 1,500 meals/day, 2,000 cab, 4,000/6,000 hotel per night, 5,000 auto-approve, 25,000 auditor, 1,00,000 head). "Above" means strictly greater.
- **One refusal per business rule** (BR-5..BR-7 reject, BR-1..BR-4 and BR-8 route).
- **RBAC:** each endpoint with one allowed role, one forbidden role and no token.
- **Written UI cases:** [test-cases.md](test-cases.md); each has a persona, preconditions, steps, expected result and the FR/BR it covers.

## Environment and data
- `./start.ps1` (`docker compose up --build`): postgres, backend :8080, frontend :3000, mock bank :8081, Prometheus :9090, Grafana :3001.
- Seed data: 150 employees, 8 departments, 3,000 claims dated relative to today; demo logins `employee|manager|manager2|auditor|head|admin@expenseflow.test` / `Demo@123`.
- Company laptops: `PW_CHANNEL=msedge` when the Playwright browser download is blocked; backend tests in Docker when JDK 17 is not on PATH.

## Entry and exit criteria
- **Entry:** stack healthy (backend `/actuator/health` UP, frontend 200).
- **Exit (per PR):** CI green. **Exit (per sprint):** sprint review in `docs/sprints/Sx-report.md`, every failure filed as a `T-5xx` bug and logged in [defect-log.md](defect-log.md).
- A `test.fixme` case counts as skipped, never as passed.

## Defects
Bug flow: `squad add --type bug --owner <Lx>` with steps, expected, actual and evidence -> bugs are taken before features -> fix PR -> L6 re-tests -> [defect-log.md](defect-log.md) updated.

## Related
[test-cases.md](test-cases.md) · [rbac-matrix.md](rbac-matrix.md) · [security-checklist.md](security-checklist.md) · [defect-log.md](defect-log.md) · [seed-summary.md](seed-summary.md)
