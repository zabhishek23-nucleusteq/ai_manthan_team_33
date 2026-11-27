import { defineConfig } from '@playwright/test';

// API_URL: backend base URL (compose publishes it on 8080). UI projects are added with the browser specs (T-035).
export default defineConfig({
  testDir: './api',
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
      use: { baseURL: process.env.API_URL ?? 'http://localhost:8080' },
    },
  ],
});
