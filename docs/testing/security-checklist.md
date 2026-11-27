# Security checklist (OWASP Top 10 style)

Method: Claude reviewed the security-relevant code (`SecurityConfig`, `AuthService`, `JwtTokenService`, `AdminController`, compose/env files) and ran live probes against the compose stack on 2026-11-27. A person confirmed each finding before it was filed. Real issues became bug tasks; false alarms and accepted risks are listed with the reason.

Scope limit: receipt upload/download (T-016), approvals, payouts and CSV export are not merged yet. Their rows are marked "pending" and must be re-checked at the next gate.

| # | Area (OWASP) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | A01 Broken access control | Every protected endpoint: no token 401, wrong role 403 | Pass | [rbac-matrix.md](rbac-matrix.md): 63 passed, 0 failed |
| 2 | A01 | Non-admin cannot learn the admin API schema from validation errors | Pass after fix | was T-502 (400 before 403); `/api/admin/**` now `hasRole` in the filter chain |
| 3 | A01 | Sign-up cannot create a staff role (extra `roles`/`role` fields ignored) | Pass | `api/rbac.spec.ts` sign-up tests |
| 4 | A01 | Employee reading another employee's claim gets 404 (no enumeration) | Pending | spec written, skipped until claims API (T-016) |
| 5 | A02 Cryptographic failures | Passwords hashed with BCrypt; JWT HS256; secret must be >= 32 bytes | Pass | `SecurityConfig`: `BCryptPasswordEncoder`, length check at startup |
| 6 | A02 | `alg=none` and tampered-payload JWTs rejected | Pass | probe: both 401 |
| 7 | A02 | Default JWT secret in `application.yml` and compose (`dev-only-change-me...`) | Accepted risk | Synthetic demo data only; real deployments set `JWT_SECRET` in `.env` (git-ignored, `.env.example` documents it) |
| 8 | A03 Injection | SQL-style input in login email | Pass | probe `a'--@x.test` -> 401; JPA parameterised queries |
| 9 | A04 Insecure design | Token lifetime 8 h with roles inside the token: a role change applies only after expiry | Accepted risk | Documented trade-off for a stateless demo; shorten `app.jwt.ttl` if needed |
| 10 | A05 Misconfiguration | `/actuator/env`, `/actuator/heapdump` not exposed | Pass | probe: 401 |
| 11 | A05 | `/actuator/prometheus` and `/v3/api-docs` are public | Accepted risk | Needed for Prometheus and the demo; in production restrict to the internal network |
| 12 | A05 | Security headers on the API | Pass | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store` |
| 13 | A05 | Security headers on the frontend (nginx) | Pass after fix | bug T-508 fixed; verified CSP, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy` |
| 14 | A07 Auth failures | Brute-force protection on login | Pass after fix | bug T-507 fixed; verified: 5 wrong passwords -> 401, then 429 |
| 15 | A07 | No user enumeration on login | Pass | unknown email and wrong password return identical body; dummy BCrypt hash equalises timing |
| 16 | A08 Integrity | CSRF | Not applicable | stateless Bearer tokens, no cookies; CSRF disabled deliberately |
| 17 | A06 Vulnerable components | Frontend dependencies | Pass | `npm audit --omit=dev --audit-level=high` in CI: 0 vulnerabilities |
| 18 | A06 | Maven dependencies | Pass | CI: CycloneDX SBOM + OSV scan, 126 + 62 packages, no issues |
| 19 | A09 Logging | Audit trail append-only for claim actions | Pending | audit tables exist (T-018); verify at S3 gate |
| 20 | A10 SSRF / uploads | Receipt upload type/size/path handling | Pending | receipts API not merged (T-016) |
| 21 | Secrets | No secrets committed | Pass | `.env` git-ignored; only dev defaults in `.env.example` |

False alarms: none so far. Row 9 and 11 were considered and accepted with the reasons above.
