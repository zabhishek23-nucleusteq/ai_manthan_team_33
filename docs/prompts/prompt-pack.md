# Prompt pack - reusable prompts (CREATE format)

Reusable AI asset: prompts our squad uses across the lifecycle with Claude Code. Each follows **CREATE** - **C**ontext, **R**ole, **E**xpectation, **A**udience, **T**one/format, **E**xamples. Copy, fill the `<...>`, run in plan mode first. Other squads can adopt it as is.

Rules that apply to every prompt: plan first, verify before ship (run the tests and show output), synthetic data only, stay inside your folders, never invent API fields not in `contracts/openapi.yaml`.

---

## A. Architecture (L1)

### A1. Requirements from the problem statement
> **Context:** `docs/problem/problem-statement.md`, `squad doc team-standards`. **Role:** senior solution architect. **Expectation:** `docs/requirements.md` with personas, FR-n (each with acceptance criteria), BR-n (every number/threshold/window in the statement, exact value), NFRs incl. JWT auth, role-based access, super admin, dashboard date filter, assumptions A-n, out of scope, traceability FR -> task. **Audience:** 6 developers who start coding in 45 minutes. **Tone/format:** numbered, terse, no prose. **Examples:** "BR-3: electronics return window is 10 days (day 10 allowed, day 11 refused, code WINDOW_EXPIRED)".

### A2. Failure modes before code
> **Context:** the state machine in `CLAUDE.md` §6. **Role:** reliability engineer reviewing a design. **Expectation:** for every transition list double submit, retry after timeout, concurrent actors, illegal transition, partial external failure, boundary values - each with the guard (constraint, lock, idempotency key, state check) and the test that proves it, in `docs/architecture/failure-modes.md`. **Audience:** backend devs and QA. **Tone/format:** table. **Examples:** "approve twice in parallel -> optimistic @Version -> second gets 409 -> test `approve_concurrent_onlyOneSucceeds`".

### A3. RBAC matrix
> **Context:** personas in `docs/requirements.md`, team-standards §5 and §7. **Role:** security architect. **Expectation:** `docs/architecture/rbac.md`: roles (personas + SUPER_ADMIN), self-sign-up role, matrix role x screen x API (method + path) x action, landing dashboard and menu per role, demo login per role. **Audience:** backend (`@PreAuthorize`), frontend (menus, guards), QA (403/401 tests). **Tone/format:** tables only. **Examples:** "`POST /api/refunds/{id}/approve` - FINANCE_REVIEWER allow, others 403".

### A4. ADR
> **Context:** `<decision, e.g. idempotency strategy>`, failure-modes.md. **Role:** architect. **Expectation:** `docs/adr/ADR-00n-<slug>.md`: Context, at least 2 real options with pros/cons, Decision, Consequences. **Audience:** judges and future maintainers. **Tone/format:** 1 page. **Examples:** "idempotency key table vs unique constraint per return item".

### A5. API contract
> **Context:** requirements, rbac.md. **Role:** API designer. **Expectation:** `contracts/openapi.yaml` (3.1) covering every FR, auth endpoints, admin endpoints, `from`/`to` on every KPI endpoint, error schema `{code,message,details}` with stable codes, `Idempotency-Key` on state-changing POSTs, bearer security scheme, examples. **Audience:** frontend and backend coding in parallel. **Tone/format:** valid OpenAPI, lint-clean. **Examples:** one example per main schema.

## B. Backend (L2, L3) - Spring Boot 4.1, Java 17

### B1. Feature slice
> **Context:** task `<T-xxx>`, FR/BR `<ids>`, contract path `<path>`, rbac.md row. **Role:** senior Spring Boot engineer. **Expectation:** Flyway migration, entity + repository, service with the business rule (pure domain method), controller matching the contract exactly with `@PreAuthorize`, JUnit tests: rule boundaries, illegal transition 409, allowed role 2xx / other role 403 / no token 401. Run `mvnw -B verify` and show output. **Audience:** reviewer reading the PR. **Tone/format:** small commits, conventional messages. **Examples:** test name `br3_electronicsWindowIs10Days`.

### B2. JWT security
> **Context:** team-standards §5, rbac.md. **Role:** Spring Security specialist. **Expectation:** stateless security config, users/roles schema, BCrypt, signup/login/me endpoints, HS256 JWT with roles claim, 401/403 JSON handlers, CORS, tests for login, signup, expiry, 401, 403. **Audience:** all other backend tasks depend on it. **Tone/format:** minimal, standard Spring APIs only. **Examples:** `POST /api/auth/login` -> `{accessToken, tokenType:"Bearer", expiresIn, user}`.

