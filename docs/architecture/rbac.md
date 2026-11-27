# RBAC - ExpenseFlow (source of truth for roles)

Backend is the authority (`@PreAuthorize`). Frontend hides menus and guards routes from this file. QA (L6) generates the 401/403 matrix tests from the API table below.

## 1. Roles
| Role | Persona | Self sign-up | Notes |
|---|---|---|---|
| `EMPLOYEE` | Employee | **Yes** (only role `POST /api/auth/signup` assigns) | Every staff user also has `EMPLOYEE` (managers and finance file their own claims) |
| `MANAGER` | Line manager | No (admin assigns) | Approves claims of direct reports (`users.manager_id`) and claims delegated to them |
| `FINANCE_AUDITOR` | Finance auditor | No | Level-2 approver (> 25,000 INR), works the audit queue, sample audits |
| `FINANCE_HEAD` | Finance head | No | Level-3 approver (> 1,00,000 INR), payout batches, edits policy rules. The demo finance head also holds `SUPER_ADMIN` ("finance head is also admin") |
| `SUPER_ADMIN` | System admin | No | Users/roles, system overview, audit log, health. Cannot remove own `SUPER_ADMIN` |

Spring authorities are `ROLE_<name>`; JWT claim `roles` holds the names without prefix.

## 2. Landing dashboard and menu per role
A user with several roles sees the union of menus; landing = highest role in order SUPER_ADMIN > FINANCE_HEAD > FINANCE_AUDITOR > MANAGER > EMPLOYEE.

| Role | Landing route | Menu items (route) |
|---|---|---|
| EMPLOYEE | `/me/dashboard` | My dashboard `/me/dashboard`, My claims `/claims`, New claim `/claims/new` |
| MANAGER | `/manager/dashboard` | + Team dashboard `/manager/dashboard`, Approvals `/approvals`, Delegation `/delegations` |
| FINANCE_AUDITOR | `/finance/dashboard` | + Finance dashboard `/finance/dashboard`, Approvals `/approvals`, Audit queue `/audit-queue`, Delegation `/delegations` |
| FINANCE_HEAD | `/finance/dashboard` | + Finance dashboard, Approvals, Audit queue, Payouts `/payouts`, Policy rules `/policy-rules`, Delegation |
| SUPER_ADMIN | `/admin` | Admin overview `/admin`, Users `/admin/users`, Audit log `/admin/audit`, System health `/admin/health` |

Direct URL to a route not allowed -> Ant `Result status="403"`.

## 3. API matrix (method + path -> allowed roles)
Everything under `/api/**` except `/api/auth/signup` and `/api/auth/login` needs a valid JWT (401 `UNAUTHENTICATED`). Wrong role -> 403 `FORBIDDEN`. Row-level rules are enforced in the service (column "Row rule").

| Method + path | EMP | MGR | AUD | HEAD | ADMIN | Row rule |
|---|---|---|---|---|---|---|
| `POST /api/auth/signup`, `POST /api/auth/login` | public | public | public | public | public | signup -> EMPLOYEE only |
| `GET /api/auth/me` | Y | Y | Y | Y | Y | self |
| `GET /api/claims`, `POST /api/claims` | Y | Y | Y | Y | - | own claims only |
| `GET/PUT/DELETE /api/claims/{id}` | Y | Y | Y | Y | - | owner only (others get **404**, no enumeration); PUT/DELETE only in DRAFT / CLARIFICATION_REQUESTED |
| `POST /api/claims/{id}/submit` | Y | Y | Y | Y | - | owner; `Idempotency-Key` required |
| `POST /api/receipts` | Y | Y | Y | Y | - | uploader owns it |
| `GET /api/receipts/{id}/content` | Y | Y | Y | Y | - | uploader, any approver assigned (now or before) to the claim, AUDITOR, HEAD |
| `GET /api/claims/{id}/audit`, `GET/POST /api/claims/{id}/comments` | Y | Y | Y | Y | - | owner, assigned approvers, AUDITOR, HEAD |
| `GET /api/approvals/inbox` | - | Y | Y | Y | - | steps assigned to me (incl. via delegation) |
| `POST /api/approvals/{claimId}/approve` `/reject` `/send-back` | - | Y | Y | Y | - | only the currently assigned approver; never the claimant |
| `GET/POST /api/delegations`, `DELETE /api/delegations/{id}` | - | Y | Y | Y | - | own delegations |
| `GET /api/users/approvers` | - | Y | Y | Y | Y | delegate picker |
| `GET /api/departments` | Y | Y | Y | Y | Y | - |
| `GET /api/audit-queue`, `POST /api/audit-queue/bulk-approve`, `POST /api/audit-queue/{claimId}/sample-audit` | - | - | Y | Y | - | not own claims |
| `GET /api/payout-batches`, `GET /api/payout-batches/{id}`, `GET /api/payout-batches/{id}/export.csv` | - | - | Y | Y | - | - |
| `POST /api/payout-batches`, `POST /api/payout-batches/{id}/submit` | - | - | - | Y | - | - |
| `GET /api/policy-rules` | - | Y | Y | Y | Y | - |
| `PUT /api/policy-rules/{code}` | - | - | - | Y | - | audited |
| `GET /api/dashboard/kpis?from&to` | Y | Y | Y | Y | Y | EMP: own; MGR: direct reports; AUD/HEAD/ADMIN: all |
| `POST /api/telemetry/page-load` | Y | Y | Y | Y | Y | - |
| `GET/POST /api/admin/users`, `PUT /api/admin/users/{id}/roles`, `PATCH /api/admin/users/{id}` | - | - | - | - | Y | cannot remove own SUPER_ADMIN (409 `CANNOT_REMOVE_OWN_ADMIN`) |
| `GET /api/admin/overview?from&to`, `GET /api/admin/audit`, `GET /api/admin/health` | - | - | - | - | Y | - |

## 4. Demo logins (seeded, password for all: `Demo@123`)
| Role(s) | Email | Name | Notes |
|---|---|---|---|
| EMPLOYEE | `employee@expenseflow.test` | Priya Employee | reports to manager below; owns DEMO-1001..DEMO-1010 |
| EMPLOYEE + MANAGER | `manager@expenseflow.test` | Rahul Manager | Engineering; has pending approvals |
| EMPLOYEE + FINANCE_AUDITOR | `auditor@expenseflow.test` | Anita Auditor | audit queue with sample audits |
| EMPLOYEE + FINANCE_HEAD + SUPER_ADMIN | `head@expenseflow.test` | Farhan Head | payouts, policy rules, admin |
| SUPER_ADMIN | `admin@expenseflow.test` | Abhishek Admin | admin only |
| EMPLOYEE + MANAGER | `manager2@expenseflow.test` | Meera Backup | delegate target for leave demo |
