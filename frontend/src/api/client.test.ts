import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSessionCacheForTests, setSession } from '../features/auth/session'
import { api, apiFetch, buildUrl, setUnauthorizedHandler } from './client'
import { ApiError, isApiError, parseApiError } from './errors'
import { newIdempotencyKey, useIdempotencyKey } from './idempotency'
import { resetMockState } from './mock/handlers'
import { setMockLatency } from './mock/router'
import { shouldRetry } from './queryClient'

const fetchMock = vi.fn<typeof fetch>()

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function sentHeaders(call = 0): Record<string, string> {
  return fetchMock.mock.calls[call][1]!.headers as Record<string, string>
}

function login(id = 1, roles = ['EMPLOYEE']) {
  const payload = btoa(JSON.stringify({ sub: String(id), roles, exp: Math.floor(Date.now() / 1000) + 3600 }))
  setSession({
    accessToken: `mock.${payload}.sig`,
    expiresAt: Date.now() + 3_600_000,
    user: { id, name: 'T', email: 't@expenseflow.test', roles: roles as never },
  })
}

beforeEach(() => {
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('ApiError parsing', () => {
  it('reads {code,message,details} from the body', async () => {
    const e = await parseApiError(
      json(409, { code: 'ILLEGAL_STATE_TRANSITION', message: 'Claim is APPROVED', details: { from: 'APPROVED' } }),
    )
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(409)
    expect(e.code).toBe('ILLEGAL_STATE_TRANSITION')
    expect(e.message).toBe('Claim is APPROVED')
    expect(e.details).toEqual({ from: 'APPROVED' })
    expect(isApiError(e, 'ILLEGAL_STATE_TRANSITION')).toBe(true)
  })

  it('falls back to defaults for empty or non-JSON bodies', async () => {
    const e1 = await parseApiError(new Response('<html>Bad gateway</html>', { status: 502 }))
    expect(e1.code).toBe('SERVER_ERROR')
    expect(e1.message).toMatch(/server had a problem/)
    const e2 = await parseApiError(new Response(null, { status: 403 }))
    expect(e2.code).toBe('FORBIDDEN')
  })

  it('turns a network failure into NETWORK_ERROR (status 0)', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(api.get('/api/departments')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })
})

describe('apiFetch', () => {
  it('attaches the Bearer token from the session', async () => {
    login()
    fetchMock.mockResolvedValueOnce(json(200, []))
    await api.get('/api/departments')
    expect(sentHeaders().Authorization).toMatch(/^Bearer mock\./)
  })

  it('sends no Authorization header when logged out or anonymous', async () => {
    fetchMock.mockImplementation(async () => json(200, {}))
    await api.get('/api/departments')
    expect(sentHeaders(0).Authorization).toBeUndefined()
    login()
    await apiFetch('/api/auth/login', { method: 'POST', body: {}, anonymous: true })
    expect(sentHeaders(1).Authorization).toBeUndefined()
  })

  it('sets the Idempotency-Key header and JSON body', async () => {
    login()
    fetchMock.mockResolvedValueOnce(json(200, { id: 1 }))
    await api.post('/api/claims/1/submit', { a: 1 }, { idempotencyKey: 'key-12345678' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/claims/1/submit')
    expect(init!.method).toBe('POST')
    expect(sentHeaders()['Idempotency-Key']).toBe('key-12345678')
    expect(sentHeaders()['Content-Type']).toBe('application/json')
    expect(init!.body).toBe('{"a":1}')
  })

  it('builds query strings and skips empty values', () => {
    expect(buildUrl('/api/claims', { status: 'DRAFT', page: 0, q: '', x: undefined, tag: ['a', 'b'] })).toBe(
      '/api/claims?status=DRAFT&page=0&tag=a&tag=b',
    )
    expect(buildUrl('/api/claims', {})).toBe('/api/claims')
  })

  it('returns undefined for 204', async () => {
    login()
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(api.delete('/api/claims/1')).resolves.toBeUndefined()
  })

  it('calls the logout hook on 401 but not for anonymous calls', async () => {
    const hook = vi.fn()
    const prev = setUnauthorizedHandler(hook)
    try {
      login()
      fetchMock.mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED', message: 'Authentication required' }))
      await expect(api.get('/api/claims')).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' })
      expect(hook).toHaveBeenCalledOnce()

      fetchMock.mockResolvedValueOnce(json(401, { code: 'INVALID_CREDENTIALS', message: 'Bad' }))
      await expect(apiFetch('/api/auth/login', { method: 'POST', body: {}, anonymous: true })).rejects.toMatchObject({
        code: 'INVALID_CREDENTIALS',
      })
      expect(hook).toHaveBeenCalledOnce()
    } finally {
      setUnauthorizedHandler(prev)
    }
  })

  it('does not call the hook on 403', async () => {
    const hook = vi.fn()
    const prev = setUnauthorizedHandler(hook)
    login()
    fetchMock.mockResolvedValueOnce(json(403, { code: 'FORBIDDEN', message: 'Access denied' }))
    await expect(api.get('/api/admin/users')).rejects.toMatchObject({ code: 'FORBIDDEN' })
    expect(hook).not.toHaveBeenCalled()
    setUnauthorizedHandler(prev)
  })
})

describe('idempotency', () => {
  it('generates UUID v4 keys within the contract length', () => {
    const k = newIdempotencyKey()
    expect(k).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(k.length).toBeGreaterThanOrEqual(8)
    expect(k.length).toBeLessThanOrEqual(64)
    expect(newIdempotencyKey()).not.toBe(k)
  })

  it('keeps one key per submission until reset', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const first = result.current.key()
    expect(result.current.key()).toBe(first)
    result.current.reset()
    expect(result.current.key()).not.toBe(first)
  })
})

describe('query retry policy', () => {
  it('retries network and 5xx, never 4xx', () => {
    expect(shouldRetry(0, new ApiError(0))).toBe(true)
    expect(shouldRetry(0, new ApiError(503))).toBe(true)
    expect(shouldRetry(0, new ApiError(404))).toBe(false)
    expect(shouldRetry(0, new ApiError(401))).toBe(false)
    expect(shouldRetry(2, new ApiError(503))).toBe(false)
  })
})

describe('mock mode (VITE_MOCK=1)', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MOCK', '1')
    setMockLatency(0)
    resetMockState()
  })

  it('serves contract-shaped fixtures without calling fetch', async () => {
    login(1)
    const page = await api.get<{ items: Array<{ code: string; status: string }>; total: number }>('/api/claims', {
      size: 5,
    })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(page.total).toBe(10)
    expect(page.items).toHaveLength(5)
    expect(page.items[0]).toEqual(
      expect.objectContaining({ code: expect.stringMatching(/^DEMO-10/), lineCount: expect.any(Number) }),
    )
    const drafts = await api.get<{ items: unknown[] }>('/api/claims', { status: 'DRAFT' })
    expect(drafts.items).toHaveLength(1)
  })

  it('returns 401 without a token and 404 for claims you do not own', async () => {
    const prev = setUnauthorizedHandler(() => {})
    await expect(api.get('/api/claims')).rejects.toMatchObject({ status: 401 })
    login(2, ['EMPLOYEE', 'MANAGER'])
    await expect(api.get('/api/claims/1001')).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' })
    setUnauthorizedHandler(prev)
  })

  it('create -> submit with Idempotency-Key; replay returns the same result; missing key is rejected', async () => {
    login(1)
    const c = await api.post<{ id: number; status: string }>('/api/claims', {
      title: 'Cab to airport',
      lines: [{ category: 'CAB', amount: 400, expenseDate: '2026-11-26', vendor: 'City Cabs' }],
    })
    expect(c.status).toBe('DRAFT')
    await expect(api.post(`/api/claims/${c.id}/submit`)).rejects.toMatchObject({ code: 'VALIDATION_FAILED' })
    const first = await api.post<{ status: string; version: number }>(`/api/claims/${c.id}/submit`, undefined, {
      idempotencyKey: 'abc-12345678',
    })
    expect(first.status).toBe('APPROVED')
    const replay = await api.post<{ status: string; version: number }>(`/api/claims/${c.id}/submit`, undefined, {
      idempotencyKey: 'abc-12345678',
    })
    expect(replay).toEqual(first)
    await expect(
      api.post('/api/claims/1001/submit', undefined, { idempotencyKey: 'abc-12345678' }),
    ).rejects.toMatchObject({ code: 'IDEMPOTENCY_KEY_REUSED' })
  })

  it('validates claim input and refuses illegal transitions', async () => {
    login(1)
    await expect(api.post('/api/claims', { title: 'x', lines: [] })).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
    })
    await expect(api.put('/api/claims/1006', { title: 'Edit approved', lines: [] })).rejects.toMatchObject({
      status: 409,
      code: 'ILLEGAL_STATE_TRANSITION',
    })
  })
})
