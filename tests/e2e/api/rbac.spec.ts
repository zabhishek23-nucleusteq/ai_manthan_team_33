import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test';

/**
 * RBAC matrix generated from docs/architecture/rbac.md section 3.
 *  - no token                       -> 401 (checked for every endpoint, implemented or not)
 *  - one allowed role               -> not 401/403 and not 5xx (400/404 are fine: we send empty bodies / unknown ids)
 *  - one role that is NOT allowed   -> 403
 * Allowed/denied checks need the endpoint to exist (listed in /v3/api-docs) and the demo user to log in
 * (seed data, T-013); otherwise they are skipped with the reason, so skips shrink as the backend lands.
 */

type Role = 'EMPLOYEE' | 'MANAGER' | 'FINANCE_AUDITOR' | 'FINANCE_HEAD' | 'SUPER_ADMIN';
const ALL_ROLES: Role[] = ['EMPLOYEE', 'MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD', 'SUPER_ADMIN'];

// Demo logins, rbac.md section 4 (all share the password below).
const DEMO_PASSWORD = 'Demo@123';
const LOGIN: Record<Role, string> = {
  EMPLOYEE: 'employee@expenseflow.test',
  MANAGER: 'manager@expenseflow.test',
  FINANCE_AUDITOR: 'auditor@expenseflow.test',
  FINANCE_HEAD: 'head@expenseflow.test',
  SUPER_ADMIN: 'admin@expenseflow.test',
};
// Staff demo users also hold EMPLOYEE, so "denied" probes must use a user whose full role set excludes the endpoint's roles.
// Effective role sets of the demo users (rbac.md section 4).
const EFFECTIVE: Record<Role, Role[]> = {
  EMPLOYEE: ['EMPLOYEE'],
  MANAGER: ['EMPLOYEE', 'MANAGER'],
  FINANCE_AUDITOR: ['EMPLOYEE', 'FINANCE_AUDITOR'],
  FINANCE_HEAD: ['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'],
  SUPER_ADMIN: ['SUPER_ADMIN'],
};

interface Endpoint {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string; // contract path template
  allowed: Role[]; // roles that may call it
  body?: unknown;
}

const EMP_UP: Role[] = ['EMPLOYEE', 'MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD'];
const APPROVERS: Role[] = ['MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD'];
const FIN: Role[] = ['FINANCE_AUDITOR', 'FINANCE_HEAD'];

