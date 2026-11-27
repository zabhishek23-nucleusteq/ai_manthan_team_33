import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '../features/auth/authApi'
import { resetSessionCacheForTests, setSession } from '../features/auth/session'
import { api, isMockAuth } from './client'
import { resetMockState } from './mock/handlers'
import { decodeBase64Url, decodeToken, setMockLatency } from './mock/router'

const fetchMock = vi.fn<typeof fetch>()

/** A token shaped like the backend's HS256 JWT: base64url payload without padding. */
function realStyleJwt(payload: object): string {
  const b64url = (o: object) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64url({ alg: 'HS256' })}.${b64url(payload)}.signature`
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  window.sessionStorage.clear()
  resetSessionCacheForTests()
  setMockLatency(0)
  resetMockState()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('mock switches', () => {
  it('VITE_MOCK_AUTH defaults to VITE_MOCK and can override it', () => {
    vi.stubEnv('VITE_MOCK', '1')
    vi.stubEnv('VITE_MOCK_AUTH', '')
    expect(isMockAuth()).toBe(true)
    vi.stubEnv('VITE_MOCK_AUTH', '0')
    expect(isMockAuth()).toBe(false)
    vi.stubEnv('VITE_MOCK', '0')
    vi.stubEnv('VITE_MOCK_AUTH', '1')
    expect(isMockAuth()).toBe(true)
  })

  it('real auth calls the backend even when the rest of /api is mocked', async () => {
    vi.stubEnv('VITE_MOCK', '1')
    vi.stubEnv('VITE_MOCK_AUTH', '0')
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    await expect(authApi.login({ email: 'x@expenseflow.test', password: 'bad' })).rejects.toMatchObject({
      status: 401,
      code: 'INVALID_CREDENTIALS',
    })
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/login')
  })
})

describe('mock /api with a real backend JWT', () => {
  it('decodes base64url payloads without padding', () => {
    const s = JSON.stringify({ sub: '42', email: 'a+b@expenseflow.test', n: '???>>>' })
    const b64url = btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    expect(decodeBase64Url(b64url)).toBe(s)
    expect(decodeToken(`Bearer ${realStyleJwt({ sub: '42', email: 'e@x.test', roles: ['EMPLOYEE'] })}`)).toEqual({
      userId: 42,
      email: 'e@x.test',
      roles: ['EMPLOYEE'],
    })
    expect(decodeToken(`Bearer ${realStyleJwt({ sub: '42', exp: 1 })}`).userId).toBeNull()
  })

  it('the demo employee sees the fixture claims whatever their real user id is', async () => {
    vi.stubEnv('VITE_MOCK', '1')
    vi.stubEnv('VITE_MOCK_AUTH', '0')
    setSession({
      accessToken: realStyleJwt({
        sub: '777',
        email: 'employee@expenseflow.test',
        roles: ['EMPLOYEE'],
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
      expiresAt: Date.now() + 3_600_000,
      user: { id: 777, name: 'Priya Employee', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'] },
    })
    const page = await api.get<{ total: number }>('/api/claims')
    expect(page.total).toBe(10)
    await expect(api.get('/api/claims/1001')).resolves.toMatchObject({ code: 'DEMO-1001' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('another real user whose id collides with a fixture owner id sees none of them', async () => {
    vi.stubEnv('VITE_MOCK', '1')
    vi.stubEnv('VITE_MOCK_AUTH', '0')
    setSession({
      accessToken: realStyleJwt({
        sub: '1',
        email: 'new.person@expenseflow.test',
        roles: ['EMPLOYEE'],
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
      expiresAt: Date.now() + 3_600_000,
      user: { id: 1, name: 'New Person', email: 'new.person@expenseflow.test', roles: ['EMPLOYEE'] },
    })
    await expect(api.get<{ total: number }>('/api/claims')).resolves.toMatchObject({ total: 0 })
    await expect(api.get('/api/claims/1001')).rejects.toMatchObject({ status: 404 })
  })
})
