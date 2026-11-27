# Track A · Use Case: ExpenseFlow – Expense Claims and Policy-Driven Approvals

## Problem Statement

A mid-size services company reimburses expenses through email threads and Excel. Policy breaches slip through, duplicate receipts get paid, and managers take days to respond. Build an expense system with a policy engine, a multi-level approval chain, an auditor review queue, and a payout export.

## Personas

* Employee
* Line manager
* Finance auditor
* Finance head (also admin)

## Must-Have Scope

* An employee creates a claim with several line items (category, amount, date, receipt upload), saves a draft, and submits.
* A policy engine checks every line and returns one of three outcomes: **auto-approve**, **route for approval**, or **auto-reject** with a reason.
* The approval chain runs manager, then finance auditor above 25,000 INR, then finance head above 1,00,000 INR. Approvers can comment, send back for clarification, or delegate while on leave.
* A finance auditor works a review queue with filters and bulk approve, and a random 10% of auto-approved claims are flagged for sample audit.
* Approved claims go into a payout batch that exports as a CSV in a mock bank-file format, and claims then move to **Paid**.
* A dashboard shows spend by category, department, and month, policy violations, and average approval time.
* An append-only audit trail records every action. An employee can only see their own claims.

## Policy Rules (Seed as Configuration, Not Hard-Coded)

* **Meals:** Up to 1,500 INR per day.
* **Hotel:** Up to 6,000 INR per night in tier-1 cities and 4,000 INR in others.
* **Local cab:** Up to 2,000 INR per trip.
* **Receipts:** A receipt is mandatory above 500 INR.
* **Duplicate detection:** A line with the same vendor, amount, and date as an earlier line, or the same receipt file hash, is flagged as a duplicate.
* **Weekend spend:** Flagged for manager attention.

## Synthetic Data

150 employees in 8 departments, a reporting hierarchy, and 3,000 historical claims including planted duplicates and limit breaches.

## Phase-by-Phase Expectations

| Phase | Expected Output | How Claude Should Be Used |
| :--- | :--- | :--- |
| **Architecture** | Context and container diagram, claim state machine, policy-as-configuration design, ADR on how approval chains are modelled | Ask Claude to review the design as a skeptical finance auditor and list ways to bypass approvals |
| **UI and UX** | Mobile-friendly claim capture, side-by-side receipt viewer for approvers, dense but scannable data tables with bulk actions | Claude proposes layouts and states for tables; the team tests the approver flow with a stopwatch |
| **Development** | Working app with the full chain, policy engine, and payout export | Plan-first prompts, `CLAUDE.md` with business rules, tests written before the policy engine |
| **Testing** | Unit tests for every policy rule, tests for approval thresholds and delegation, 10 written end-to-end cases, defect log, a basic security checklist run | Claude generates rule-by-rule cases and reviews the code against common web vulnerabilities; the team fixes what is real and rejects false alarms |
| **Deployment** | Docker Compose, CI with tests and a dependency check, seeded demo environment from one command | Claude writes CI; the team confirms secrets are not committed |
| **Support (SRE)** | Metrics for queue size, approval age, and page latency, dashboard, alert, runbook | Claude drafts the runbook and an alert on oldest pending approval; the team validates thresholds |