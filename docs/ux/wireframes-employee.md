# Employee wireframes - claim capture (mobile first)

Scope: everything the EMPLOYEE role does (rbac.md: `/me/dashboard`, `/claims`, `/claims/new`, `/claims/:id`). Staff users also hold EMPLOYEE, so these screens appear for every logged-in user. Internal (approver / auditor / payout / admin) screens: `docs/ux/internal-wireframes.md`. Colours, status and flag tags: `docs/ux/design-tokens.md`.

Designed at **360 px first**, then widened. Ant Design components named in each section.
APIs (rbac.md §3): `GET/POST /api/claims`, `GET/PUT/DELETE /api/claims/{id}`, `POST /api/claims/{id}/submit` (`Idempotency-Key`), `POST /api/receipts`, `GET /api/receipts/{id}/content`, `GET/POST /api/claims/{id}/comments`, `GET /api/claims/{id}/audit`, `GET /api/dashboard/kpis?from&to`.

---

## 1. My claims `/claims`

360 px:
```
+------------------------------------+
| [=] ExpenseFlow          (Priya v) |
+------------------------------------+
| My claims                [+ New]   |
| [All|Draft|Pending|Needs reply|..] |  <- Segmented / Tabs with counts
| Search [________________]          |
| +--------------------------------+ |
| | EXP-2026-00412   [Pending mgr] | |
| | Client visit Mumbai            | |
| | 32,450 INR - 4 lines - 18 Nov  | |
| | ! 1 flag: Over limit           | |
| +--------------------------------+ |
| | EXP-2026-00398  [Needs reply]  | |  <- orange, pinned on top
| | "Please attach hotel bill"     | |
| +--------------------------------+ |
| | DRAFT (no number) [Draft]      | |
| | 3 lines - 2,140 INR - edited 1h| |
| +--------------------------------+ |
|            [Load more]             |
+------------------------------------+
```
Desktop (>= 992 px): `Table` with columns Claim, Title, Submitted, Lines, Amount INR (right), Status, Flags, Last update; sortable, filters in URL.

- Claims needing my reply (`CLARIFICATION_REQUESTED`) sorted first and shown with the approver's question.
- Tap / Enter on a card -> detail (§4); draft -> editor (§2).
- Only own claims (backend filters; nothing to show for others).
- States: loading `Skeleton` (3 cards) · empty `Empty` "No claims yet" + [Create your first claim] · empty filter "No claims in Paid" + [Show all] · error `Alert` "Could not load claims" + [Retry] · success: toast after create/submit/delete ("Draft deleted", `Undo` not offered - delete is confirmed first).
- Keyboard: Tab to tabs (arrow keys switch), `/` focuses search, Tab through cards, Enter opens, `N` = new claim.

## 2. Claim editor `/claims/new`, `/claims/:id/edit` (DRAFT or CLARIFICATION_REQUESTED only)

360 px - lines as cards:
```
+------------------------------------+
| < Back        New claim    [Save]  |
+------------------------------------+
| Title *  [Client visit Mumbai   ]  |
| Purpose  [______________________]  |
+------------------------------------+
| Line 1                     [...]   |  <- menu: duplicate line, delete
| Category * [Hotel            v]    |
| Date *     [18 Nov 2026      ]     |
| City tier  (o) Tier-1 ( ) Other    |  <- only for Hotel
| Nights *   [1]                     |
| Vendor *   [Taj Lands End    ]     |
| Amount *   [INR  7,200       ]     |
|  ! Over limit: 6,000/night tier-1  |  <- live hint from GET /api/policy-rules
| Receipt  [Camera] [Upload]         |
|   [thumb] taj_bill.jpg  2.1MB  (x) |
+------------------------------------+
| Line 2  Meals - 18 Nov - 1,100     |  <- collapsed card, tap to expand
| Line 3  Cab - 19 Nov - 640         |
+------------------------------------+
| [+ Add line]                       |
+------------------------------------+
| Total 32,450 INR - 4 lines         |  <- sticky bottom bar
| [Save draft]      [Submit >]       |
+------------------------------------+
```
Desktop: lines in an editable `Table` (one row per line, receipt column with upload button), receipt preview in a right `Drawer`.

