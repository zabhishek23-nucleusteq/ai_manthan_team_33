import AxeBuilder from '@axe-core/playwright';
import { test, expect, type Page } from '@playwright/test';
import { DEMO_USERS, loginAs } from './helpers';

// NFR-A11Y: no serious or critical axe violations on the key screens (TC-A11Y).
async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(
    bad.map((v) => `${v.id} (${v.impact}): ${v.nodes.length} node(s), e.g. ${v.nodes[0]?.target.join(' ')}`),
  ).toEqual([]);
}

test('a11y: login page', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Log in' })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test('a11y: sign-up page', async ({ page }) => {
  await page.goto('/signup');
  await expect(page.getByRole('button', { name: 'Create account' })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test('a11y: landing dashboard (employee)', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]);
  await expectNoSeriousViolations(page);
});

test('a11y: 403 page', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]);
  await page.goto('/admin');
  await expect(page.getByText('403 - No access')).toBeVisible();
  await expectNoSeriousViolations(page);
});

test('a11y: my claims', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]);
  await page.goto('/claims');
  await expect(page.getByText('My claims').first()).toBeVisible();
  await expectNoSeriousViolations(page);
});

test('keyboard: login form can be completed without a mouse', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').focus();
  await page.keyboard.type('employee@expenseflow.test');
  await page.keyboard.press('Tab');
  await page.keyboard.type('Demo@123');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/me\/dashboard$/);
});
