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
  /** user id from the mock bearer token, or null when missing/invalid */
  userId: number | null
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

function decodeToken(auth: string | undefined): { userId: number | null; roles: string[] } {
  const token = auth?.startsWith('Bearer ') ? auth.slice(7) : null
  if (!token) return { userId: null, roles: [] }
  try {
    const p = JSON.parse(atob(token.split('.')[1])) as { sub: string; roles?: string[]; exp?: number }
    if (p.exp && p.exp * 1000 < Date.now()) return { userId: null, roles: [] }
    return { userId: Number(p.sub), roles: p.roles ?? [] }
  } catch {
    return { userId: null, roles: [] }
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
    const { userId, roles } = decodeToken(headers.Authorization)
    if (!r.public && userId === null) {
      out = err(401, 'UNAUTHENTICATED', 'Authentication required')
      break
    }
    const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(match[i + 1])]))
    out = await r.handler({ method: m, path: u.pathname, params, query: u.searchParams, headers, body, userId, roles })
    break
  }
  const hasBody = out.body !== undefined && out.status !== 204
  return new Response(hasBody ? JSON.stringify(out.body) : null, {
    status: out.status,
    headers: { ...(hasBody ? { 'Content-Type': 'application/json' } : {}), ...out.headers },
  })
}
