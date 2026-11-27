# RBAC test matrix

Generated from `tests/e2e/api/rbac.spec.ts`, which encodes `docs/architecture/rbac.md` section 3.
Regenerate: start the stack (`./start.ps1`), then in `tests/e2e`: `npm run test:api; npm run rbac:summary`.

Last run: 2026-11-27 - **63 passed, 0 failed, 0 known bugs, 60 skipped** of 123.

Per endpoint: (1) no token -> 401; (2) an allowed role is not 401/403/5xx; (3) a role without access -> 403.
Checks 2 and 3 are skipped until the endpoint is in `/v3/api-docs` and the seeded demo users (T-013) can log in; skips are not passes.

KNOWN BUG = the backend deviates from rbac.md; the check is expected to fail until the linked bug task is fixed.

| Check | Result | Note |
|---|---|---|
| GET /api/auth/me: no token -> 401 | pass |  |
| GET /api/auth/me: allowed role is not rejected | pass |  |
| GET /api/auth/me: a role without access -> 403 | skipped | no demo user without access can log in yet (seed data, T-013) |
| GET /api/claims: no token -> 401 | pass |  |
| GET /api/claims: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/claims: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims: no token -> 401 | pass |  |
| POST /api/claims: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/claims/{id}: no token -> 401 | pass |  |
| GET /api/claims/{id}: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/claims/{id}: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| PUT /api/claims/{id}: no token -> 401 | pass |  |
| PUT /api/claims/{id}: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| PUT /api/claims/{id}: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| DELETE /api/claims/{id}: no token -> 401 | pass |  |
| DELETE /api/claims/{id}: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| DELETE /api/claims/{id}: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims/{id}/submit: no token -> 401 | pass |  |
| POST /api/claims/{id}/submit: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims/{id}/submit: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/receipts: no token -> 401 | pass |  |
| POST /api/receipts: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/receipts: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/receipts/{id}/content: no token -> 401 | pass |  |
| GET /api/receipts/{id}/content: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/receipts/{id}/content: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/claims/{id}/audit: no token -> 401 | pass |  |
| GET /api/claims/{id}/audit: allowed role is not rejected | pass |  |
| GET /api/claims/{id}/audit: a role without access -> 403 | pass |  |
| GET /api/claims/{id}/comments: no token -> 401 | pass |  |
| GET /api/claims/{id}/comments: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/claims/{id}/comments: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims/{id}/comments: no token -> 401 | pass |  |
| POST /api/claims/{id}/comments: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/claims/{id}/comments: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/approvals/inbox: no token -> 401 | pass |  |
| GET /api/approvals/inbox: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/approvals/inbox: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/approve: no token -> 401 | pass |  |
| POST /api/approvals/{claimId}/approve: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/approve: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/reject: no token -> 401 | pass |  |
| POST /api/approvals/{claimId}/reject: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/reject: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/send-back: no token -> 401 | pass |  |
| POST /api/approvals/{claimId}/send-back: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/approvals/{claimId}/send-back: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/delegations: no token -> 401 | pass |  |
| GET /api/delegations: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/delegations: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/delegations: no token -> 401 | pass |  |
| POST /api/delegations: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/delegations: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| DELETE /api/delegations/{id}: no token -> 401 | pass |  |
| DELETE /api/delegations/{id}: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| DELETE /api/delegations/{id}: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/users/approvers: no token -> 401 | pass |  |
| GET /api/users/approvers: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/users/approvers: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/departments: no token -> 401 | pass |  |
| GET /api/departments: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/departments: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/audit-queue: no token -> 401 | pass |  |
| GET /api/audit-queue: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/audit-queue: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/audit-queue/bulk-approve: no token -> 401 | pass |  |
| POST /api/audit-queue/bulk-approve: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/audit-queue/bulk-approve: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/audit-queue/{claimId}/sample-audit: no token -> 401 | pass |  |
| POST /api/audit-queue/{claimId}/sample-audit: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/audit-queue/{claimId}/sample-audit: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches: no token -> 401 | pass |  |
| GET /api/payout-batches: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches/{id}: no token -> 401 | pass |  |
| GET /api/payout-batches/{id}: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches/{id}: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches/{id}/export.csv: no token -> 401 | pass |  |
| GET /api/payout-batches/{id}/export.csv: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/payout-batches/{id}/export.csv: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/payout-batches: no token -> 401 | pass |  |
| POST /api/payout-batches: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/payout-batches: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/payout-batches/{id}/submit: no token -> 401 | pass |  |
| POST /api/payout-batches/{id}/submit: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/payout-batches/{id}/submit: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/policy-rules: no token -> 401 | pass |  |
| GET /api/policy-rules: allowed role is not rejected | pass |  |
| GET /api/policy-rules: a role without access -> 403 | pass |  |
| PUT /api/policy-rules/{code}: no token -> 401 | pass |  |
| PUT /api/policy-rules/{code}: allowed role is not rejected | pass |  |
| PUT /api/policy-rules/{code}: a role without access -> 403 | pass |  |
| GET /api/dashboard/kpis: no token -> 401 | pass |  |
| GET /api/dashboard/kpis: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/dashboard/kpis: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/telemetry/page-load: no token -> 401 | pass |  |
| POST /api/telemetry/page-load: allowed role is not rejected | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| POST /api/telemetry/page-load: a role without access -> 403 | skipped | endpoint not implemented yet (not in /v3/api-docs) |
| GET /api/admin/users: no token -> 401 | pass |  |
| GET /api/admin/users: allowed role is not rejected | pass |  |
| GET /api/admin/users: a role without access -> 403 | pass |  |
| POST /api/admin/users: no token -> 401 | pass |  |
| POST /api/admin/users: allowed role is not rejected | pass |  |
| POST /api/admin/users: a role without access -> 403 | pass |  |
| PUT /api/admin/users/{id}/roles: no token -> 401 | pass |  |
| PUT /api/admin/users/{id}/roles: allowed role is not rejected | pass |  |
| PUT /api/admin/users/{id}/roles: a role without access -> 403 | pass |  |
| PATCH /api/admin/users/{id}: no token -> 401 | pass |  |
| PATCH /api/admin/users/{id}: allowed role is not rejected | pass |  |
| PATCH /api/admin/users/{id}: a role without access -> 403 | pass |  |
| GET /api/admin/overview: no token -> 401 | pass |  |
| GET /api/admin/overview: allowed role is not rejected | pass |  |
| GET /api/admin/overview: a role without access -> 403 | pass |  |
| GET /api/admin/audit: no token -> 401 | pass |  |
| GET /api/admin/audit: allowed role is not rejected | pass |  |
| GET /api/admin/audit: a role without access -> 403 | pass |  |
| GET /api/admin/health: no token -> 401 | pass |  |
| GET /api/admin/health: allowed role is not rejected | pass |  |
| GET /api/admin/health: a role without access -> 403 | pass |  |
| sign-up yields EMPLOYEE only | pass |  |
| sign-up ignores a requested staff role | pass |  |
| employee reading another employee's claim -> 404 (no enumeration) | skipped | claims API not implemented yet (T-016) |
