# CLAUDE.md - project memory (shared by all 6 laptops through Git)

Read fully at the start of every session.

## 1. How this team works
- 6 laptops, 6 roles. This laptop's role: `squad whoami`. Role rules: your `/role-...` skill. Ownership map: `squad doc roles`.
- The only channel between laptops is this repo:
  - Task board: branch `squad/board` (hidden worktree `.squad-board/`), used only through the `squad` command.
  - Code: short-lived branches -> PR -> `main`.
  - Shared decisions: `docs/` (requirements, architecture, ADRs, milestones) and `contracts/openapi.yaml`.
- Work is split into **hourly sprints S1..Sn** (field `milestone` on each task). Always finish the earliest sprint first; every sprint ends with something showable to the invigilators. Each sprint ends with a **review** task: L6 runs `/squad-milestone-check Sx` (fresh clone, start, tests, PRs merged, CI green), writes `docs/sprints/Sx-report.md` with a "Show the invigilator" script, and files bug tasks.
- Team standards (login/sign-up + JWT + role-based access, super admin dashboard, Ant Design, dashboard date filter 1D/7D/1M/Custom, seed data dated relative to today, sprint showcase, bug flow): `squad doc team-standards`. SRE handling: `docs/sre/sre-handbook.md` (template: `squad doc sre-handbook`).
- Start every session with `/squad-work` (L2-L6 in a loop: `/loop /squad-work`).

## 2. Non-negotiable rules (hackathon gates - breaking one = disqualified from ranking)
1. Synthetic or public data only. Never real customer/company data in code or prompts.
2. Open-source / free stack only.
3. Code stays on the laptop or in this company repo.
4. No pre-built solution code. Everything domain-specific is written during the event.

## 3. Working rules for Claude on every laptop
- **Plan first.** Read the task, the referenced FR/BR, contract and ADR; write a short plan (files, tests, verification); then implement.
- **Contract first.** `contracts/openapi.yaml` is the source of truth between frontend and backend. Contract changes go in their own small PR with a note to L1.
- **Stay in your lane.** Only edit folders your role owns. Need something elsewhere: `squad add --owner Lx --title ...`.
- **Verify before ship.** Run the tests for what you changed and show the output. Never claim it works without running it.
- **Bugs first.** Bug tasks (`T-5xx`) are always taken before features.
- **Small commits** after each verified step: `feat(scope): ...`, `fix(scope): ...`, `test(scope): ...`, `docs(scope): ...`.
- **Rebase on `origin/main` before every PR.** Merge only when CI is green (or CI does not exist yet and local tests passed).
- **UI states are scored:** every screen has loading, empty, error and success states, keyboard access, labels, and works at 360px.
- No secrets in code: `.env` (git-ignored) + `.env.example`.
- **Context and cost.** One task per session; `/clear` after it is done. Search with Grep/Read or the `Explore` agent. No `general-purpose` subagents for lookups; if a subagent is needed pass `model: "sonnet"`.
- **Company laptop policy.** If Windows blocks a file ("Application Control policy has blocked this file"), do not try to bypass it: run that step inside Docker.
- Record notable Claude use and every correction of Claude's output with `/squad-journal`.

## 4. Fixed tech stack (do not debate it)
| Layer | Choice |
|---|---|
| Backend | Java 17 (JDK 17, already on every laptop), **Spring Boot 4.1.x**, Maven wrapper (`mvnw` / `mvnw.cmd`), Spring Web, Spring Data JPA, Bean Validation, Flyway, springdoc-openapi, **Spring Security + JWT** (stateless, roles per `docs/architecture/rbac.md`) |
| DB | PostgreSQL 18 (Docker) |
| External mocks | Small separate Spring Boot app(s) in `services/<name>/` with fault injection |
| Frontend | React 19 + Vite 7+ + TypeScript (Node 26.13.1), **Ant Design** (antd + icons, theme tokens via ConfigProvider), React Router, TanStack Query, Vitest |
| Tests | JUnit 5 + Spring Boot Test + MockMvc, Testcontainers (PostgreSQL) for repository/API tests, Vitest, Playwright 1.60+ (channel `msedge` if browser download is blocked) |
| Observability | Spring Boot Actuator + Micrometer Prometheus (`/actuator/prometheus`), Prometheus + Grafana in Compose, alert rules in `infra/prometheus/alerts.yml` |
| Deploy | `docker compose up --build` via `./start.ps1` / `./start.sh` |
| CI | GitHub Actions: `./mvnw -B verify`, frontend lint + test + build, image build, E2E |

## 5. Repo layout
```
backend/            Spring Boot app: src/main/java/<pkg>/{api,domain,service,repository,config,observability}, src/main/resources/db/migration (Flyway), src/test/java
services/<mock>/    mock external service(s) (Spring Boot)
frontend/           React app (src/design, src/components, src/features/<persona>/, src/api)
contracts/          openapi.yaml
infra/              prometheus/, grafana/
tests/e2e/          Playwright specs
docs/               problem/, requirements.md, architecture/, adr/, milestones/, ux/, testing/, sre/, claude-journal/
```

## 6. Project specifics - ExpenseFlow
Full detail: `docs/requirements.md` (FR/BR/NFR/seed), `docs/architecture/` (README, rbac.md, failure-modes.md), `docs/adr/`, `contracts/openapi.yaml`. Base package `com.expenseflow`.

