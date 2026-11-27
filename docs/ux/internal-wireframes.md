# Internal screens - wireframes (L5)

Scope: staff-facing screens of ExpenseFlow - approver (manager / auditor / head), audit queue, payout batches, policy rules, delegation, role dashboards and the super admin area. Employee claim capture is L4 (`docs/ux/` external wireframes).

Sources: `docs/problem/problem-statement.md`, `docs/architecture/rbac.md` (roles, routes, API), team-standards §1 (date filter), §5 (RBAC), §6 (Ant Design), §7 (super admin).
Claim status names below are provisional until the claim state machine ADR lands; the UI maps statuses through one `statusTag()` helper so a rename is one-line.

Status tags used everywhere (colour + text, never colour only):
`DRAFT` default · `SUBMITTED` blue · `PENDING_MANAGER` / `PENDING_AUDITOR` / `PENDING_HEAD` gold · `CLARIFICATION_REQUESTED` orange · `APPROVED` / `AUTO_APPROVED` green · `REJECTED` / `AUTO_REJECTED` red · `IN_PAYOUT` purple · `PAID` cyan.
Policy flags (`Tag` + icon): `OVER_LIMIT`, `NO_RECEIPT`, `DUPLICATE`, `WEEKEND`, `SAMPLE_AUDIT`.

---

## 0. App shell (all staff roles)

