import { test, expect, type Page } from '@playwright/test';
import { DEMO_USERS, loginAs } from './helpers';

// Most recent weekday that is not in the future (weekend spend is flagged by policy and would change the outcome).
function lastWeekday(): Date {
  const d = new Date();
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() - 1);
  return d;
}

// Fills a one-line MEAL claim through the real editor and submits it. Unique vendor + amount per run so the
// duplicate-detection rule (same vendor, amount, date) cannot interfere with reruns.
async function submitMealClaim(page: Page, amount: number) {
  const unique = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  await page.goto('/claims/new');
  await page.getByLabel('Title').fill(`E2E meal ${unique}`);
  await page.getByLabel('Line 1 category').click();
  await page.getByTitle('Meals').click();
  // The date field is a read-only calendar picker: open it and click the day cell (title = YYYY-MM-DD).
  const day = lastWeekday();
  const iso = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
  await page.getByLabel('Line 1 date').click();
  if (day.getMonth() !== new Date().getMonth()) await page.locator('.ant-picker-header-prev-btn').click();
  await page.locator(`td[title="${iso}"]`).click();
  await page.getByLabel('Line 1 amount').fill(String(amount));
  await page.getByLabel('Line 1 vendor').fill(`E2E Cafe ${unique}`);
  await page.getByRole('button', { name: /Submit$/ }).click();
  // "Submit this claim?" confirmation dialog
  await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
}

// Business-flow cases TC-06..TC-10 (docs/testing/test-cases.md). Each is test.fixme until the feature it needs is
// merged; the reason names the task so skips are visible in the report and never counted as passes.
// To enable one: replace test.fixme with test and implement the steps from the written case.

test('TC-06 employee submits a small claim -> auto-approved', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]);
  await submitMealClaim(page, 250); // <= 500 INR: no receipt needed; <= 1,500 INR meals limit
  await expect(page.getByText('Claim auto-approved')).toBeVisible({ timeout: 15_000 });
});

test('TC-07 meal without receipt over 500 INR -> auto-rejected with a reason', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]);
  await submitMealClaim(page, 800); // > 500 INR and no receipt attached
  await expect(page.getByText('Claim rejected by policy')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/receipt/i).first()).toBeVisible();
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

test('TC-14 dashboard date presets 1D / 7D / 1M change the numbers', async ({ page }) => {
  // Auditor sees the whole company (scope ALL); seeded history is spread over the past months, so a wider
  // range can only include more claims.
  await loginAs(page, DEMO_USERS[2]);
  const claims = page.getByTestId('kpi-claims');
  const readClaims = async () => Number((await claims.locator('.ant-statistic-content').innerText()).replace(/[^0-9]/g, ''));
  const range = page.getByRole('radiogroup', { name: 'Date range' });
  await expect(claims).toBeVisible({ timeout: 15_000 });
  const counts: number[] = [];
  for (const label of ['1 Day', '7 Days', '1 Month']) {
    const option = range.getByText(label, { exact: true });
    // Already the active preset (the page has a default): the shown number is current, no reload to wait for.
    const active = await range.locator('.ant-segmented-item-selected').innerText();
    const before = await readClaims();
    await option.click();
    if (active.trim() !== label) await expect.poll(readClaims, { timeout: 15_000 }).not.toBe(before);
    counts.push(await readClaims());
  }
  expect(counts[0]).toBeLessThan(counts[1]);
  expect(counts[1]).toBeLessThan(counts[2]);
});
