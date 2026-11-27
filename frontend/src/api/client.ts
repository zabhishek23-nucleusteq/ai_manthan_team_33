import { expireSession, getAccessToken } from '../features/auth/session'
import { ApiError, parseApiError } from './errors'
import { handleMockRequest } from './mock/router'

export type Query = Record<string, string | number | boolean | null | undefined | readonly (string | number)[]>

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  query?: Query
  /** JSON-serialised unless it is FormData (receipt upload). */
  body?: unknown
  /** Sent as the Idempotency-Key header (required on state-changing POSTs, NFR-IDEM). */
  idempotencyKey?: string
  headers?: Record<string, string>
  signal?: AbortSignal
  /** Skip the Bearer token and the 401 -> logout hook (login/sign-up). */
  anonymous?: boolean
  /** Always call the real backend, even in mock mode (used by auth when VITE_MOCK_AUTH=0). */
  real?: boolean
}

/** VITE_MOCK=1: use the in-browser mock backend for /api calls (except auth when VITE_MOCK_AUTH=0). */
export const isMockMode = (): boolean => import.meta.env.VITE_MOCK === '1'

/** Auth can be switched separately: VITE_MOCK_AUTH=0 logs in against the real backend while other APIs stay mocked. */
export const isMockAuth = (): boolean => {
  const v = import.meta.env.VITE_MOCK_AUTH
  return v === undefined || v === '' ? isMockMode() : v === '1'
}

let onUnauthorized: () => void = expireSession

/** Override what happens on a 401 (tests, or a future refresh flow). Returns the previous handler. */
export function setUnauthorizedHandler(fn: () => void): () => void {
  const prev = onUnauthorized
  onUnauthorized = fn
  return prev
}

export function buildUrl(path: string, query?: Query): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) v.forEach((x) => params.append(k, String(x)))
    else params.append(k, String(v))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}

/**
 * Typed fetch for /api/**: adds Bearer token, JSON body, Idempotency-Key; turns non-2xx into ApiError;
 * on 401 (for authenticated calls) clears the session and sends the user to Login.
 */
export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, idempotencyKey, headers = {}, signal, anonymous = false, real = false } = opts
  const url = buildUrl(path, query)

  const h: Record<string, string> = { Accept: 'application/json', ...headers }
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData
  if (body !== undefined && !isForm) h['Content-Type'] = 'application/json'
  if (idempotencyKey) h['Idempotency-Key'] = idempotencyKey
  if (!anonymous) {
    const token = getAccessToken()
    if (token) h.Authorization = `Bearer ${token}`
  }

  let res: Response
  try {
    if (isMockMode() && !real) {
      res = await handleMockRequest(method, url, h, body)
    } else {
      res = await fetch(url, {
        method,
        headers: h,
        body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
        signal,
      })
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0)
  }

  if (!res.ok) {
    const err = await parseApiError(res)
    if (res.status === 401 && !anonymous) onUnauthorized()
    throw err
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  const type = res.headers.get('Content-Type') ?? ''
  return (type.includes('json') ? JSON.parse(text) : text) as T
}

export const api = {
  get: <T>(path: string, query?: Query, opts?: Omit<RequestOptions, 'method' | 'query'>) =>
    apiFetch<T>(path, { ...opts, method: 'GET', query }),
  post: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiFetch<T>(path, { ...opts, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiFetch<T>(path, { ...opts, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>) =>
    apiFetch<T>(path, { ...opts, method: 'PATCH', body }),
  delete: <T = void>(path: string, opts?: Omit<RequestOptions, 'method'>) =>
    apiFetch<T>(path, { ...opts, method: 'DELETE' }),
}
