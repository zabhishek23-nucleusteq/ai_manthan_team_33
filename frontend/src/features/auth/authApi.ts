import { mockAuthApi } from './mockAuth'
import { ApiError, type ApiErrorBody, type LoginRequest, type LoginResponse, type SignupRequest, type User } from './types'

export const isMockMode = (): boolean => import.meta.env.VITE_MOCK === '1'

async function request<T>(path: string, init: RequestInit & { token?: string } = {}): Promise<T> {
  const { token, headers, ...rest } = init
  let res: Response
  try {
    res = await fetch(path, {
      ...rest,
      headers: {
        Accept: 'application/json',
        ...(rest.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
    })
  } catch {
    throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Cannot reach the server. Check your connection.' })
  }
  if (!res.ok) {
    let body: Partial<ApiErrorBody> = {}
    try {
      body = (await res.json()) as ApiErrorBody
    } catch {
      /* non-JSON error */
    }
    throw new ApiError(res.status, body)
  }
  return (await res.json()) as T
}

const realAuthApi = {
  login: (req: LoginRequest) =>
    request<LoginResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify(req) }),
  signup: (req: SignupRequest) => request<User>('/api/auth/signup', { method: 'POST', body: JSON.stringify(req) }),
  me: (token: string) => request<User>('/api/auth/me', { token }),
}

export const authApi = {
  login: (req: LoginRequest) => (isMockMode() ? mockAuthApi.login(req) : realAuthApi.login(req)),
  signup: (req: SignupRequest) => (isMockMode() ? mockAuthApi.signup(req) : realAuthApi.signup(req)),
  me: (token: string) => (isMockMode() ? mockAuthApi.me(token) : realAuthApi.me(token)),
}