- `Form` + `Form.List` for lines; one line expanded at a time on mobile (`Collapse` accordion); new line auto-expanded with focus on Category.
- Category `Select`: Meals, Hotel, Local cab, Travel, Other. Category-specific fields: Hotel -> city tier + nights; Cab -> from/to (optional).
- Amount `InputNumber` with `INR` prefix, `en-IN` grouping, `inputMode="decimal"`, min 1, max 2 decimals.
- Date `DatePicker`, future dates disabled; weekend date shows soft hint "Weekend spend is flagged for your manager".
- **Receipt**: `Upload` with `accept="image/*,application/pdf"` and `capture="environment"` on the Camera button (opens phone camera); max 10 MB; jpg/png/pdf. Progress bar per file; thumbnail; remove (x). Uploads go to `POST /api/receipts` immediately, line stores `receiptId`.
- **Live policy hints** (client-side preview using policy rules; the server decision is final): over limit, "Receipt required above 500 INR" when amount > 500 and no receipt. Shown as `Typography.Text type="warning"` + flag icon, never blocking save.
- Save draft: `PUT`/`POST` without submit; autosave every 30 s while dirty ("Saved 10:42" text). Leaving with unsaved changes -> confirm `Modal`.
- Submit: validates all lines (required fields, receipt rule), then confirm `Modal` "Submit 4 lines, 32,450 INR? You cannot edit after submitting." -> `POST /submit` with an `Idempotency-Key` generated once per editor session (double tap safe; button `loading` + disabled).
- Clarification mode (status `CLARIFICATION_REQUESTED`): banner at top with the approver's question (§5) and lines editable; submit button reads "Resubmit".
- States: loading `Skeleton` form · error loading `Result` + Retry · validation errors inline per field + `Alert` summary at top listing lines with errors (links scroll to the line) · upload error per file ("Upload failed - Retry") · save error `message.error` keeps the form data · 409 not editable -> `Alert` "This claim was already submitted" + [View claim].
- Keyboard: autofocus Title; Tab order Title -> lines -> Add line -> Save -> Submit; Enter in a line's last field adds a new line; `Ctrl+S` save draft; `Ctrl+Enter` submit; Esc closes the receipt preview.

## 3. Submit result `/claims/:id/submitted`

The policy engine returns one outcome for the claim (worst line wins) and a result per line.

Auto-approved:
```
+------------------------------------+
|              (v)                   |
|   Claim auto-approved              |  Result status="success"
|   EXP-2026-00415 - 2,140 INR       |
|   All 3 lines are within policy.   |
|   Next: added to the next payout   |
|   batch (usually weekly).          |
|  [View claim]   [New claim]        |
+------------------------------------+
```
Routed for approval:
```
|              (i)                   |
|   Sent for approval                |  Result status="info"
|   EXP-2026-00412 - 32,450 INR      |
|   Approval chain:                  |
|   (o) Rahul Manager - now          |  Steps (vertical on mobile)
|   ( ) Finance auditor (> 25,000)   |
|   ( ) Finance head - not needed    |
|   Why: Line 1 Hotel over limit     |
|        (7,200 > 6,000/night tier-1)|
|  [View claim]   [New claim]        |
```
Auto-rejected:
```
|              (x)                   |
|   Claim rejected by policy         |  Result status="error"
|   EXP-2026-00416 - 9,800 INR       |
|   Reasons:                         |
|   - Line 2 Meals 2,300: receipt    |
|     required above 500 INR         |
|   - Line 3: duplicate of           |
|     EXP-2026-00201 line 1          |
|     (same vendor, amount, date)    |
|  [Copy to new draft]  [My claims]  |
```
- Every reason names the line, the rule and the numbers. "Copy to new draft" pre-fills a new claim so the employee can fix and resubmit.
- Focus moves to the result title on load (`tabIndex=-1`, `aria-live="polite"`) so screen readers announce the outcome.
- States: loading (submit in progress) `Spin` "Checking your claim against policy..." · error (network/5xx) `Result status="warning"` "We could not confirm the submit" + [Check claim status] (safe because of the idempotency key) · success = one of the three results above.

## 4. Claim detail with timeline `/claims/:id`