```
+--------------------------------------------------------------------------+
| [=] ExpenseFlow            Rahul Manager [MANAGER][EMPLOYEE]  (Logout)   |  Header
+-------------+------------------------------------------------------------+
| My dashboard|  Breadcrumb: Approvals / EXP-2026-00412                    |
| My claims   |                                                            |
| New claim   |  <page content>                                            |
| ----------- |                                                            |
| Team dash   |                                                            |
| Approvals(7)|  <- Badge = pending count from inbox                       |
| Delegation  |                                                            |
+-------------+------------------------------------------------------------+
```
- Ant `Layout` + `Sider` (`breakpoint="lg"`, `collapsedWidth=0` -> Drawer on 360px) + `Menu` built from rbac.md §2 (union of the user's roles).
- Landing route = highest role (SUPER_ADMIN > FINANCE_HEAD > FINANCE_AUDITOR > MANAGER > EMPLOYEE).
- Route guard: route not in the user's menu -> `Result status="403"` "You do not have access to this page" + "Go to my dashboard" button.
- Skip link "Skip to content" as first focusable element; `Menu` is keyboard navigable (arrow keys).

---

## 1. Approver inbox `/approvals` (MANAGER, FINANCE_AUDITOR, FINANCE_HEAD)

API: `GET /api/approvals/inbox` (steps assigned to me, incl. via delegation).

```
Approvals                                          [Delegated to me: 2 (i)]
+--------------------------------------------------------------------------+
| Search claim / employee [__________]  Level [All v]  Flags [Any v]       |
| Age [Any v]   Sort: Oldest first v                         12 pending    |
+--------------------------------------------------------------------------+
| [ ] Claim         Employee       Dept   Amount INR  Flags        Age  Lvl |
| [ ] EXP-..0412    Priya E.       Eng      32,450    OVER_LIMIT   3d   L1  |
| [ ] EXP-..0398    Arjun K.       Sales     1,240    WEEKEND      1d   L1  |
| [ ] EXP-..0377 *D Neha S.        Ops       8,900    -            5h   L1  |
+--------------------------------------------------------------------------+
  *D = delegated from Meera Backup (Tag "Delegated")
```
- Ant `Table` (dense `size="small"`), row click / Enter opens the review screen (§2). Age > 48 h shown red + "Overdue" text.
- Filters kept in the URL. Counts in the header. Default sort: oldest first.
- 360px: `Table` replaced by `List` of `Card`s (claim, employee, amount, flags, age).
- States: loading `Skeleton` table · empty `Empty` "No claims waiting for you" + link to delegation · error `Alert` + Retry · success after action: `message.success("EXP-..0412 approved")` and row removed.

## 2. Claim review with side-by-side receipt viewer `/approvals/:claimId`

APIs: `GET /api/claims/{id}` (approver view), `GET /api/receipts/{id}/content`, `GET/POST /api/claims/{id}/comments`, `GET /api/claims/{id}/audit`, `POST /api/approvals/{claimId}/approve|reject|send-back`.

Desktop (>= 992px): two panes, `Splitter` / `Row` 50/50.
```
+-------------------------------------+------------------------------------+
| EXP-2026-00412  [PENDING_MANAGER]   |  Receipt 2 of 4     [<] [>]        |
| Priya Employee - Engineering        |  +------------------------------+  |
| Total 32,450 INR   Submitted 3d ago |  |                              |  |
| Chain: (o) Manager -> ( ) Auditor   |  |      receipt image / PDF     |  |
|        (>25k) -> ( ) Head (>1L)     |  |      zoom + / -, rotate      |  |
| ----------------------------------- |  |                              |  |
| # Date   Category  Vendor   Amount  |  +------------------------------+  |
| 1 18 Nov Hotel     Taj..    7,200 ! |  Line 2: Hotel, Mumbai (tier-1)    |
|   OVER_LIMIT: 6,000/night tier-1    |  Rule: HOTEL_TIER1 max 6,000/night |
| 2 18 Nov Meals     Cafe..   1,100   |  Overage: 1,200 INR                |
| 3 19 Nov Cab       Uber       640   |                                    |
| 4 19 Nov Meals     -          400 R |  R = no receipt (allowed <= 500)   |
| ----------------------------------- |                                    |
| Comments (2)  |  History (Timeline) |                                    |
| ----------------------------------- |                                    |
| Comment [______________________]    |                                    |
| [Approve (A)] [Send back (S)] [Reject (R)]                               |
+-------------------------------------+------------------------------------+
```
- Selecting a line (click / Up-Down arrow) shows its receipt on the right; `[`/`]` move between receipts.
- Every flag shows the rule applied and the numbers (limit, actual, overage), from the policy engine result.
- Duplicate flag links to the earlier line ("Same vendor + amount + date as EXP-..0201 line 3").
- Chain uses Ant `Steps` (current level highlighted; skipped levels shown "not required (<= 25,000)").
- Actions:
  - **Approve** - `Popconfirm` "Approve 32,450 INR? Next: Finance auditor (amount > 25,000)." Comment optional.
  - **Send back** - `Modal` with required comment ("What needs clarification?"), status -> `CLARIFICATION_REQUESTED`.
  - **Reject** - `Modal` with required reason (Select of common reasons + text).
  - Buttons disabled with tooltip when the user is the claimant or not the current assignee (backend also refuses).
- Keyboard: `A` / `S` / `R` open the action dialogs (only when focus is not in a text field), Enter confirms, Esc cancels, `J`/`K` next/previous claim in inbox order (stopwatch flow: open -> check receipt -> A -> Enter -> next claim).
- 360px: single column, receipt opens in a `Drawer` ("View receipt" button on each line); actions in a sticky bottom bar.
- States: loading `Skeleton`; receipt loading `Spin`; receipt missing `Empty` "No receipt (not required <= 500 INR)" or "Receipt missing" warning; 404 -> `Result` "Claim not found or not assigned to you"; 409 (already actioned by someone else) -> `Alert` "This claim was already handled" + back to inbox.

## 3. Audit queue `/audit-queue` (FINANCE_AUDITOR, FINANCE_HEAD)

APIs: `GET /api/audit-queue`, `POST /api/audit-queue/bulk-approve`, `POST /api/audit-queue/{claimId}/sample-audit`.

```
Audit queue                     [Pending L2: 23] [Sample audit: 9] [Flagged: 14]
+--------------------------------------------------------------------------+
| Dept [All v] Category [All v] Amount [min]-[max] Flags [Any v]           |
| Submitted [RangePicker]  Source: (o) All ( ) Approval ( ) Sample audit   |
+--------------------------------------------------------------------------+
| 3 selected - total 61,200 INR   [Bulk approve (Shift+A)] [Clear]         |
+--------------------------------------------------------------------------+
| [x] Claim       Employee  Dept  Amount   Flags          Reason in queue  |
| [x] EXP-..0412  Priya E.  Eng   32,450   OVER_LIMIT     > 25,000         |
| [x] EXP-..0350  Arjun K.  Sales 27,100   -              > 25,000         |
| [ ] EXP-..0290  Neha S.   Ops    4,300   SAMPLE_AUDIT   random 10%       |
+--------------------------------------------------------------------------+
```
- Ant `Table` with `rowSelection`, filters/sort in URL, counts as `Tag`s / `Segmented` tabs.
- **Bulk approve** only selects rows without blocking flags (`DUPLICATE`, `NO_RECEIPT` > 500 are not selectable, checkbox disabled with tooltip "Review individually: duplicate"). Confirm `Modal`: "Approve 3 claims, total 61,200 INR? 1 will move to Finance head (> 1,00,000)." Result: per-claim outcome list (approved / failed + reason), partial failure shown as `Alert type="warning"`.
- **Sample audit** rows (`SAMPLE_AUDIT` tag, random 10% of auto-approved claims): open the same review screen (§2) with actions "Audit OK" / "Raise issue" (send back).
- Auditor cannot see/act on own claims (filtered out by backend; UI shows nothing extra).
- Keyboard flow for bulk approve: `/` focuses search, Up/Down moves row focus, `Space` toggles selection, `Shift+A` opens bulk approve, Enter confirms, Esc cancels. Selection count is announced via `aria-live="polite"`.
- 360px: card list with checkbox per card; bulk bar sticky at the bottom.
- States: loading / empty ("Queue is clear") / error + Retry / success `message.success("3 claims approved")`.

## 4. Payout batches `/payouts` (FINANCE_HEAD create/submit; FINANCE_AUDITOR read + export)

APIs: `GET/POST /api/payout-batches`, `GET /api/payout-batches/{id}`, `POST /api/payout-batches/{id}/submit`, `GET /api/payout-batches/{id}/export.csv`.

List:
```
Payout batches                                         [+ New batch]
| Batch      Created    Claims  Total INR    Status      Actions          |
| PB-0012    27 Nov     42      3,18,400     DRAFT       Open             |
| PB-0011    20 Nov     57      4,02,950     EXPORTED    CSV | Mark paid  |
| PB-0010    13 Nov     61      4,77,100     PAID        CSV              |
```
New batch / detail:
```
New payout batch
Approved, not yet in a batch: 42 claims, 3,18,400 INR   Approved up to [date]
| [x] Claim  Employee  Bank a/c (masked)  Amount  Approved on |
...
[Create batch]  ->  Batch PB-0012 (DRAFT)  [Export CSV] [Submit / Mark paid]
```
- `Steps`: Draft -> Exported (CSV downloaded) -> Paid. Claims move to `PAID` on submit.
- Submit confirm: "Mark 42 claims (3,18,400 INR) as Paid? This cannot be undone."
- CSV preview: first 5 rows in a `Table` with the mock bank-file columns before download.
- States: loading, empty ("No approved claims waiting for payout"), error, success (`Result status="success"` after submit with link to batch).

## 5. Policy rules `/policy-rules` (FINANCE_HEAD edit; MANAGER, AUDITOR, ADMIN read)

APIs: `GET /api/policy-rules`, `PUT /api/policy-rules/{code}`.
```
| Code          Rule                         Limit       Outcome    Edit |
| MEALS_DAY     Meals per day                1,500 INR   Route      [e]  |
| HOTEL_TIER1   Hotel / night tier-1         6,000 INR   Route      [e]  |
| HOTEL_OTHER   Hotel / night other cities   4,000 INR   Route      [e]  |
| CAB_TRIP      Local cab per trip           2,000 INR   Route      [e]  |
| RECEIPT_MIN   Receipt mandatory above        500 INR   Reject     [e]  |
| DUPLICATE     Same vendor+amount+date/hash   -         Flag       [e]  |
| WEEKEND       Weekend spend                  -         Flag mgr   [e]  |
```
- Edit in a `Drawer` form (number input with INR prefix, outcome select, required change note). Confirm shows old -> new value. Changes are audited; "Last changed by Farhan Head, 26 Nov" under each row.

## 6. Delegation `/delegations` (MANAGER, AUDITOR, HEAD)

APIs: `GET/POST /api/delegations`, `DELETE /api/delegations/{id}`, `GET /api/users/approvers`.
- Form: Delegate to (`Select` with search over approvers, excludes self), From / To (`RangePicker`, from >= today), reason. Enter submits.
- Table of my delegations (active / upcoming / past) with "End now" (`Popconfirm`), and "Delegated to me" table.
- Banner on the inbox while a delegation from me is active: "Your approvals go to Meera Backup until 30 Nov".

---

## 7. Dashboards (common pattern)

Every dashboard page has the same filter bar (team-standards §1):
```
[ 1 Day | 7 Days | 1 Month | Custom ]  [RangePicker 20 Nov 2026 - 27 Nov 2026]
Last 7 days: 20 Nov - 27 Nov                               (Refresh)
```
- `DatePicker.RangePicker` with `presets` (1 Day, 7 Days, 1 Month) + free custom range; default 7 Days; URL `?range=7d` or `?from=&to=`; one range drives all widgets; sent as `from` (inclusive) / `to` (exclusive). Max 366 days (picker disables beyond).
- KPI = `Card` + `Statistic` with an `(i)` `Tooltip` holding the definition below. Charts via `@ant-design/charts` with a colour-blind safe palette, values labelled, and a "Show as table" toggle.
- Empty range -> `Empty` "No data between 20 Nov and 27 Nov" + [Widen to 1 Month].
- Loading: `Skeleton` per card; error: `Alert` + Retry per widget (one failing widget does not blank the page).
- 360px: KPIs stack 1 per row (`Col xs=24 sm=12 lg=6`), charts full width.
- Keyboard: presets are a `Segmented` control (arrow keys), RangePicker reachable by Tab.

API: `GET /api/dashboard/kpis?from&to` (scope by role: EMP own, MGR direct reports, AUD/HEAD all); admin: `GET /api/admin/overview?from&to`.

### 7.1 Employee - My dashboard `/me/dashboard` (L4 builds screen, KPIs listed for completeness)
| KPI | Definition |
|---|---|
| My spend | Sum of line amounts of my claims submitted in range, excluding DRAFT and REJECTED |
| Pending | Count of my claims currently in any PENDING_* or CLARIFICATION_REQUESTED status |
| Reimbursed | Sum of my claims that moved to PAID in range |
| Avg time to approval | Mean hours from submit to final approval for my claims approved in range |

### 7.2 Manager - Team dashboard `/manager/dashboard`
```
[filter bar]
[Waiting for me: 7] [Oldest waiting: 3d 4h] [Team spend: 4,12,300] [Violations: 18]
[Team spend by category - bar]        [Team spend by month - line]
[Approval time trend - line]          [Top violators (table: employee, flags)]
[My inbox preview: 5 oldest -> Open approvals]
```
| KPI | Definition |
|---|---|
| Waiting for me | Count of approval steps currently assigned to me (incl. delegated), not range-filtered (live) |
| Oldest waiting | Age of the oldest step assigned to me (now - assigned_at) |
| Team spend | Sum of line amounts of claims by my direct reports submitted in range, excluding DRAFT / REJECTED |
| Policy violations | Count of claim lines by my direct reports with at least one policy flag, submitted in range |
| Avg approval time (mine) | Mean hours from step assigned to my decision, for steps I decided in range |

### 7.3 Finance dashboard `/finance/dashboard` (AUDITOR, HEAD)
```
[filter bar]
[Total spend] [Claims submitted] [Auto-approved %] [Violations] [Avg approval time] [Awaiting payout]
[Spend by category - bar]   [Spend by department - bar]
[Spend by month - column]   [Violations by rule - bar]
[Approval time by level (L1/L2/L3) - bar]  [Audit queue: L2 pending / sample audits -> Open]
```
| KPI | Definition |
|---|---|
| Total spend | Sum of line amounts of claims submitted in range, excluding DRAFT / REJECTED / AUTO_REJECTED |
| Claims submitted | Count of claims with submit time in range |
| Auto-approved % | AUTO_APPROVED claims / claims decided by the policy engine in range |
| Policy violations | Count of claim lines with at least one flag (OVER_LIMIT, NO_RECEIPT, DUPLICATE, WEEKEND) submitted in range; split by rule in the chart |
| Duplicates caught | Count of lines flagged DUPLICATE in range |
| Avg approval time | Mean hours from submit to final decision (APPROVED / REJECTED) for claims decided in range; per level in the chart |
| Awaiting payout | Count and sum of APPROVED claims not in a payout batch (live) |
| Paid | Sum of claims moved to PAID in range |
| Spend by department / category / month | Total spend grouped by the claimant's department / line category / submit month (bucket: 1D -> hour, 7D/1M -> day, longer -> week) |

---

## 8. Super admin `/admin` (SUPER_ADMIN only)

Sub-menu: Overview `/admin`, Users `/admin/users`, Audit log `/admin/audit`, System health `/admin/health`.

### 8.1 System overview `/admin` (with the §7 filter bar)
```
[Users: 156 (active 152)] [Claims submitted] [Total spend] [Pending approvals] [Oldest pending] [Paid]
[Claims by status - bar]  [Spend by department - bar]
[Logins / sign-ups per day - line]  [Policy violations by rule]
```
| KPI | Definition |
|---|---|
| Users | Count of user accounts; "active" = enabled |
| Claims submitted | Count of claims with submit time in range (all employees) |
| Total spend | As finance dashboard |
| Pending approvals | Count of open approval steps across all levels (live) |
| Oldest pending | Age of the oldest open approval step (live) - same metric as the SRE alert |
| Paid | Sum of claims moved to PAID in range |
| Claims by status | Count of claims per current status, for claims submitted in range |

### 8.2 Users `/admin/users`
```
Users                                                  [+ Create staff user]
Search name/email [________]  Role [All v]  Dept [All v]  Status [All v]
| Name           Email                      Dept   Roles               Status    Actions        |
| Farhan Head    head@expenseflow.test      Fin    HEAD ADMIN EMP      Enabled   Roles | Disable |
| Rahul Manager  manager@expenseflow.test   Eng    MANAGER EMP         Enabled   Roles | Disable |
```
- Create staff user `Modal` form: name, email, department, manager (`Select`), roles (`Checkbox.Group`, EMPLOYEE always on), temporary password (generated, copy button). Enter submits; field errors from backend `details` shown inline.
- Roles `Modal`: checkbox per role; my own SUPER_ADMIN checkbox disabled with tooltip "You cannot remove your own admin role" (backend 409 `CANNOT_REMOVE_OWN_ADMIN` also mapped to a message).
- Enable/Disable via `Popconfirm` ("Disable Rahul Manager? They will not be able to log in. 7 pending approvals will need a delegate."). Cannot disable self.
- Pagination server-side; 360px card view.

### 8.3 Audit log `/admin/audit`
```
Actor [Select] Action [Select: CLAIM_SUBMITTED, APPROVED, REJECTED, SENT_BACK, DELEGATED,
       BULK_APPROVED, SAMPLE_AUDIT_FLAGGED, PAYOUT_CREATED, PAID, POLICY_CHANGED, ROLE_CHANGED, USER_DISABLED, LOGIN]
Entity [claim id / user]  When [RangePicker presets]
| Time (IST)        Actor          Action        Entity        Details (expand)        |
| 27 Nov 10:42:13   Rahul Manager  APPROVED      EXP-..0412    level 1, comment "ok"   |
```
- Read-only (append-only trail); expandable row shows before/after JSON. Export current filter to CSV.

### 8.4 System health `/admin/health`
```
| Service           Status   Detail                    |
| API (backend)     UP       actuator /health          |
| Database          UP       PostgreSQL, 4 ms          |
| Bank mock         DOWN !   last OK 10:31             |
[Error rate (5xx %) last 1 h]  [p95 latency]  [Approval queue size]  [Oldest pending approval age]
Links: Grafana dashboard, runbook (docs/sre/sre-handbook.md)
```
- Status as `Badge status="success|error|warning"` + text. Auto-refresh every 30 s (toggle), "Last checked 10:42:13".

---

## 9. Keyboard and accessibility checklist (all internal screens)
- Every form: labels, autofocus on the first field, Enter submits, Esc closes modals, errors linked to fields.
- Tables: focusable rows, Enter opens, Space selects, visible focus ring (Ant token `controlOutline`).
- Single-key shortcuts (A/S/R/J/K, Shift+A, /) listed in a `?` help `Modal`; disabled while typing in inputs.
- Money always `en-IN` format with "INR"; dates `DD MMM YYYY` + IST tooltip.
- 360px checked for every screen (Drawer menu, card lists, sticky action bars).

## 10. Playwright coverage planned (L5/L6)
1. Each role logs in and lands on its dashboard (rbac.md §2).
2. Forbidden URL (e.g. manager -> `/payouts`) shows 403.
3. Dashboard preset switching 1 Day / 7 Days / 1 Month / Custom updates URL and widgets.
4. Manager approves a claim from inbox with keyboard only (A, Enter).
5. Auditor bulk-approves 3 claims with keyboard (Space, Shift+A, Enter).
6. Head creates a payout batch, exports CSV, marks paid.
7. Super admin creates a staff user, assigns MANAGER, cannot remove own SUPER_ADMIN.
