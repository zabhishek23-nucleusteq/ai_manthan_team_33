// Turns test-results/results.json (Playwright JSON reporter) into docs/testing/rbac-matrix.md.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const report = JSON.parse(readFileSync(resolve(here, '../test-results/results.json'), 'utf8'));
const out = resolve(here, '../../../docs/testing/rbac-matrix.md');

const rows = [];
const walk = (suite) => {
  for (const spec of suite.specs ?? []) {
    const t = spec.tests?.[0];
    const known = t?.annotations?.find((a) => a.type === 'fail');
    const status = t?.status === 'skipped' ? 'skipped'
      : known ? 'KNOWN BUG'
      : t?.status === 'expected' ? 'pass' : 'FAIL';
    const reason = known?.description ?? t?.annotations?.find((a) => a.type === 'skip')?.description ?? '';
    rows.push({ title: spec.title, status, reason });
  }
  for (const s of suite.suites ?? []) walk(s);
};
for (const s of report.suites) walk(s);

const count = (s) => rows.filter((r) => r.status === s).length;
const lines = [
  '# RBAC test matrix',
  '',
  'Generated from `tests/e2e/api/rbac.spec.ts`, which encodes `docs/architecture/rbac.md` section 3.',
  'Regenerate: start the stack (`./start.ps1`), then in `tests/e2e`: `npm run test:api; npm run rbac:summary`.',
  '',
  `Last run: ${new Date().toISOString().slice(0, 10)} - **${count('pass')} passed, ${count('FAIL')} failed, ${count('KNOWN BUG')} known bugs, ${count('skipped')} skipped** of ${rows.length}.`,
  '',
  'Per endpoint: (1) no token -> 401; (2) an allowed role is not 401/403/5xx; (3) a role without access -> 403.',
  'Checks 2 and 3 are skipped until the endpoint is in `/v3/api-docs` and the seeded demo users (T-013) can log in; skips are not passes.',
  '',
  'KNOWN BUG = the backend deviates from rbac.md; the check is expected to fail until the linked bug task is fixed.',
  '',
  '| Check | Result | Note |',
  '|---|---|---|',
  ...rows.map((r) => `| ${r.title} | ${r.status} | ${r.reason} |`),
  '',
];
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, lines.join('\n'));
console.log(`wrote ${out}: ${count('pass')} pass, ${count('FAIL')} fail, ${count('skipped')} skipped`);
