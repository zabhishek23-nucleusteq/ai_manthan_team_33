# SRE Handbook - ExpenseFlow

How we run and support ExpenseFlow. When monitoring or a test finds a problem, everyone knows who does what until it is fixed, verified and written up. Owner: L6. Runbooks: [runbook.md](runbook.md).

## 1. Services

| Service | Port | Health | Metrics | Owner |
|---|---|---|---|---|
| backend (Spring Boot) | 8080 | `/actuator/health` | `/actuator/prometheus` | L2 / L3 |
| mock-bank (payout export target, fault injection) | 8081 | `/actuator/health` | `/actuator/prometheus` | L3 |
| frontend (nginx, proxies `/api`) | 3000 | `GET /` returns 200 | - | L4 / L5 |
| postgres 18 | 5432 | `pg_isready` | - | L2 |
| prometheus / grafana | 9090 / 3001 | `/-/healthy` / `/api/health` | - | L6 |

Start: `./start.ps1` or `./start.sh`. Logs: `docker compose logs <svc> --tail 100`.

## 2. What we watch

| Signal | Source | Normal | Alert when | Panel |
|---|---|---|---|---|
| Service up | `up{job=...}`, `/actuator/health` | UP | DOWN 1 min | Overview |
| Request error rate | `http_server_requests_seconds_count{status=~"5.."}` | < 1% | > 5% for 5 min | Overview |
| Request latency p95 (page/API) | `http_server_requests_seconds_bucket` | < 500 ms | > 2 s for 5 min | Overview |
| Bank payout call latency p95 | `external_call_seconds{target="bank"}` | < 500 ms | > 2 s for 5 min | Integrations |
| Payout success rate | `payouts_total{outcome}` | > 95% | < 90% for 5 min | Business |
| **Approval queue size** | `approval_queue_size` (gauge, claims pending per level) | steady | > 200 for 15 min | Business |
| **Oldest pending approval age** | `approval_oldest_pending_age_seconds` (gauge) | < 2 days | > 3 days (72 h) | Business |
| Claims submitted volume | `claims_submitted_total` | steady | 0 for 10 min in working hours | Business |

Metric names above are the agreed targets; L2/L3 expose them (file a task if one is missing).
Thresholds are starting values, validated in the drill and adjusted from real behaviour.

## 3. Severities

| Sev | Meaning | Response |
|---|---|---|
| SEV1 | Main flow down for all users, or money/data wrong (double payout, employee sees another's claim, approval bypassed) | Owning laptop stops feature work and fixes now; L1 informed |
| SEV2 | One persona flow broken, or a KPI alert firing (payout failures, stuck approvals) | Bug task, taken next by owning laptop |
| SEV3 | Cosmetic, or slow but working | Bug task, normal priority |

## 4. Incident flow (detect -> fix -> verify -> learn)

1. **Detect**: Prometheus/Grafana alert, a failed sprint gate, or a test or user report.
2. **Triage (L6, 5 min)**: reproduce, set severity, find the owning laptop (`squad doc roles`).
3. **Mitigate** if possible: reset mock faults (`POST /admin/faults {"mode":"ok"}`), restart the container, or roll back the last PR (`git revert`).
4. **File the bug (SRE -> owning laptop)**:
   `squad add --type bug --owner Lx --milestone <current> --title "<symptom>" --body "Severity / Steps / Expected / Actual / Evidence (panel, logs) / Runbook used"`
   and add a row to `docs/testing/defect-log.md`.
5. **Fix (owner)**: the owner's loop takes bugs first: failing test, fix, PR, CI green, merge, `squad status T-5xx done`.
6. **Verify (L6)**: alert resolved, panel normal, regression test passes; close the defect-log row.
7. **Learn**: SEV1/SEV2 and the planned drill get `docs/sre/rca-<n>.md` (timeline with times, impact, root cause, detection, resolution, went well/badly, action items as tasks).

Ownership map for routing bugs: auth/RBAC and policy engine -> L2/L3 backend, UI -> L4/L5, compose/CI/observability -> L6.

## 5. Runbook index

| Alert | Runbook section | First check |
|---|---|---|
| OldestPendingApprovalTooOld (> 72 h) | `runbook.md#oldest-pending-approval` | Business panel: queue by level; is a manager/auditor missing or on leave (delegation)? |
| ApprovalQueueGrowing | `runbook.md#approval-queue` | Submitted vs decided rate; scheduler or notification job stopped? |
| BankPayoutFailureRate (< 90% success) | `runbook.md#bank-payout-failure` | Integrations panel; `GET mock-bank:8081/admin/faults` |
| BankCallLatencyHigh (p95 > 2 s) | `runbook.md#bank-payout-failure` | Same; check mock fault `latencyMs` |
| ServiceDown / HighErrorRate | `runbook.md#service-down` | `docker compose ps`, `logs backend --tail 100` |

Each runbook entry: meaning, first 3 checks (panel, `docker compose ps`, logs), likely causes, mitigation commands, how to verify recovery, owner of the fix. The detailed text lives in `runbook.md` (written when the alerts are added).

## 6. Planned incident drill (shown in the demo)

Break mock-bank via `/admin/faults` (errorRate 0.5 or latency 3000 ms), watch the payout alert fire, follow this handbook live, recover, write `rca-001.md` with panel screenshots as evidence.
