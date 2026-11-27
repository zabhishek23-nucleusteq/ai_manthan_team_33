// Walks the "Show the invigilator" demo script (docs/sprints/S1-report.md) against the running stack and saves
// one screenshot per step into docs/demo/screenshots/. Uses Playwright from tests/e2e and the installed Edge.
//   cd tests/e2e && npm ci && node ../../docs/demo/capture.cjs
const path = require('path');
const { chromium } = require(path.resolve(__dirname, '../../tests/e2e/node_modules/playwright'));

const WEB = process.env.WEB_URL || 'http://localhost:3000';
const API = process.env.API_URL || 'http://localhost:8080';
const OUT = path.resolve(__dirname, 'screenshots');
const PASSWORD = 'Demo@123';
const USERS = [
  ['employee', 'employee@expenseflow.test'],
  ['manager', 'manager@expenseflow.test'],
  ['auditor', 'auditor@expenseflow.test'],
  ['head', 'head@expenseflow.test'],
  ['admin', 'admin@expenseflow.test'],
];

const results = [];

async function step(name, fn) {
  try {
    await fn();
    results.push(`OK   ${name}`);
  } catch (e) {
    results.push(`FAIL ${name}: ${String(e.message || e).split('\n')[0]}`);
  }
}

async function login(page, email) {
  await page.goto(`${WEB}/login`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(800);
}

(async () => {
  const browser = await chromium.launch({ channel: process.env.PW_CHANNEL || 'msedge' });
  const newPage = async () => (await browser.newContext({ viewport: { width: 1366, height: 860 } })).newPage();
  const shot = (page, file) => page.screenshot({ path: path.join(OUT, file), fullPage: true });

  // 1. Sign up a new employee, then log in -> lands on the app
  await step('01 sign-up form', async () => {
    const page = await newPage();
    const email = `demo-${Date.now()}@expenseflow.test`;
    await page.goto(`${WEB}/signup`);
    await page.getByLabel('Full name').fill('Demo Newcomer');
    await page.getByLabel('Work email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Newcomer@123');
    await page.getByLabel('Confirm password').fill('Newcomer@123');
    await shot(page, '01-signup-form.png');
    await page.getByRole('button', { name: 'Create account' }).click();
    await page.waitForURL(/\/login/, { timeout: 15000 });
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill('Newcomer@123');
    await page.getByRole('button', { name: 'Log in' }).click();
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(800);
    await shot(page, '02-signup-then-login-landing.png');
  });

  // 2. Design page
  await step('03 design page', async () => {
    const page = await newPage();
    await page.goto(`${WEB}/design`);
    await page.waitForLoadState('networkidle').catch(() => {});
    await shot(page, '03-design-page.png');
  });

  // 3. Each demo role lands on its own screen
  let n = 4;
  for (const [key, email] of USERS) {
    const file = `${String(n++).padStart(2, '0')}-login-${key}.png`;
    await step(`${file}`, async () => {
      const page = await newPage();
      await login(page, email);
      await shot(page, file);
    });
  }

  // 4. No token -> 401 JSON
  await step('09 /api/auth/me without token', async () => {
    const page = await newPage();
    await page.goto(`${API}/api/auth/me`);
    await shot(page, '09-auth-me-401.png');
  });

  // 5. Health: backend, mock bank, Prometheus, Grafana
  const health = [
    ['10-backend-health.png', `${API}/actuator/health`],
    ['11-mock-bank-health.png', 'http://localhost:8081/actuator/health'],
    ['12-prometheus-targets.png', 'http://localhost:9090/targets'],
    ['13-grafana.png', 'http://localhost:3001/'],
  ];
  for (const [file, url] of health) {
    await step(file, async () => {
      const page = await newPage();
      await page.goto(url);
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(1500);
      await shot(page, file);
    });
  }

  // 6. Contract (served by the backend as Swagger UI)
  await step('14 contract / swagger', async () => {
    const page = await newPage();
    await page.goto(`${API}/swagger-ui.html`);
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(1500);
    await shot(page, '14-api-docs-swagger.png');
  });

  await browser.close();
  console.log(results.join('\n'));
})();
