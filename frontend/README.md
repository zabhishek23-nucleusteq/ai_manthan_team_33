# ExpenseFlow frontend

React 19 + Vite + TypeScript, Ant Design 6, React Router 7, TanStack Query 5, Vitest.

```
npm ci
npm run dev        # http://localhost:5173, /api proxied to http://localhost:8080 (override: VITE_API_PROXY)
npm run lint       # oxlint
npm test -- --run  # vitest
npm run build      # tsc -b && vite build
```

- Theme tokens (light + dark): `src/design/theme.ts`, applied by `src/design/ThemeProvider.tsx`.
- Showcase: `/design` (tokens, Card/Statistic, claim status Tags, Form, Table, page states).
- Shared states for every screen: `PageLoading`, `PageEmpty`, `PageError` in `src/components/PageState.tsx`.
- Claim status -> Tag mapping: `src/design/claimStatus.ts` (provisional until the contract enum lands).
- API layer `src/api`: `api.get/post/put/patch/delete` (Bearer token, typed `ApiError {status, code, message, details}`, 401 -> logout),
  `useIdempotencyKey()` for state-changing POSTs, contract types in `types.ts`, `QueryProvider` (no retry on 4xx).
- Mock mode `VITE_MOCK=1`: auth + `src/api/mock` handlers (departments, claims CRUD, submit with idempotency replay).
  Add handlers for your endpoints with `mockRoute(method, '/api/x/{id}', handler)` in `src/api/mock/handlers.ts`.
- Docker: `Dockerfile` builds and serves with nginx (`nginx.conf`: SPA fallback, `/api` -> `backend:8080`).
