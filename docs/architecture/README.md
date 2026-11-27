# Architecture - ExpenseFlow

Related: [requirements](../requirements.md) · [rbac](rbac.md) · [failure modes](failure-modes.md) · [ADR-001 approval chain](../adr/ADR-001-approval-chain-model.md) · [ADR-002 policy as configuration](../adr/ADR-002-policy-as-configuration.md) · [contract](../../contracts/openapi.yaml)

## 1. Context
```mermaid
flowchart LR
  E[Employee] -->|capture + submit claims| EF[ExpenseFlow]
  M[Line manager] -->|approve / send back / delegate| EF
  A[Finance auditor] -->|review queue, sample audit| EF
  H[Finance head / admin] -->|final approval, payouts, policy rules, users| EF
  EF -->|payout CSV bank-file| BANK[(Mock bank service)]
  SRE[SRE / on-call] -->|dashboards, alerts| OBS[Prometheus + Grafana]
  OBS -->|scrape| EF
  OBS -->|scrape| BANK
```

## 2. Containers
```mermaid
flowchart LR
  subgraph Browser
    FE[React 19 + Ant Design SPA<br/>nginx :3000]
  end
  FE -->|/api JSON + JWT| BE[Backend Spring Boot 4.1 :8080<br/>security, claims, policy, approval,<br/>audit-queue, payout, dashboard, admin, audit]
  BE -->|JPA / Flyway| DB[(PostgreSQL 18 :5432)]
  BE -->|POST /bank/payout-files CSV| MB[mock-bank Spring Boot :8081<br/>fault injection /admin/faults]
  PR[Prometheus :9090<br/>alerts.yml] -->|/actuator/prometheus| BE
  PR --> MB
  GR[Grafana :3001] --> PR
```

## 3. Claim state machine
```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> AUTO_REJECTED: submit, REJECT violation
  DRAFT --> APPROVED: submit, clean and total <= 5000
  DRAFT --> PENDING_MANAGER: submit, routed
  PENDING_MANAGER --> PENDING_AUDITOR: approve, total > 25000
  PENDING_MANAGER --> APPROVED: approve, total <= 25000
  PENDING_AUDITOR --> PENDING_HEAD: approve, total > 100000
  PENDING_AUDITOR --> APPROVED: approve, total <= 100000
  PENDING_HEAD --> APPROVED: approve
  PENDING_MANAGER --> REJECTED: reject
  PENDING_AUDITOR --> REJECTED: reject
  PENDING_HEAD --> REJECTED: reject
  PENDING_MANAGER --> CLARIFICATION_REQUESTED: send back
  PENDING_AUDITOR --> CLARIFICATION_REQUESTED: send back
  PENDING_HEAD --> CLARIFICATION_REQUESTED: send back
  CLARIFICATION_REQUESTED --> PENDING_MANAGER: resubmit, routed
  CLARIFICATION_REQUESTED --> AUTO_REJECTED: resubmit, REJECT violation
  APPROVED --> REJECTED: sample audit EXCEPTION
  APPROVED --> IN_PAYOUT: added to batch
  IN_PAYOUT --> PAID: bank accepted
  IN_PAYOUT --> APPROVED: batch cancelled after failure
  AUTO_REJECTED --> [*]
  REJECTED --> [*]
  PAID --> [*]
```
Note: a resubmitted claim that is clean and <= 5,000 is auto-approved like a first submit.

## 4. Data model
```mermaid
erDiagram
  DEPARTMENTS ||--o{ USERS : has
  USERS ||--o{ USERS : "manager_id"
  USERS }o--o{ ROLES : user_roles
  USERS ||--o{ CLAIMS : files
  CLAIMS ||--|{ CLAIM_LINES : contains
  RECEIPTS ||--o| CLAIM_LINES : "receipt_id"
  CLAIMS ||--o{ APPROVAL_STEPS : "chain"
  CLAIMS ||--o{ CLAIM_COMMENTS : has
  USERS ||--o{ DELEGATIONS : "delegator / delegate"
  PAYOUT_BATCHES ||--|{ PAYOUT_ITEMS : contains
  CLAIMS ||--o| PAYOUT_ITEMS : "unique claim_id"
  POLICY_RULES
  AUDIT_EVENTS
  IDEMPOTENCY_KEYS
```
Key columns: `claims(id, code, employee_id, title, status, total_amount, policy_outcome, policy_result jsonb, sample_audit NONE|PENDING|OK|EXCEPTION, submitted_at, decided_at, version)`, `claim_lines(id, claim_id, category MEAL|HOTEL|CAB|TRAVEL|OTHER, amount, expense_date, vendor, description, city, nights, receipt_id, violations jsonb)`, `receipts(id, owner_id, file_name, content_type, size, sha256, content bytea)`, `approval_steps(id, claim_id, level MANAGER|AUDITOR|HEAD, assignee_id, acted_by_id, on_behalf_of_id, decision, comment, created_at, decided_at)`, `delegations(id, delegator_id, delegate_id, from_date, to_date, active)`, `policy_rules(code, category, limit_amount, unit, params jsonb, action, enabled, updated_by, updated_at)`, `payout_batches(id, status CREATED|SUBMITTED|PAID|SUBMIT_FAILED|CANCELLED, total, item_count, bank_reference, value_date, version)`, `audit_events(id, at, actor_id, on_behalf_of_id, action, entity_type, entity_id, before jsonb, after jsonb)` append-only, `idempotency_keys(key, user_id, request_hash, status, response jsonb, created_at)`.

## 5. Backend package layout (`com.expenseflow`) and owner
| Package | Owner | Content |
|---|---|---|
| `config` | L2 | app config, OpenAPI, Jackson |
| `domain`, `repository` | L2 | entities (Claim, ClaimLine, Receipt, ApprovalStep, Delegation, PolicyRule, PayoutBatch, Department), repositories |
| `api` | L2 | ClaimController, ReceiptController, PolicyRuleController, DepartmentController, PayoutController + global error handler |
| `policy` | L2 | PolicyEngine (pure, rule-per-class, reads `policy_rules`) |
| `payout` | L2 | batch service + CSV writer |
| `seed` | L2 | Datafaker generator, profile `seed` |
| `security` | L3 | Spring Security, JWT, AuthController, users/roles |
| `approval` | L3 | ApprovalService (chain resolver, delegation), ApprovalController, DelegationController, AuditQueueController |
| `audit`, `idempotency` | L3 | AuditService.record(...), Idempotency filter/aspect |
| `integration` | L3 | MockBankClient (timeouts, metrics) |
| `admin`, `dashboard`, `observability` | L3 | admin APIs, KPI queries, custom gauges, page-load telemetry |

## 6. Cross-cutting decisions
- Errors: `@RestControllerAdvice` -> `{code, message, details}`; codes listed in the contract.
- Idempotency: header required on state-changing POSTs (NFR-IDEM).
- Concurrency: `@Version` optimistic locking + state checks.
- Audit: `AuditService.record(action, entity, before, after)` called in the same transaction as the change.
- Time: all timestamps `timestamptz`, UTC in API, UI shows local (Asia/Kolkata).