### B3. Seed data
> **Context:** team-standards §2, requirements (counts), state machine. **Role:** test-data engineer. **Expectation:** idempotent seed with fixed random seed, exact counts, dates relative to now over 60 days (3-5% in last 24 h), all states, boundary records, one demo user per role, named demo records, summary in `docs/testing/seed-summary.md`. **Audience:** dashboard demo and testers. **Tone/format:** code + summary table. **Examples:** order DEMO-1002 bought 31 days ago -> refused by BR-1.

### B4. Dashboard KPI endpoint
> **Context:** team-standards §1, KPI definitions in `docs/ux/internal-wireframes.md`. **Role:** backend engineer. **Expectation:** endpoint with `from`/`to` validation, SQL aggregation with bucket by range, role check, tests at 24 h / 7 d / 30 d boundaries + invalid range. **Audience:** L5 dashboard. **Tone/format:** contract-exact JSON. **Examples:** `GET /api/dashboard/returns?from=..&to=..`.

## C. Frontend (L4, L5) - React + Ant Design

### C1. Screen with Ant Design
> **Context:** wireframe `<section>`, contract path, rbac.md (who sees it), team-standards §6. **Role:** senior React + Ant Design engineer. **Expectation:** screen built only from Ant components (Form/Table/Card/Steps/Result...), loading/empty/error/success states, keyboard access, labels, 360px layout, route guarded by role, Vitest test + Playwright spec. **Audience:** judges scoring UI/UX. **Tone/format:** small components, hooks for data. **Examples:** refusal shown as `Alert type="warning"` with the plain-language reason.

### C2. Login / sign-up / guards
> **Context:** team-standards §5, auth endpoints in the contract. **Role:** frontend security-minded engineer. **Expectation:** Ant Form pages, AuthProvider (token memory + sessionStorage), RequireAuth/RequireRole, 401 -> login with return URL, 403 Result page, redirect to the role's landing dashboard. **Audience:** every other screen. **Tone/format:** typed, tested. **Examples:** wrong password -> `Alert` "Email or password is incorrect".

### C3. Dashboard with date filter
> **Context:** team-standards §1, KPI endpoints. **Role:** data-viz engineer. **Expectation:** RangePicker with presets 1 Day / 7 Days / 1 Month + custom, default 7 Days, URL-synced, KPI Statistic cards + accessible charts + table fallback, metric definitions in tooltips, Empty state with "widen range", Playwright preset test. **Audience:** managers and super admin. **Tone/format:** Ant Card grid, stacks at 360px.

## D. Test, deploy, operate (L6)

### D1. RBAC test matrix
> **Context:** rbac.md, seeded demo users. **Role:** QA engineer. **Expectation:** API tests for every protected endpoint (allowed 2xx, other role 403, no token 401) + Playwright per-role login/menu/403 tests; results in `docs/testing/rbac-results.md`. **Audience:** judges (security + testing). **Tone/format:** data-driven table of cases.

### D2. E2E test cases with boundaries
> **Context:** requirements FR/BR. **Role:** test designer. **Expectation:** `docs/testing/test-cases.md` with >= 10 cases (incl. day N vs N+1, thresholds, double submit, mixed baskets) and matching Playwright specs. **Audience:** judges. **Tone/format:** table id/persona/steps/expected/FR/spec/result.

### D3. Compose + CI
> **Context:** repo layout, services. **Role:** DevOps engineer. **Expectation:** compose with healthchecks, one-command `start.ps1`/`start.sh`, GitHub Actions running `mvnw verify`, npm lint/test/build, image builds, E2E; verified on a fresh clone. **Audience:** a judge who clones the repo. **Tone/format:** pinned versions, comments only where non-obvious.

### D4. Runbook, drill, RCA
> **Context:** alerts, sre-handbook. **Role:** SRE. **Expectation:** runbook per alert; run the drill (break the mock via `/admin/faults`), follow the handbook, write `docs/sre/rca-00n.md` with timeline, impact, root cause, detection, resolution, actions. **Audience:** on-call engineer and judges. **Tone/format:** checklists and a timeline table.

## E. Every role

### E1. Bug fix
> **Context:** bug task `<T-5xx>` steps/expected/actual/evidence. **Role:** engineer owning the code. **Expectation:** reproduce with a failing test first, minimal fix, all tests green, PR, mark done, defect-log row updated. **Audience:** reviewer. **Tone/format:** `fix(scope): ...` commit.

### E2. Self-review before PR
> **Context:** my diff (`git diff origin/main`). **Role:** strict reviewer. **Expectation:** list correctness bugs, missing role checks, missing UI states, contract mismatches, missing tests - then fix them. **Audience:** me. **Tone/format:** short list, most severe first.

### E3. Journal entry
> **Context:** this session. **Role:** team lead documenting AI use. **Expectation:** one `/squad-journal` entry in CREATE form with what Claude produced, how we verified, and any correction. **Audience:** judges (Claude usage 25 pts).
