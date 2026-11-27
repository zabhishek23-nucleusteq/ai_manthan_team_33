# ADR-002: Policy rules as configuration

Status: Accepted (kickoff, S1) · Owner: L1

## Context
The statement requires the policy rules (meals 1,500/day, hotel 6,000/4,000 per night, cab 2,000/trip, receipt above 500, duplicates, weekend) to be **seeded as configuration, not hard-coded**. Finance must be able to change limits; every result must be explainable to the employee (reason codes) and reproducible for audit.

## Options
### A. `application.yml` properties
- + Zero code for storage; typed `@ConfigurationProperties`.
- - Change needs a redeploy; no audit of who changed what; not editable from the UI.

### B. Database table `policy_rules` + rule evaluators in code (chosen)
Each row: `code, category, limit_amount, unit (PER_DAY|PER_NIGHT|PER_TRIP|PER_LINE|PER_CLAIM), params jsonb (e.g. tier1Cities), action (ROUTE|REJECT), enabled`. Each `code` maps to one small Java evaluator (`MealDailyLimitRule`, `HotelNightlyLimitRule`, `CabTripLimitRule`, `ReceiptRequiredRule`, `DuplicateLineRule`, `DuplicateReceiptRule`, `WeekendSpendRule`). Values and action come from the row.
- + Limits, thresholds, actions and on/off editable via `PUT /api/policy-rules/{code}` (FINANCE_HEAD), audited with before/after.
- + Evaluators are pure functions -> one unit test class per rule with boundary tests (tests first).
- - A new kind of rule still needs code (acceptable).

### C. Rule engine / expression language (Drools, SpEL, JSON-logic stored in DB)
- + Fully dynamic rules.
- - Hard to test and explain, risk of unsafe expressions (SpEL injection), too heavy for the time box.

## Decision
**Option B.** Flyway seeds the rows (`V3__policy_rules.sql`). `PolicyEngine.evaluate(claim, context)` loads enabled rules, runs every evaluator on every line, returns `PolicyResult {outcome, lineResults[violations], reasons, ruleSnapshot}` which is stored on the claim (jsonb) so a later rule change does not alter history. Outcome per BR-9. Chain thresholds (25,000 / 1,00,000), auto-approve max (5,000) and sample rate (0.10) are rows too.

## Consequences
- The UI shows reason codes with human messages ("Meals on 21 Nov total 1,820 INR, limit 1,500 per day").
- Changing a rule never re-evaluates already submitted claims (snapshot).
- Tests: every rule has boundary tests at the limit and 0.01 above, plus disabled-rule test.
