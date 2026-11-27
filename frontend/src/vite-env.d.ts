/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "1" = use the in-browser mock backend for /api (until the backend endpoints are merged). */
  readonly VITE_MOCK?: string
  /** Overrides VITE_MOCK for auth only: "0" = real login/sign-up/me against the backend. */
  readonly VITE_MOCK_AUTH?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
