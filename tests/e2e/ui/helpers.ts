import { expect, type Page } from '@playwright/test';

export const DEMO_PASSWORD = 'Demo@123';

export type DemoUser = { key: string; email: string; name: string; landing: string; heading: RegExp };

// rbac.md section 2 and 4. Farhan Head is also SUPER_ADMIN, so lands on /admin (highest role wins).
export const DEMO_USERS: DemoUser[] = [
  { key: 'employee', email: 'employee@expenseflow.test', name: 'Priya Employee', landing: '/me/dashboard', heading: /My dashboard/ },
  { key: 'manager', email: 'manager@expenseflow.test', name: 'Rahul Manager', landing: '/manager/dashboard', heading: /Team dashboard/ },
  { key: 'auditor', email: 'auditor@expenseflow.test', name: 'Anita Auditor', landing: '/finance/dashboard', heading: /Finance dashboard/ },
  { key: 'head', email: 'head@expenseflow.test', name: 'Farhan Head', landing: '/admin', heading: /Admin|Logged in as Farhan Head/i },
  { key: 'admin', email: 'admin@expenseflow.test', name: 'Abhishek Admin', landing: '/admin', heading: /Admin|Logged in as Abhishek Admin/i },
];

export async function login(page: Page, email: string, password = DEMO_PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

export async function loginAs(page: Page, user: DemoUser) {
  await login(page, user.email);
  await expect(page).toHaveURL(new RegExp(`${user.landing}$`));
}
