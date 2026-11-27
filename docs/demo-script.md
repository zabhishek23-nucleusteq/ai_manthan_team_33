# ExpenseFlow - 5-minute demo script

One presenter (P) drives the browser; one helper (H) runs the terminal. Password for every login: `Demo@123`.
All data is synthetic (seeded relative to today).

| Login | Role | Lands on |
|---|---|---|
| `employee@expenseflow.test` | EMPLOYEE | `/me/dashboard` |
| `manager@expenseflow.test` | MANAGER | `/manager/dashboard` |
| `auditor@expenseflow.test` | FINANCE_AUDITOR | `/finance/dashboard` |
| `head@expenseflow.test` | FINANCE_HEAD (+ SUPER_ADMIN) | `/finance/dashboard` |
| `admin@expenseflow.test` | SUPER_ADMIN | `/admin` |

## Before the clock starts (T-10 min)
1. `./start.ps1` (or `./start.sh`); wait for "Frontend: http://localhost:3000". `docker compose ps` shows 6 containers up.
2. Open tabs: http://localhost:3000 (logged out), a **private window** for the second role, http://localhost:9090/alerts, http://localhost:3001 (Grafana "ExpenseFlow overview"), `docs/sre/runbook.md#bank-payout-failures` on GitHub, GitHub **Actions** tab, `docs/claude-journal/`.
3. Have a small receipt file ready (any PNG/PDF under 5 MB, e.g. `receipt.png`).
4. Reset faults: `curl -X DELETE localhost:8081/admin/faults`. Note that `OldestPendingApprovalTooOld` is already firing on seed data (expected, see runbook) - mention it only if asked.
5. Zoom browser to 110 %, close notifications.

## Timeline

### 0:00-0:30 - Problem and persona map (P, slide or README)
- "Expense claims today: email + spreadsheets, policy checked by hand, weeks to get paid, no audit trail."
- "ExpenseFlow: employee submits -> **policy engine** decides auto-approve / route / auto-reject with a reason -> chain manager -> auditor (> 25,000) -> head (> 1,00,000) -> payout batch -> bank file -> PAID. Every step audited."
- Persona map: Employee, Manager, Finance auditor, Finance head, Super admin - one login each (table above).

### 0:30-1:45 - Employee claim, incl. an auto-reject (P)
1. Log in as **employee** -> lands on own dashboard. **New claim**.
2. Line: MEAL, **800 INR**, today, vendor "Cafe Demo", **no receipt** -> Submit.
   -> **"Claim rejected by policy"** with reason **`RECEIPT_REQUIRED` - receipt mandatory above 500 INR** (BR-5). "Rules are config in `policy_rules`, not code (ADR-002)."
3. New claim: HOTEL, Mumbai, 3 nights, **30,000 INR**, attach `receipt.png` -> Submit.
   -> **Routed** with `HOTEL_NIGHTLY_LIMIT` (10,000/night > 6,000 tier-1), status **PENDING_MANAGER**. Point out: > 25,000 so the chain will include the auditor.
4. (If time) a 250 INR meal -> **auto-approved** instantly.

> **H at 1:30:** inject the bank fault and submit a payout so the alert is firing by 3:15 (it needs ~1.5 min):
> ```
> curl -X POST localhost:8081/admin/faults -H "Content-Type: application/json" -d '{"errorRate":1,"latencyMs":0,"mode":"500"}'
> ```
> then as **head** in the private window: **Payouts** -> create batch -> **Submit to bank** -> fails with `BANK_UNAVAILABLE` (502), batch `SUBMIT_FAILED`.
> **Click Retry once more (fault still on) - it must fail twice.** The `payouts_total{outcome="failure"}` series only appears at the first failure, so `increase()` sees no rise from one failure after a backend restart and the alert never fires (found in the rehearsal, bug filed). The second failure makes it 1 -> 2 and the alert fires about a minute later.

### 1:45-2:45 - Manager, auditor, head (P)
1. **manager** -> **Approvals**: the 30,000 hotel claim is on top with its violation tag. Open it -> receipt viewer -> **Approve**. Status -> **PENDING_AUDITOR**.
   - Mention **Send back** (CLARIFICATION_REQUESTED) and **Delegations** (`/delegations`, same-or-higher role, max 30 days) - show the button, don't click.
