# Runbook - ExpenseFlow alerts

One section per alert in `infra/prometheus/alerts.yml`. Flow around it: `sre-handbook.md` section 4 (detect, triage, mitigate, file bug, fix, verify, RCA).
Dashboards: Grafana http://localhost:3001 ("ExpenseFlow overview"). Alerts: Prometheus http://localhost:9090/alerts.
First three checks for any alert: the Grafana panel named in the section, `docker compose ps`, `docker compose logs <service> --tail 100`.

## Service down
**Alert:** `ServiceDown` (SEV1): backend or mock-bank cannot be scraped for 1 minute.
- **Meaning:** the service is stopped, crashing or unreachable; users see errors or nothing.
- **Dashboard:** "Services up".
- **Diagnosis:** `docker compose ps` (state, restarts); `docker compose logs backend --tail 100` (Flyway or datasource errors, port clash); `curl localhost:8080/actuator/health`; for the backend check postgres is `healthy`.
- **Mitigation:** `docker compose up -d <service>`; if a bad deploy: `git revert` the last merged PR and redeploy; if postgres is the cause see its logs and restart it.
- **Verify:** health `UP`, panel green, alert inactive.
- **Escalate:** backend -> L2 (data/startup) or L3 (security config); mock-bank -> L3; compose/Docker -> L6.

## High error rate
**Alert:** `HighErrorRate` (SEV2): more than 5% of API requests return 5xx for 5 minutes (only counted above 0.05 req/s).
- **Meaning:** an endpoint or dependency is failing for real users.
- **Dashboard:** "Error rate (5xx, %)" and "API latency p95".
- **Diagnosis:** `docker compose logs backend --tail 200 | grep -i error`; find the endpoint with `http_server_requests_seconds_count{status=~"5.."}` grouped by `uri` in Prometheus; check postgres health and the bank (next section).
- **Mitigation:** fix or roll back the offending change; if caused by the bank, reset faults (below).
- **Verify:** ratio below 5%, alert resolves after 5 minutes.
- **Escalate:** owner of the failing endpoint (`squad doc roles`); file `squad add --type bug`.

## Bank payout failures
**Alerts:** `BankPayoutFailures` (SEV2: any failed payout submit in 5 minutes, fires after 1 minute) and `BankCallLatencyHigh` (SEV3: bank call p95 above 2 s for 5 minutes).
- **Meaning:** the file reached or tried to reach the mock bank and it answered 5xx, timed out or was slow. The batch is `SUBMIT_FAILED` and no claim is marked Paid (safe to retry; the bank is idempotent on `BATCH_ID`, so nothing is paid twice).
- **Dashboard:** "Bank payout files by outcome", "Bank faults injected", "API latency p95"; counters `payouts_total{outcome}`, `external_call_seconds`, `expenseflow_bank_submit_total{result}`.
- **Diagnosis:** `curl localhost:8081/admin/faults` (is a fault active: `mode` `500`/`timeout`, `latencyMs`?); `docker compose logs mock-bank --tail 100`; `curl localhost:8081/actuator/health`; backend error body names the bank answer (`BANK_UNAVAILABLE`, `BANK_TIMEOUT`).
- **Mitigation:** if a fault is active, reset it: `curl -X DELETE localhost:8081/admin/faults`. If the bank is down: `docker compose up -d mock-bank`. Then retry the batch from Payouts (UI) or `POST /api/payout-batches/{id}/submit` with a new `Idempotency-Key`. If the outcome is unknown after a timeout, reconcile first: `GET localhost:8081/bank/payout-files?batchId=PB-<id>`.
- **Verify:** batch status `PAID`, `payouts_total{outcome="success"}` increased, alert becomes inactive about 5 minutes after the last failure (the rule looks back 5 minutes).
- **Rule note (T-511):** the rule also fires when the failure counter first appears already above 0 (fresh stack, failure before the first scrape); rule unit tests: `infra/prometheus/alerts.test.yml`, run in CI job `prometheus-rules`.
- **Escalate:** payout service -> L2; mock-bank behaviour -> L3.

## Oldest pending approval
**Alert:** `OldestPendingApprovalTooOld` (SEV2): the oldest approval step has waited more than 48 hours (5 minute hold).
- **Meaning:** a claim is stuck with an approver who is absent or overloaded; employees are not being reimbursed.
- **Dashboard:** "Oldest pending approval" and "Approval queue size by level" (`approval_oldest_pending_age_seconds{level}`).
- **Diagnosis:** which level is oldest (MANAGER, AUDITOR, HEAD); open Approvals as that role and sort by age; check whether the approver has a delegation (`/delegations`) or is disabled in Admin > Users.
- **Mitigation:** ask the approver to act, delegate to a backup (demo: Rahul Manager -> Meera Backup), or a finance head approves; for AUDITOR/HEAD queues use bulk approve for low-risk items.
- **Verify:** gauge drops below 48 h, alert resolves.
- **Seed-data note:** the seeded demo history contains approvals pending 4 to 5 days, so on a freshly seeded stack this alert is already firing (verified 2026-11-27: oldest 5.3 days). That is expected and shows the alert works; clear it by approving the oldest items or by seeding newer history. If it should stay green for demos, raise the threshold in `alerts.yml` or seed pending items younger than 48 h.
- **Escalate:** business owner (finance head); approval engine bugs -> L3.

## Approval queue
**Alert:** `ApprovalQueueGrowing` (SEV3): more than 1000 claims waiting for 15 minutes.
- **Meaning:** intake is outpacing decisions.
- **Dashboard:** "Approval queue size by level".
- **Diagnosis:** growth per level; any approver offline; any recent spike in claims submitted.
- **Mitigation:** same as the previous section; consider bulk approve for the auditor queue.
- **Verify:** queue size trending down.
- **Escalate:** finance head.
