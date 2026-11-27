import { test } from '@playwright/test';

// Business-flow cases TC-06..TC-10 (docs/testing/test-cases.md). Each is test.fixme until the feature it needs is
// merged; the reason names the task so skips are visible in the report and never counted as passes.
// To enable one: replace test.fixme with test and implement the steps from the written case.

test.fixme('TC-06 employee submits a small claim -> auto-approved', async () => {
  // needs: claims API + submit (T-016), policy engine wired (T-015), employee claim editor (T-022/T-023)
});

test.fixme('TC-07 meal without receipt over 500 INR -> auto-rejected with a reason', async () => {
  // needs: claims API + submit (T-016)
});

test.fixme('TC-08 claim over 25,000 INR goes manager then auditor; auditor approves', async () => {
  // needs: approval chain API (T-019) and approver inbox UI (T-025)
});

test.fixme('TC-09 manager sends back, employee edits and resubmits', async () => {
  // needs: approval chain API (T-019), approver inbox (T-025), claim detail + clarification (T-023)
});

test.fixme('TC-10 manager delegates while on leave; delegate approves', async () => {
  // needs: delegation API (T-019) and delegation screen (T-025)
});

test.fixme('TC-12 auditor bulk-approves from the audit queue', async () => {
  // needs: auditor queue API (T-020) and UI (T-026)
});

test.fixme('TC-13 finance head creates a payout batch and downloads the bank CSV', async () => {
  // needs: payout batch API (T-017) and UI (T-031)
});

test.fixme('TC-14 dashboard date presets 1D / 7D / 1M change the numbers', async () => {
  // needs: role dashboards UI (T-033)
});
