/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "1" = use mock auth/API (until the backend endpoints are merged). */
  readonly VITE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
