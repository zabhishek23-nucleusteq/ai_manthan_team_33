# ExpenseFlow - expense claims with policy-driven approvals (AI Manthan Team 33)

Employees capture expense claims with receipts; a configurable policy engine auto-approves, routes or auto-rejects each claim with a reason; claims flow manager -> finance auditor (> 25,000 INR) -> finance head (> 1,00,000 INR); auditors work a review queue with bulk approve and 10% sample audits; approved claims are paid through a CSV bank file to a mock bank. Append-only audit trail, role dashboards, super admin.

## Run (one command)
Prerequisites: Docker Desktop. Then from the repo root:

```powershell
./start.ps1        # Windows
```
```bash
./start.sh         # macOS / Linux / Git Bash
```
Both run `docker compose up --build -d` with values from `.env` (copy `.env.example` to `.env` first; the scripts do it if `.env` is missing).

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend health | http://localhost:8080/actuator/health |
| API docs (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Mock bank | http://localhost:8081/actuator/health |
| Prometheus / Grafana | http://localhost:9090 / http://localhost:3001 (from S2) |

Stop: `docker compose down` (add `-v` to wipe the database and re-seed).

## Demo logins (password `Demo@123`)
| Role | Email |
|---|---|
| Employee | `employee@expenseflow.test` |
| Line manager | `manager@expenseflow.test` (delegate: `manager2@expenseflow.test`) |
| Finance auditor | `auditor@expenseflow.test` |
| Finance head + admin | `head@expenseflow.test` |
| Super admin | `admin@expenseflow.test` |

Demo claims `DEMO-1001`..`DEMO-1010` are described in [docs/requirements.md](docs/requirements.md) §6.

## Docs map
- Requirements (FR/BR/NFR, seed spec): [docs/requirements.md](docs/requirements.md)
- Architecture (context, containers, state machine, ER): [docs/architecture/README.md](docs/architecture/README.md)
- RBAC: [docs/architecture/rbac.md](docs/architecture/rbac.md) · Failure modes + approval-bypass review: [docs/architecture/failure-modes.md](docs/architecture/failure-modes.md)
- ADRs: [ADR-001 approval chain](docs/adr/ADR-001-approval-chain-model.md) · [ADR-002 policy as configuration](docs/adr/ADR-002-policy-as-configuration.md)
- API contract: [contracts/openapi.yaml](contracts/openapi.yaml)
- Sprint tracker: [docs/tracking/squad-tracker.xlsx](docs/tracking/squad-tracker.xlsx)
- Reusable AI asset: [docs/prompts/prompt-pack.md](docs/prompts/prompt-pack.md)

_Tests, sprint reports, SRE runbook/RCA and the demo script are added during the build (T-190)._

## Submission artefacts
- Tests: [test plan](docs/testing/test-plan.md) · [test cases](docs/testing/test-cases.md) · [test results](docs/testing/test-results.md) · [defect log](docs/testing/defect-log.md) · [RBAC matrix](docs/testing/rbac-matrix.md) · E2E: [tests/e2e](tests/e2e)
- SRE: [runbook](docs/sre/runbook.md) · [drill RCA](docs/sre/rca-drill.md) · [alerts](infra/prometheus/alerts.yml)
- Claude usage: [CLAUDE.md](CLAUDE.md) · [prompt pack](docs/prompts/prompt-pack.md) · [journals](docs/claude-journal)
- Sprint reviews + demo script: [docs/sprints](docs/sprints) · tracker: [squad-tracker.xlsx](docs/tracking/squad-tracker.xlsx)