### Glossary
- **Claim**: one expense report (code `EXP-000123`, demo `DEMO-1001`) with 1..50 **lines** (category MEAL/HOTEL/CAB/TRAVEL/OTHER, amount, expense date, vendor, city+nights for HOTEL, receipt).
- **Policy engine**: runs every enabled rule in table `policy_rules` on every line -> outcome **AUTO_APPROVED / ROUTED / AUTO_REJECTED** + violation codes. Rules are config, not code constants (ADR-002).
- **Chain**: approval steps MANAGER -> AUDITOR (> 25,000) -> HEAD (> 1,00,000) (ADR-001). **Delegation**: approver's steps go to a delegate for a date range. **Send back**: CLARIFICATION_REQUESTED.
- **Audit queue**: auditor's filtered list (PENDING_AUDITOR + sample audits) with bulk approve. **Sample audit**: random 10% of auto-approved claims.
- **Payout batch**: APPROVED claims -> CSV bank file -> mock bank (`services/mock-bank`) -> PAID.

### Roles (rbac.md)
EMPLOYEE (self sign-up) · MANAGER · FINANCE_AUDITOR · FINANCE_HEAD (+SUPER_ADMIN on demo user) · SUPER_ADMIN. Demo logins `employee|manager|manager2|auditor|head|admin@expenseflow.test` / `Demo@123`.

### State machine
`DRAFT -submit-> AUTO_REJECTED | APPROVED (auto) | PENDING_MANAGER -> [PENDING_AUDITOR] -> [PENDING_HEAD] -> APPROVED -> IN_PAYOUT -> PAID`; any PENDING_x -> REJECTED | CLARIFICATION_REQUESTED (-> resubmit = submit again, restart at MANAGER); APPROVED -> REJECTED on sample-audit EXCEPTION; IN_PAYOUT -> APPROVED only when a failed batch is cancelled. Terminal: AUTO_REJECTED, REJECTED, PAID.

### Business rules (exact values; "above" = strictly greater; BigDecimal compareTo)
- BR-1 Meals <= 1,500 INR per employee per day (sum across claims) -> `MEAL_DAILY_LIMIT` ROUTE
- BR-2/3 Hotel <= 6,000 per night tier-1 (Mumbai, Delhi, Bengaluru, Chennai, Kolkata, Hyderabad, Pune, Ahmedabad), 4,000 others (amount/nights) -> `HOTEL_NIGHTLY_LIMIT` ROUTE
- BR-4 Cab <= 2,000 per line (trip) -> `CAB_TRIP_LIMIT` ROUTE
- BR-5 Receipt mandatory when amount > 500 -> `RECEIPT_REQUIRED` REJECT
- BR-6 Same vendor (trim, case-insensitive) + amount + date as an earlier non-rejected line -> `DUPLICATE_LINE` REJECT
- BR-7 Same receipt SHA-256 as an earlier non-rejected line -> `DUPLICATE_RECEIPT` REJECT
- BR-8 Saturday/Sunday expense -> `WEEKEND_SPEND` ROUTE (manager attention tag)
- BR-9 Any REJECT -> AUTO_REJECTED; else any ROUTE -> ROUTED; else total <= 5,000 -> AUTO_APPROVED; else ROUTED
- BR-10..12 Chain: MANAGER always; + AUDITOR if total > 25,000; + HEAD if total > 1,00,000
- BR-13 Sample audit 10% of AUTO_APPROVED · BR-14 delegation: same-or-higher approver role, never the claimant, one hop, <= 30 days
- BR-15 No self-approval -> escalate to next level · BR-16 resubmit re-runs policy, restarts at MANAGER
- BR-17 Bank CSV format (requirements.md) · BR-18 payable = APPROVED, sample audit not PENDING/EXCEPTION, not in a batch · BR-19 avg approval hours = submitted -> final human decision
- BR-20 Receipt PDF/JPG/PNG <= 5 MB · BR-21 seed 150 employees, 8 departments, 3,000 claims

### Invariants
1. Employee sees only own claims (others -> 404). 2. Approver never acts on own claim. 3. Only the assigned approver (or active delegate) acts on an open step. 4. Lines are editable only in DRAFT/CLARIFICATION_REQUESTED. 5. A claim is in at most one payout batch and is paid once. 6. `audit_events` is append-only (DB trigger) and every state change writes one in the same transaction. 7. Every state-changing POST takes `Idempotency-Key`. 8. Policy results are stored with a rule snapshot; rule changes do not rewrite history. 9. Every KPI endpoint takes `from`/`to` (NFR-DASH).

### Sprint plan (build window 3 hours; sprint = 1 hour; review gate T-90x at the end of each)
| Sprint | Hour | Showable result |
|---|---|---|
| S1 | hour 1 | Architecture pack (diagrams, state machine, rbac, 2 ADRs, contract, failure modes), wireframes, `/design` theme page, one-command start (db + backend health + frontend), CI first run, login/sign-up with JWT and each role on its own placeholder dashboard, audit + idempotency base |
| S2 | hour 2 | Seeded data (150 users, 3,000 claims); employee creates claim with receipts -> policy engine outcome with reasons; manager/auditor/head chain with send-back + delegation and receipt viewer; auditor queue with filters, bulk approve, sample audit; RBAC tests; Prometheus + Grafana up |
| S3 | hour 3 | Payout batch -> CSV -> mock bank -> PAID; role dashboards with 1D/7D/1M/Custom on seed data; super admin (users, overview, audit, health); metrics + alert + runbook + drill RCA; 10 E2E cases; README + demo. **Feature freeze at 2h30** |
