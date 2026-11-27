# mock-bank

Mock external bank that receives ExpenseFlow payout files. Synthetic data only. Port `8090`.

## API
| Method | Path | Notes |
|---|---|---|
| POST | `/bank/payout-files` | Body `text/csv` (or multipart field `file`). Optional `Idempotency-Key` header: same key + same file -> original result (200, `Idempotent-Replayed: true`); same key + other file -> 422. New file -> 201 `{fileId, accepted, rejected, acceptedAmountMinor, status, errors[], receivedAt}`. Bad header / no rows -> 400. |
| GET | `/bank/payout-files/{fileId}` | Look up / reconcile a file. 404 if unknown. |
| GET/POST/DELETE | `/admin/faults` | Read / set / reset faults. `{"errorRate":0.5,"latencyMs":3000,"mode":"ok\|500\|timeout"}` |
| GET | `/actuator/health`, `/actuator/prometheus` | Never affected by faults. |

CSV format (`amount_minor` in paise, per-line max 100000000):
```
reference,employee_id,payee_name,account_number,ifsc,amount_minor,currency
CLM-1001,EMP-001,Asha Rao,123456789012,HDFC0001234,125050,INR
```
Status: `ACCEPTED` / `PARTIALLY_ACCEPTED` / `REJECTED` (all rows bad).

## Faults
Apply only to `/bank/**`. `latencyMs` is added to every request; with `mode` `500` or `timeout`, a request fails with probability `errorRate` (500 `BANK_UNAVAILABLE`, or hang `MOCKBANK_FAULT_TIMEOUT_MS` then 504 `BANK_TIMEOUT`).
Env defaults: `MOCKBANK_FAULT_MODE`, `MOCKBANK_FAULT_ERROR_RATE`, `MOCKBANK_FAULT_LATENCY_MS`, `MOCKBANK_FAULT_TIMEOUT_MS`.

```
curl -X POST localhost:8090/admin/faults -H 'Content-Type: application/json' -d '{"errorRate":1,"latencyMs":0,"mode":"500"}'
curl -X DELETE localhost:8090/admin/faults
```

Metrics: `mockbank_payout_files_total{outcome}`, `mockbank_payout_lines_total{outcome}`, `mockbank_faults_injected_total{type}`.

## Run
`./mvnw verify` · `docker build -t mock-bank services/mock-bank && docker run -p 8090:8090 mock-bank`