const ENDPOINTS: Endpoint[] = [
  { method: 'GET', path: '/api/auth/me', allowed: ALL_ROLES },
  { method: 'GET', path: '/api/claims', allowed: EMP_UP },
  { method: 'POST', path: '/api/claims', allowed: EMP_UP, body: {} },
  { method: 'GET', path: '/api/claims/{id}', allowed: EMP_UP },
  { method: 'PUT', path: '/api/claims/{id}', allowed: EMP_UP, body: {} },
  { method: 'DELETE', path: '/api/claims/{id}', allowed: EMP_UP },
  { method: 'POST', path: '/api/claims/{id}/submit', allowed: EMP_UP, body: {} },
  { method: 'POST', path: '/api/receipts', allowed: EMP_UP },
  { method: 'GET', path: '/api/receipts/{id}/content', allowed: [...EMP_UP] },
  { method: 'GET', path: '/api/claims/{id}/audit', allowed: EMP_UP },
  { method: 'GET', path: '/api/claims/{id}/comments', allowed: EMP_UP },
  { method: 'POST', path: '/api/claims/{id}/comments', allowed: EMP_UP, body: {} },
  { method: 'GET', path: '/api/approvals/inbox', allowed: APPROVERS },
  { method: 'POST', path: '/api/approvals/{claimId}/approve', allowed: APPROVERS, body: {} },
  { method: 'POST', path: '/api/approvals/{claimId}/reject', allowed: APPROVERS, body: {} },
  { method: 'POST', path: '/api/approvals/{claimId}/send-back', allowed: APPROVERS, body: {} },
  { method: 'GET', path: '/api/delegations', allowed: APPROVERS },
  { method: 'POST', path: '/api/delegations', allowed: APPROVERS, body: {} },
  { method: 'DELETE', path: '/api/delegations/{id}', allowed: APPROVERS },
  { method: 'GET', path: '/api/users/approvers', allowed: [...APPROVERS, 'SUPER_ADMIN'] },
  { method: 'GET', path: '/api/departments', allowed: ALL_ROLES },
  { method: 'GET', path: '/api/audit-queue', allowed: FIN },
  { method: 'POST', path: '/api/audit-queue/bulk-approve', allowed: FIN, body: {} },
  { method: 'POST', path: '/api/audit-queue/{claimId}/sample-audit', allowed: FIN, body: {} },
  { method: 'GET', path: '/api/payout-batches', allowed: FIN },
  { method: 'GET', path: '/api/payout-batches/{id}', allowed: FIN },
  { method: 'GET', path: '/api/payout-batches/{id}/export.csv', allowed: FIN },
  { method: 'POST', path: '/api/payout-batches', allowed: ['FINANCE_HEAD'], body: {} },
  { method: 'POST', path: '/api/payout-batches/{id}/submit', allowed: ['FINANCE_HEAD'], body: {} },
  { method: 'GET', path: '/api/policy-rules', allowed: [...APPROVERS, 'SUPER_ADMIN'] },
  { method: 'PUT', path: '/api/policy-rules/{code}', allowed: ['FINANCE_HEAD'], body: {} },
  { method: 'GET', path: '/api/dashboard/kpis', allowed: ALL_ROLES },
  { method: 'POST', path: '/api/telemetry/page-load', allowed: ALL_ROLES, body: {} },
  { method: 'GET', path: '/api/admin/users', allowed: ['SUPER_ADMIN'] },
  { method: 'POST', path: '/api/admin/users', allowed: ['SUPER_ADMIN'], body: {} },
  { method: 'PUT', path: '/api/admin/users/{id}/roles', allowed: ['SUPER_ADMIN'], body: {} },
  { method: 'PATCH', path: '/api/admin/users/{id}', allowed: ['SUPER_ADMIN'], body: {} },
  { method: 'GET', path: '/api/admin/overview', allowed: ['SUPER_ADMIN'] },
  { method: 'GET', path: '/api/admin/audit', allowed: ['SUPER_ADMIN'] },
  { method: 'GET', path: '/api/admin/health', allowed: ['SUPER_ADMIN'] },
];

// Known backend defects. These checks are expected to fail (test.fail): when the bug is fixed they start
// "unexpectedly passing", which fails CI and tells us to delete the entry. Key: "METHOD contract-path".
const KNOWN_BUGS: Record<string, string> = {
  'POST /api/admin/users': 'T-502: 400 instead of 403 (body validated before @PreAuthorize)',
  'PUT /api/admin/users/{id}/roles': 'T-502: 400 instead of 403 (body validated before @PreAuthorize)',
};

const norm = (p: string) => p.replace(/\{[^}]+\}/g, '{}');
const concrete = (p: string) => p.replace(/\{code\}/g, 'NO_SUCH_RULE').replace(/\{[^}]+\}/g, '999999999');

let api: APIRequestContext;
const tokens: Partial<Record<Role, string>> = {};
const implemented = new Set<string>(); // "METHOD norm(path)" found in /v3/api-docs

function call(ep: Endpoint, token?: string) {
  const url = concrete(ep.path);
  const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};
  const opts: { headers: Record<string, string>; data?: unknown; failOnStatusCode: boolean } = {
    headers,
    failOnStatusCode: false,
  };
  if (ep.body !== undefined) opts.data = ep.body;
  return api.fetch(url, { method: ep.method, ...opts });
}

test.beforeAll(async () => {
  api = await pwRequest.newContext({ baseURL: process.env.API_URL ?? 'http://localhost:8080' });

  const docs = await api.get('/v3/api-docs', { failOnStatusCode: false });
  if (docs.ok()) {
    const spec = (await docs.json()) as { paths?: Record<string, Record<string, unknown>> };
    for (const [path, ops] of Object.entries(spec.paths ?? {})) {
      for (const m of Object.keys(ops)) implemented.add(`${m.toUpperCase()} ${norm(path)}`);
    }
  }

  for (const role of ALL_ROLES) {
    const res = await api.post('/api/auth/login', {
      data: { email: LOGIN[role], password: DEMO_PASSWORD },
      failOnStatusCode: false,
    });
    if (res.ok()) tokens[role] = ((await res.json()) as { accessToken: string }).accessToken;
  }
});

test.afterAll(async () => {
  await api?.dispose();
});

