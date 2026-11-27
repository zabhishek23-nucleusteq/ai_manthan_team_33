import { defineConfig, devices } from '@playwright/test';

// API_URL: backend (compose publishes 8080). WEB_URL: frontend (compose publishes 3000).
// PW_CHANNEL=msedge uses the installed Edge when the Playwright browser download is blocked (company laptops).
const channel = process.env.PW_CHANNEL || undefined;

export default defineConfig({
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  projects: [
    {
      name: 'api',
      testDir: './api',
      use: { baseURL: process.env.API_URL ?? 'http://localhost:8080' },
    },
    {
      name: 'ui',
      testDir: './ui',
      use: {
        ...devices['Desktop Chrome'],
        channel,
        baseURL: process.env.WEB_URL ?? 'http://localhost:3000',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
      },
    },
    {
      name: 'ui-mobile',
      testDir: './ui',
      testMatch: /auth\.spec\.ts|a11y\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        channel,
        viewport: { width: 360, height: 740 },
        baseURL: process.env.WEB_URL ?? 'http://localhost:3000',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
      },
    },
  ],
});
