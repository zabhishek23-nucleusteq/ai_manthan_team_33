import { test, expect } from '@playwright/test';
import { DEMO_USERS, login, loginAs } from './helpers';

// Written cases TC-01..TC-05 and TC-11 in docs/testing/test-cases.md.

test.describe('TC-01 login lands each role on its own dashboard', () => {
  for (const u of DEMO_USERS) {
    test(`${u.key} -> ${u.landing}`, async ({ page }) => {
      await loginAs(page, u);
      await expect(page.getByText(`Logged in as ${u.name}`)).toBeVisible();
    });
  }
});

test('TC-02 wrong password shows an error and stays on login', async ({ page }) => {
  await login(page, 'employee@expenseflow.test', 'not-the-password');
  await expect(page.getByRole('alert')).toContainText('Email or password is incorrect');
  await expect(page).toHaveURL(/\/login/);
});

test('TC-03 sign-up creates an employee who can then log in', async ({ page }) => {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@expenseflow.test`;
  await page.goto('/signup');
  await page.getByLabel('Full name').fill('E2E Newcomer');
  await page.getByLabel('Work email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Newcomer@123');
  await page.getByLabel('Confirm password').fill('Newcomer@123');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/login\?created=1/);
  await expect(page.getByText('Account created')).toBeVisible();
  await page.getByLabel('Password').fill('Newcomer@123');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(/\/me\/dashboard$/);
});

test('TC-04 sign-up validates mismatched passwords', async ({ page }) => {
  await page.goto('/signup');
  await page.getByLabel('Full name').fill('E2E Mismatch');
  await page.getByLabel('Work email').fill('mismatch@expenseflow.test');
  await page.getByLabel('Password', { exact: true }).fill('Newcomer@123');
  await page.getByLabel('Confirm password').fill('Different@123');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/signup/);
  await expect(page.getByText(/match/i)).toBeVisible();
});

test('TC-05 a protected page without a session redirects to login and returns after login', async ({ page }) => {
  await page.goto('/claims');
  await expect(page).toHaveURL(/\/login\?next=%2Fclaims/);
  await page.getByLabel('Email').fill('employee@expenseflow.test');
  await page.getByLabel('Password').fill('Demo@123');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(/\/claims$/);
});

test('TC-11 a role without access sees the 403 page', async ({ page }) => {
  await loginAs(page, DEMO_USERS[0]); // employee
  await page.goto('/admin');
  await expect(page.getByText('403 - No access')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Go to my dashboard' })).toBeVisible();
});
