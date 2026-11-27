# Defect log

Every bug found by tests, gates, SRE or review. Flow: `docs/sre/sre-handbook.md` section 4. Task ids are squad board ids.

| ID | Sev | Found by | Summary | Owner | Fix | Status |
|---|---|---|---|---|---|---|
| T-500 | SEV2 | milestone review | Mock bank CSV header differs from BR-17 bank-file format | L3 | merged | Closed |
| T-501 | SEV2 | CI (e2e-api on main) | Admin write APIs return 400 instead of 403 for non-admin roles | L3 | merged | Closed |
| T-502 | SEV3 | RBAC matrix (rbac.spec.ts) | Admin API returns 400 instead of 403 for non-admin callers with an invalid body (`@PreAuthorize` runs after body validation); fixed with `/api/admin/**` `hasRole` in the filter chain | L3 | merged; verified: matrix 63 pass, 0 fail | Closed |
| T-503 | SEV3 | CI | Admin write endpoints 400 instead of 403 (duplicate report of T-502) | L3 | merged | Closed (duplicate) |
| T-504 | SEV2 | CI | main red: known-bug markers for T-502 still in rbac.spec.ts after the fix | L1 | merged | Closed (duplicate of T-505) |
| T-505 | SEV2 | CI | Main CI red: remove fixed T-502 entries from KNOWN_BUGS (PR 40) | L6 | merged | Closed |
| T-506 | SEV3 | axe in a11y.spec.ts | Colour contrast and in-text link violations on login, sign-up, landing, 403 and My claims | L4 | - | Open (spec expects failure until fixed) |
| T-507 | SEV3 | security probe | Login has no rate limit or lockout: 25 wrong passwords in a row all answered 401 | L3 | - | Open |
| T-508 | SEV3 | security probe | Frontend nginx sends no security headers (CSP, X-Frame-Options, nosniff) | L4 | - | Open |

Process notes
- The "known bug" mechanism (`test.fail` with the bug id) keeps CI usable while a bug is open and turns CI red the moment the bug is fixed, so the marker is removed in the same PR as the fix.
- CI infrastructure issue (not a product defect): the Maven wrapper download from `repo.maven.apache.org` failed on every push to main in the backend job; fixed by `MVNW_REPOURL=https://repo1.maven.org/maven2` (T-009).
- Flaky test seen once: `auth.test.tsx` "checks that passwords match and length rules" failed once in CI and passed on re-run. Not reproduced; watch.
