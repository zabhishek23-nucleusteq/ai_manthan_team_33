# S1 sprint review - 2026-11-27 16:40
Result: **FAIL** (2 open items, both tracked; everything else in S1 works on a fresh clone)

Gate: T-901 · checked on `main` @ `fd92fe0` (stack + tests), CI checked up to `1599302` · run by L1 for L6

## Show the invigilator (2 minutes)
1. `./start.ps1` on a fresh clone -> prints Frontend/Backend/Mock bank URLs in ~3 min (first build) -> one-command start works.
2. http://localhost:8080/actuator/health -> `UP`; http://localhost:8081/actuator/health -> mock bank `UP`.
3. http://localhost:3000/design -> Ant Design theme page (tokens from `docs/ux/design-tokens.md`).
4. http://localhost:3000/login -> log in as `employee@expenseflow.test` / `Demo@123` -> lands on **My dashboard**; log out, log in as `manager@...` -> **Team dashboard**, `auditor@...` -> **Finance dashboard**, `admin@...` -> **Admin overview** (placeholders until T-006).
5. http://localhost:3000/signup -> new account is always EMPLOYEE (even if a staff role is requested).
6. http://localhost:8080/swagger-ui.html -> call `GET /api/auth/me` without a token -> `401 UNAUTHENTICATED`; as employee `PUT /api/policy-rules/AUTO_APPROVE_MAX` -> `403 FORBIDDEN`.
7. Docs: `docs/architecture/` (diagrams, state machine, `rbac.md`, `failure-modes.md`), `docs/adr/ADR-001`, `ADR-002`, `contracts/openapi.yaml`, `docs/ux/` wireframes.

## What works now
- One-command start: db + backend + frontend + mock bank, health UP (T-001, T-002, T-003, T-007).
- Login / sign-up with JWT; sign-up is EMPLOYEE only; 6 demo logins with correct roles (T-004, T-005; demo users from T-013).
- Each role is routed to its own landing page; route guards and API RBAC give 401/403 with contract error bodies (`UNAUTHENTICATED`, `FORBIDDEN`, `INVALID_CREDENTIALS`, `EMAIL_TAKEN`, `VALIDATION_FAILED`).
- `/design` theme page, API client layer (T-003, T-010).
- Domain schema + Flyway, audit trail (append-only) + `Idempotency-Key` base (T-012, T-018).
- Architecture pack, 2 ADRs, contract, employee + internal wireframes, SRE handbook (T-008, T-011, T-014).
- CI on GitHub Actions: backend, frontend, mock-bank, image builds, dependency check, E2E API job (T-009).
- Already ahead of plan: seed 150 users / 3,000 claims, policy engine + rules API, admin APIs, Prometheus + Grafana.

## Checks
| Check | Result | Evidence |
|---|---|---|
| Fresh clone + `start.ps1` | PASS | exit 0, 201 s from clean volumes; 4 containers up, postgres healthy |
| Health | PASS | backend `UP`, mock bank `UP`, frontend `/` and `/design` 200 |
| Demo logins (6) | PASS | all 200 with roles per `rbac.md`; seed users=150, claims=3000 |
| Sign-up / 401 / 403 | PASS | signup 201 EMPLOYEE, dup 409, weak pw 400, no token 401, employee -> admin 403 |
| UI login -> role landing | NOT RUN | routes + `RequireRole` guards present in `App.tsx`; not clicked through in a browser (no UI E2E yet) |
| Backend `mvn verify` | PASS | 123/123 (Testcontainers, in Docker) |
| Frontend lint / test / build | PASS | lint 0 errors 0 warnings; 56/56 tests; build OK |
| E2E API (Playwright) | FAIL | 61 passed / 2 failed / 60 skipped (skips = endpoints of later sprints); the 2 = stale `test.fail` markers, see T-505 |
| CI on main | FAIL | run 45987242005: all jobs green except `e2e-api` (same 2 tests) |
| Open PRs | PASS | none |
| S1 tasks done | FAIL | 13/14 - T-006 role layout + landing dashboards (L5) still in progress |

## Bugs filed
- T-505 Main CI red: remove fixed T-502 entries from `KNOWN_BUGS` in `tests/e2e/api/rbac.spec.ts` -> L6
- T-501 closed as duplicate of T-502 (L3, backend fix merged in #36)

## Not done / risks
- T-006 (L5): real role layout/menu + landing dashboards; today each role lands on a placeholder page.
- Re-run only: CI green after T-505, T-006 merged -> close T-901.
- No UI E2E yet (browser specs come with T-035).
- On this laptop the default ports (5432, 8080) clash with other local stacks; the gate used `.env` overrides. Fresh judge machine is unaffected.