test.describe('RBAC matrix (rbac.md s3)', () => {
  for (const ep of ENDPOINTS) {
    const label = `${ep.method} ${ep.path}`;
    const key = `${ep.method} ${norm(ep.path)}`;

    test(`${label}: no token -> 401`, async () => {
      const res = await call(ep);
      expect(res.status()).toBe(401);
    });

    test(`${label}: allowed role is not rejected`, async () => {
      test.skip(!implemented.has(key), 'endpoint not implemented yet (not in /v3/api-docs)');
      const role = ep.allowed.find((r) => tokens[r]);
      test.skip(!role, 'no allowed demo user can log in yet (seed data, T-013)');
      const res = await call(ep, tokens[role!]);
      expect([401, 403], `${role} got ${res.status()}`).not.toContain(res.status());
      expect(res.status(), `${role} got ${res.status()}`).toBeLessThan(500);
    });

    test(`${label}: a role without access -> 403`, async () => {
      test.skip(!implemented.has(key), 'endpoint not implemented yet (not in /v3/api-docs)');
      const denied = ALL_ROLES.find(
        (r) => tokens[r] && !EFFECTIVE[r].some((eff) => ep.allowed.includes(eff)),
      );
      test.skip(!denied, 'no demo user without access can log in yet (seed data, T-013)');
      const bug = KNOWN_BUGS[`${ep.method} ${ep.path}`];
      if (bug) test.fail(true, bug);
      const res = await call(ep, tokens[denied!]);
      expect(res.status(), `${denied} got ${res.status()}`).toBe(403);
    });
  }
});

test.describe('Row rules and sign-up', () => {
  const unique = () => `rbac-${Date.now()}-${Math.floor(Math.random() * 1e6)}@expenseflow.test`;

  test('sign-up yields EMPLOYEE only', async () => {
    const res = await api.post('/api/auth/signup', {
      data: { name: 'Rbac Probe', email: unique(), password: 'Probe@12345' },
      failOnStatusCode: false,
    });
    expect(res.status()).toBe(201);
    expect(((await res.json()) as { roles: string[] }).roles).toEqual(['EMPLOYEE']);
  });

  test('sign-up ignores a requested staff role', async () => {
    const email = unique();
    const res = await api.post('/api/auth/signup', {
      data: {
        name: 'Rbac Escalate',
        email,
        password: 'Probe@12345',
        roles: ['SUPER_ADMIN'],
        role: 'SUPER_ADMIN',
      },
      failOnStatusCode: false,
    });
    // Either rejected outright (400) or created as a plain employee. Never a staff role.
    expect([201, 400]).toContain(res.status());
    if (res.status() === 201) {
      expect(((await res.json()) as { roles: string[] }).roles).toEqual(['EMPLOYEE']);
      const login = await api.post('/api/auth/login', { data: { email, password: 'Probe@12345' } });
      const { accessToken } = (await login.json()) as { accessToken: string };
      const me = await api.get('/api/auth/me', { headers: { Authorization: `Bearer ${accessToken}` } });
      expect(((await me.json()) as { roles: string[] }).roles).toEqual(['EMPLOYEE']);
    }
  });

  test('employee reading another employee\'s claim -> 404 (no enumeration)', async () => {
    test.skip(!implemented.has('GET /api/claims/{}'), 'claims API not implemented yet (T-016)');
    test.skip(!tokens.EMPLOYEE, 'demo employee cannot log in yet (seed data, T-013)');
    const list = await api.get('/api/claims', {
      headers: { Authorization: `Bearer ${tokens.EMPLOYEE}` },
      failOnStatusCode: false,
    });
    expect(list.ok()).toBeTruthy();
    const body = (await list.json()) as { content?: { id: number }[] } | { id: number }[];
    const items = Array.isArray(body) ? body : (body.content ?? []);
    test.skip(items.length === 0, 'demo employee has no claims yet (seed data, T-013)');
    const claimId = items[0].id;

    const email = unique();
    await api.post('/api/auth/signup', { data: { name: 'Rbac Other', email, password: 'Probe@12345' } });
    const login = await api.post('/api/auth/login', { data: { email, password: 'Probe@12345' } });
    const { accessToken } = (await login.json()) as { accessToken: string };

    const res = await api.get(`/api/claims/${claimId}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      failOnStatusCode: false,
    });
    expect(res.status()).toBe(404);
  });
});
