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

## 6. Project specifics
<!-- L1 fills this during /squad-kickoff: glossary, state machine, business rules BR-1.., invariants, milestones. -->
_Not filled yet - waiting for kickoff._
