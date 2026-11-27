# Demo walkthrough with screenshots

The "Show the invigilator" script from [docs/sprints/S1-report.md](../sprints/S1-report.md), run on 2026-11-27 at about 18:23 against the stack rebuilt from `main` (`docker compose up -d --build backend frontend mock-bank`; postgres, Prometheus and Grafana were already up). There is one screenshot per step, in [screenshots/](screenshots/).

Demo logins: `employee | manager | auditor | head | admin @expenseflow.test`, password `Demo@123`. All data is synthetic seed data.

| # | Step | What it shows | Screenshot |
|---|---|---|---|
| 1 | Open `http://localhost:3000/signup` and fill in a new employee | Sign-up form (FR-1) | [01-signup-form.png](screenshots/01-signup-form.png) |
| 2 | Create the account, then log in with it | The new EMPLOYEE lands on **My dashboard**. The empty state reads "No data between … and …" with a *Widen the range* button, and the header shows "Logged in as Demo Newcomer" | [02-signup-then-login-landing.png](screenshots/02-signup-then-login-landing.png) |
| 3 | `http://localhost:3000/design` | Ant Design theme tokens and the state wrappers | [03-design-page.png](screenshots/03-design-page.png) |
| 4 | Log in as `employee` | **My dashboard** (own claims): 7-day range in the URL and in words, KPI cards (spend, claims, pending, violations, average approval time) and charts | [04-login-employee.png](screenshots/04-login-employee.png) |
| 5 | Log in as `manager` | **Team dashboard** (direct reports) | [05-login-manager.png](screenshots/05-login-manager.png) |
| 6 | Log in as `auditor` | **Finance dashboard** (whole company) | [06-login-auditor.png](screenshots/06-login-auditor.png) |
| 7 | Log in as `head` | Lands on **Admin overview**, the landing page the e2e helpers set for this user | [07-login-head.png](screenshots/07-login-head.png) |
| 8 | Log in as `admin` | **Admin overview**: users by role, claims, spend and pending approvals in range, with tabs for Users, Audit log and Health | [08-login-admin.png](screenshots/08-login-admin.png) |
| 9 | `GET http://localhost:8080/api/auth/me` without a token | `401 {"code":"UNAUTHENTICATED","message":"Authentication required"}` | [09-auth-me-401.png](screenshots/09-auth-me-401.png) |
| 10 | `http://localhost:8080/actuator/health` | Backend `UP` | [10-backend-health.png](screenshots/10-backend-health.png) |
| 11 | `http://localhost:8081/actuator/health` | Mock bank `UP` | [11-mock-bank-health.png](screenshots/11-mock-bank-health.png) |
| 12 | `http://localhost:9090/targets` | Prometheus scrape targets | [12-prometheus-targets.png](screenshots/12-prometheus-targets.png) |
| 13 | `http://localhost:3001` → ExpenseFlow overview | The Grafana dashboard is provisioned (see the known gap below) | [13-grafana.png](screenshots/13-grafana.png) |
| 14 | `http://localhost:8080/swagger-ui.html` | The API served from `contracts/openapi.yaml` | [14-api-docs-swagger.png](screenshots/14-api-docs-swagger.png) |

## Re-run
With the stack running:
```
cd tests/e2e && npm ci
node ../../docs/demo/capture.cjs        # uses the installed Edge (PW_CHANNEL=msedge by default)
```
The script prints `OK` or `FAIL` for each step and overwrites the screenshots. On this run all 14 steps printed `OK`.

## Known gaps in this capture
- **Grafana panels are not rendered.** The ExpenseFlow overview dashboard opens, but in the headless capture its panels stay blank. Screenshot 13 proves only that the dashboard is provisioned, not that it shows live data. Open it in a normal browser for the demo.
- Steps 5, 6, 10, 11, 12 and 14 were captured but not checked by eye before this was committed. Steps 2, 4, 7, 8, 9 and 13 were.
- Not run from a clean `start.ps1`: the existing stack was rebuilt in place and kept its data volume.