360 px:
```
+------------------------------------+
| < My claims                        |
| EXP-2026-00412   [Pending manager] |
| Client visit Mumbai                |
| 32,450 INR - submitted 18 Nov      |
+------------------------------------+
| [Lines] [Timeline] [Comments 2]    |  Tabs
+------------------------------------+
| Timeline                           |
| * 18 Nov 10:02 You submitted       |  Timeline
| * 18 Nov 10:02 Policy: routed      |
|   Hotel over limit (7,200 > 6,000) |
| * 19 Nov 16:40 Rahul Manager       |
|   sent back: "Attach hotel bill"   |
| * 20 Nov 09:15 You replied +       |
|   added receipt                    |
| o Waiting: Rahul Manager (2d)      |  pending dot
| o Next: Finance auditor            |
| o Then: Payout -> Paid             |
+------------------------------------+
```
- Lines tab: cards per line (category, date, vendor, amount, flags with reasons, receipt thumbnail -> full view in `Image` preview with zoom).
- Timeline from `GET /api/claims/{id}/audit` (append-only), future steps greyed. Delegation visible ("Assigned to Meera Backup - delegated by Rahul Manager").
- Actions by status: DRAFT -> Edit / Delete (`Popconfirm`); CLARIFICATION_REQUESTED -> Reply (§5); AUTO_REJECTED/REJECTED -> Copy to new draft; others read-only.
- Not my claim / not found -> `Result status="404"` "Claim not found" (backend returns 404, no enumeration).
- States: loading `Skeleton` · error + Retry · empty timeline cannot happen for a submitted claim (draft shows "Not submitted yet").
- Keyboard: tabs with arrow keys; timeline is a list readable in order; receipt preview Esc closes.

## 5. Clarification response `/claims/:id` (status CLARIFICATION_REQUESTED)

```
+------------------------------------+
| ! Rahul Manager needs more info    |  Alert type="warning" at top
| "Please attach the hotel bill for  |
|  line 1." - 19 Nov 16:40           |
+------------------------------------+
| Your reply *                       |
| [I've attached the Taj invoice.  ] |  TextArea, autofocus
| Attach / fix lines: [Edit lines]   |  -> editor in clarification mode
| [Send reply and resubmit]          |
+------------------------------------+
```
- Reply is required (min 5 chars). "Send reply and resubmit" posts the comment then submits (same idempotency rule); the claim goes back to the approver who asked.
- Changed lines are marked "Changed after clarification" for the approver.
- States: sending `loading` button · error keeps text, `message.error` + retry · success `Result status="success"` "Reply sent - back with Rahul Manager".
- Keyboard: focus lands in the reply box; `Ctrl+Enter` sends.

## 6. My dashboard `/me/dashboard` (landing for EMPLOYEE)

```
[ 1 Day | 7 Days | 1 Month | Custom ]   Last 7 days: 20 Nov - 27 Nov
[My spend 12,340] [Pending 2] [Reimbursed 8,200] [Avg approval 26 h]   (i) on each
[Needs your reply (1) -> card]
[My spend by category - bar]   [Recent claims (5) -> My claims]
[+ New claim] (primary, full width on mobile)
```
KPI definitions: see `internal-wireframes.md` §7.1. Same date-filter pattern (presets, URL, empty range -> "No data between X and Y" + [Widen to 1 Month]).
- States: per-widget loading / error + Retry; empty = "No claims in this range" + [New claim].

---

## 7. Cross-screen checklist
- 360 px: no horizontal scroll, touch targets >= 44 px, sticky bottom action bar on editor and detail, inputs 16 px font.
- Every form field has a visible label; errors linked via `aria-describedby` (Ant `Form.Item` does this).
- Money `en-IN` with "INR"; dates `DD MMM YYYY`.
- Status and flags always via `statusTag` / `flagTag` (icon + text, colour-blind safe).
- Every screen: loading, empty, error (with retry), success listed above.

## 8. Planned E2E (L4/L6)
1. Employee creates a claim with 3 lines on a 360 px viewport, uploads a receipt, saves draft, submits -> auto-approved result.
2. Over-limit hotel line -> routed result showing manager -> auditor chain.
3. Meals > 500 without receipt + duplicate line -> auto-rejected with both reasons; Copy to new draft.
4. Clarification: manager sends back, employee replies and resubmits; timeline shows all steps.
5. Employee opens another employee's claim URL -> 404.
