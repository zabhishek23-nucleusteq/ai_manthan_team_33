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
- Docker: `Dockerfile` builds and serves with nginx (`nginx.conf`: SPA fallback, `/api` -> `backend:8080`).