2. **auditor** -> **Audit queue**: filters, the claim is there (only once PR #74 "AUDITOR level is a finance team pool" is merged - before that the step goes to a seeded auditor and `auditor@` gets 403; then just bulk approve seeded items); tick it plus two seeded items -> **Approve selected (3)** (bulk approve). Point at the **sample audit** tag (random 10 % of auto-approved claims, BR-13).
3. **head** -> **Approvals**: a seeded **PENDING_HEAD** claim (> 1,00,000) -> Approve -> **APPROVED**. "An approver can never act on their own claim; it escalates (BR-15)."

### 2:45-3:15 - Dashboard (P, as head)
- Finance dashboard: KPI flashcards + charts; switch **1D / 7D / 1M / Custom** - numbers change (seed is dated relative to today). Manager sees TEAM scope, employee OWN scope - same API with `from`/`to`.

### 3:15-4:00 - Break the bank -> alert -> runbook (P + H)
1. http://localhost:9090/alerts -> **`BankPayoutFailures` FIRING** (SEV2). Grafana panel "Bank payout files by outcome" shows the failure.
2. Open `docs/sre/runbook.md#bank-payout-failures`: meaning, diagnosis, mitigation. "Nothing was paid - the bank is idempotent on BATCH_ID."
3. H follows the runbook live: `curl localhost:8081/admin/faults` (shows mode 500) -> `curl -X DELETE localhost:8081/admin/faults`.
4. P: **Payouts** -> **Retry** the batch -> **PAID**. Show `docs/sre/rca-drill.md` (timeline: fired after 84 s, resolved, RCA with action items).

### 4:00-4:40 - How we used Claude (P, GitHub)
- **6 laptops, 6 roles, one repo:** the only channel was git - task board on branch `squad/board`, PR per task, contract in `contracts/openapi.yaml`.
- **CLAUDE.md** = shared project memory (rules, glossary, BR values, sprint plan) read by every Claude session.
- **Hourly sprints** with a review gate: `docs/sprints/S1-report.md`, `S3-report.md` (fresh clone, tests, "show the invigilator" script, bugs filed).
- **Journal** `docs/claude-journal/L1..L6.md`: prompts, what Claude produced, how it was verified, and every correction we made to Claude's output.

### 4:40-5:00 - Tests and CI green (P)
- GitHub **Actions**: latest `main` run green (backend `mvnw verify` with Testcontainers, frontend lint + test + build, images, E2E).
- Numbers: RBAC matrix 63/63, frontend unit tests, Playwright API + UI (desktop and 360 px). "Thank you."

## Fallbacks
- Alert not yet firing at 3:15: show it **PENDING** (yellow) and the failed batch, go to the runbook, come back after the Claude section.
- A UI screen fails: same step via Swagger http://localhost:8080/swagger-ui.html (port is `BACKEND_PORT`; on a laptop where 8080 is taken, e.g. by Airflow, it is 18080 - check `docker compose ps`) (logged in as that role) - `POST /api/payout-batches`, `.../submit`.
- Running long: skip step 0:30-4 and the delegation mention; never skip the alert or CI.

## Rehearsal log
| Date | Who | Duration | Notes |
|---|---|---|---|
| 2026-11-27 | L4 (API dry run of every step on the live stack, L4 laptop) | Actions 15 s + alert wait | Auto-reject `RECEIPT_REQUIRED` "Receipt required above 500 INR (line 1: 800 INR)" OK; hotel 30,000 -> `PENDING_MANAGER` + `HOTEL_NIGHTLY_LIMIT` OK; manager approve -> `PENDING_AUDITOR` OK; **auditor@ approve 403** (step assigned to a seeded auditor; fixed by PR #74); head approves seeded PENDING_HEAD -> `APPROVED` OK; payout submit with fault -> 502 `BANK_UNAVAILABLE` OK; **alert did not fire within 5 min after a single failure** (counter starts at 1, see above) -> script now fails twice; reset faults + retry -> `PAID` OK. Spoken timeline fits 5:00 with the fault injected at 1:30. |
