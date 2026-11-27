/**
 * Tiny in-browser mock backend for VITE_MOCK=1. Feature owners register handlers for their paths
 * (see ./handlers.ts); responses must match contracts/openapi.yaml.
 */
export interface MockRequest {
  method: string
  path: string
  params: Record<string, string>
  query: URLSearchParams
  headers: Record<string, string>
  body: unknown
  /** user id (`sub`) from the bearer token (mock or real JWT), or null when missing/invalid */
  userId: number | null
  /** `email` claim; lets fixtures owned by the demo employee work with a real backend login too */
  email: string | null
  roles: string[]
}

export interface MockResponse {
  status: number
  body?: unknown
  headers?: Record<string, string>
}

type Handler = (req: MockRequest) => MockResponse | Promise<MockResponse>

interface Route {
  method: string
  pattern: RegExp
  keys: string[]
  handler: Handler
  public: boolean
}

const routes: Route[] = []

export function mockRoute(method: string, path: string, handler: Handler, opts: { public?: boolean } = {}): void {
  const keys: string[] = []
  const pattern = new RegExp(
    '^' +
      path.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\\?\{(\w+)\\?\}|:(\w+)/g, (_m, a: string, b: string) => {
        keys.push(a ?? b)
        return '([^/]+)'
      }) +
      '$',
  )
  routes.push({ method: method.toUpperCase(), pattern, keys, handler, public: !!opts.public })
}

export function clearMockRoutes(): void {
  routes.length = 0
}

export const err = (status: number, code: string, message: string, details?: Record<string, unknown>): MockResponse => ({
  status,
  body: { code, message, ...(details ? { details } : {}) },
})

/** JWT payloads are base64url without padding; atob needs standard base64. */
export function decodeBase64Url(s: string): string {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
}

interface TokenInfo {
  userId: number | null
  email: string | null
  roles: string[]
}

/** Reads (does not verify) a mock token or a real backend JWT (sub = user id, email, roles). */
export function decodeToken(auth: string | undefined): TokenInfo {
  const none: TokenInfo = { userId: null, email: null, roles: [] }
  const token = auth?.startsWith('Bearer ') ? auth.slice(7) : null
  if (!token) return none
  try {
    const p = JSON.parse(decodeBase64Url(token.split('.')[1])) as {
      sub: string
      email?: string
      roles?: string[]
      exp?: number
    }
    if (p.exp && p.exp * 1000 < Date.now()) return none
    const id = Number(p.sub)
    return Number.isFinite(id) ? { userId: id, email: p.email ?? null, roles: p.roles ?? [] } : none
  } catch {
    return none
  }
}

export let mockLatencyMs = 150
export function setMockLatency(ms: number): void {
  mockLatencyMs = ms
}

let registered = false

export async function handleMockRequest(
  method: string,
  url: string,
  headers: Record<string, string>,
  body: unknown,
): Promise<Response> {
  if (!registered) {
    registered = true
    const { registerDefaultMocks } = await import('./handlers')
    registerDefaultMocks()
  }
  if (mockLatencyMs > 0) await new Promise((r) => setTimeout(r, mockLatencyMs))

  const u = new URL(url, 'http://mock.local')
  const m = method.toUpperCase()
  let out: MockResponse = err(404, 'NOT_FOUND', `No mock for ${m} ${u.pathname}`)
  for (const r of routes) {
    if (r.method !== m) continue
    const match = r.pattern.exec(u.pathname)
    if (!match) continue
    const { userId, email, roles } = decodeToken(headers.Authorization)
    if (!r.public && userId === null) {
      out = err(401, 'UNAUTHENTICATED', 'Authentication required')
      break
    }
    const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(match[i + 1])]))
    out = await r.handler({ method: m, path: u.pathname, params, query: u.searchParams, headers, body, userId, email, roles })
    break
  }
  const hasBody = out.body !== undefined && out.status !== 204
  const rawText = typeof out.body === 'string' && !!out.headers?.['Content-Type'] && !out.headers['Content-Type'].includes('json') // e.g. text/csv
  return new Response(hasBody ? (rawText ? (out.body as string) : JSON.stringify(out.body)) : null, {
    status: out.status,
    headers: { ...(hasBody ? { 'Content-Type': 'application/json' } : {}), ...out.headers },
  })
}
