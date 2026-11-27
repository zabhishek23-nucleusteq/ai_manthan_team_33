# RCA - incident drill 001: bank payout failures

Planned drill, run live on the compose stack on 2026-11-27 (local time, UTC+5:30). Follows `sre-handbook.md` section 4 and `runbook.md#bank-payout-failures`.

## Summary
The mock bank was made to answer HTTP 500 to every payout file. Three payout submits for batch 11 failed with 502 `BANK_UNAVAILABLE`, the `BankPayoutFailures` alert fired 84 seconds after the first failure, the runbook mitigation (reset faults, retry) took 2 seconds, and batch 11 was `PAID`. The alert cleared 3 min 30 s after recovery (just over 5 minutes after the last failure, because the rule looks back 5 minutes). No money moved while the bank was failing and nothing was paid twice.

## Impact
Drill only, synthetic data. In a real incident: payouts to employees delayed for the duration; claims stayed in their pre-payout state (batch `SUBMIT_FAILED`, claims not Paid), so no data was wrong.

## Timeline (stack clock, HH:MM:SS)
| Time | Event |
|---|---|
| 17:21:06 | Baseline: faults `mode=ok`, `payouts_total{outcome="failure"}` 0, alert `inactive`. Batch 11 `SUBMIT_FAILED` from an earlier attempt. |
| 17:21:07 | **Inject:** `POST mock-bank:8081/admin/faults {"errorRate":1,"latencyMs":0,"mode":"500"}` |
| 17:21:08 - 17:21:10 | Three `POST /api/payout-batches/11/submit` calls return 502 `BANK_UNAVAILABLE` ("Injected fault: bank internal error"). |
| 17:21:31 | **Detect:** alert `BankPayoutFailures` -> `pending` (expression true at the next rule evaluation). |
| 17:22:31 | Alert -> **`firing`** after the 1 minute `for` hold: "3 failed payout submit(s) in the last 5 minutes". Time to detect: 84 s from first failure. |
| 17:22:44 | **Mitigate (runbook):** `DELETE mock-bank:8081/admin/faults`; faults back to `mode=ok`. |
| 17:22:46 | **Recover:** retry `POST /api/payout-batches/11/submit` -> 200, batch `PAID`. `payouts_total{outcome="success"}` = 1. |
| 17:26:16 | Alert -> `inactive` (the rule looks back 5 minutes, so it clears 5 min after the last failure at 17:21:10 plus one evaluation). |

## Root cause
Injected fault: the mock bank's `/admin/faults` was set to `mode=500, errorRate=1`, so every `/bank/**` request failed. In production the equivalent causes are a bank outage or an internal bank error. The backend handled it correctly: it returned 502, set the batch to `SUBMIT_FAILED` (retryable) and incremented `payouts_total{outcome="failure"}` and `expenseflow_bank_submit_total{result="http_5xx"}`.

## Detection
Prometheus alert `BankPayoutFailures` (`increase(payouts_total{outcome="failure"}[5m]) > 0`, hold 1 minute), visible at http://localhost:9090/alerts and as a rise of "Bank faults injected" on the Grafana dashboard. Detection was automatic; nobody had to notice a user complaint.

## Resolution
Reset faults and retry the batch. Idempotent on `BATCH_ID`, so the retry could not double pay.

## What went well
- Alert fired within 1.5 minutes and its description gave the failure count and a runbook link.
- Failure was safe: batch retryable, no partial payment.
- The runbook steps worked as written; recovery took 2 seconds once started.

## What went badly / gaps found
1. **First drill attempt did not fire** (17:15). The running stack had been built before T-606 landed, so `payouts_total` stayed at 0 even though `expenseflow_bank_submit_total` counted the failures. Lesson: alert metrics must be checked on the deployed build, not assumed from the code. Fixed by rebuilding on current `main`.
2. The alert clears only 5 minutes after recovery because of the `[5m]` look-back; during that time it is "firing" although the system is healthy. Acceptable, documented in the runbook; a shorter window would make it noisier.
3. `OldestPendingApprovalTooOld` is already firing on freshly seeded data (oldest approval 5.3 days). It works, but it means the alerts page is never fully green in the demo. Noted in the runbook.
4. Alertmanager is not part of the stack: alerts are visible in Prometheus/Grafana but nobody is notified.

## Action items
| Action | Owner | Status |
|---|---|---|
| Decide whether seed data should keep an approval older than 48 h (demo realism) or the threshold should move | L1 / L2 | open, raise at the final review |
| Add Alertmanager (or Grafana contact point) so alerts notify someone | L6 | open, optional |
| Keep the drill repeatable: add the steps above as a script under `tests/` or `infra/` | L6 | open |

## Evidence
- Prometheus alerts page and `payouts_total` graph, and the Grafana dashboard after the drill (shows the 3 injected faults): [docs/sre/evidence/](evidence/) (`prometheus-alerts.png`, `prometheus-payouts-total.png`, `grafana-dashboard.png`).
- Rule validation: `promtool check rules` -> "SUCCESS: 6 rules found".
- The firing state itself was captured as API output (`/api/v1/alerts`: `state=firing`, `activeAt=2026-11-27T11:51:28Z`, description "3 failed payout submit(s) in the last 5 minutes"); the screenshots were taken after recovery so they show the cleared state and the metric history.
