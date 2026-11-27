# mock-bank

Mock external bank that receives ExpenseFlow payout files. Synthetic data only. Port `8081`.

## API
| Method | Path | Notes |
|---|---|---|
| POST | `/bank/payout-files` | Body `text/csv` (or multipart field `file`) in BR-17 format. New batch -> 201 `{fileId, batchId, valueDate, accepted, rejected, acceptedAmount, status, errors[{line, reference, reason}], receivedAt}`. Idempotent on `BATCH_ID`: same batch + same file -> original result (200, `Idempotent-Replayed: true`, never paid twice); same batch + other content -> 422 `IDEMPOTENCY_KEY_REUSED`. Optional `Idempotency-Key` header is bound to the first batch it was sent with (other batch -> 422). File-level problems -> 400 `INVALID_FILE`. |
| GET | `/bank/payout-files?batchId=PB-7` | Reconcile a batch whose submit outcome is unknown (e.g. after a timeout). 404 if the bank never got it. |
| GET | `/bank/payout-files/{fileId}` | Look up a file. 404 if unknown. |
| GET/POST/DELETE | `/admin/faults` | Read / set / reset faults. `{"errorRate":0.5,"latencyMs":3000,"mode":"ok\|500\|timeout"}` |
| GET | `/actuator/health`, `/actuator/prometheus` | Never affected by faults. |

File format = BR-17 (docs/requirements.md): UTF-8 CSV, header, one `D` row per claim, `T` trailer last.
```
RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION
D,PB-7,2026-11-27,EMP0001,Asha Rao,123456789012,HDFC0001234,1250.50,INR,DEMO-1001
T,PB-7,2026-11-27,1,1250.50
```
- **400 (whole file):** wrong header, no `D` rows, missing/misplaced trailer, unknown RECORD_TYPE, mixed BATCH_ID/VALUE_DATE, VALUE_DATE not `yyyy-MM-dd`, trailer batch/date/count/total mismatch (total = sum of all `D` amounts).
- **Row rejected (rest accepted):** wrong column count, empty/duplicate NARRATION, empty EMPLOYEE_CODE/BENEFICIARY_NAME, ACCOUNT_NO not 6-18 digits, bad IFSC, AMOUNT not exactly 2 decimals, <= 0 or > 1000000.00, CURRENCY not `INR`.

Status: `ACCEPTED` / `PARTIALLY_ACCEPTED` / `REJECTED` (all rows bad).

## Faults
Apply only to `/bank/**`. `latencyMs` is added to every request; with `mode` `500` or `timeout`, a request fails with probability `errorRate` (500 `BANK_UNAVAILABLE`, or hang `MOCKBANK_FAULT_TIMEOUT_MS` then 504 `BANK_TIMEOUT`).
Env defaults: `MOCKBANK_FAULT_MODE`, `MOCKBANK_FAULT_ERROR_RATE`, `MOCKBANK_FAULT_LATENCY_MS`, `MOCKBANK_FAULT_TIMEOUT_MS`.

```
curl -X POST localhost:8081/admin/faults -H 'Content-Type: application/json' -d '{"errorRate":1,"latencyMs":0,"mode":"500"}'
curl -X DELETE localhost:8081/admin/faults
```

Metrics: `mockbank_payout_files_total{outcome}`, `mockbank_payout_lines_total{outcome}`, `mockbank_faults_injected_total{type}`.

## Run
`./mvnw verify` · `docker build -t mock-bank services/mock-bank && docker run -p 8081:8081 mock-bank`
