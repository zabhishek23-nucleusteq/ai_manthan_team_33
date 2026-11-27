import { apiFetch, isMockAuth } from '../../api/client'
import { mockAuthApi } from './mockAuth'
import type { LoginRequest, LoginResponse, SignupRequest, User } from './types'

export { isMockAuth }

// Always real HTTP (never the generic /api mock router): auth has its own mock in mockAuth.ts.
// `anonymous`: no stored token and no 401 -> logout redirect (a wrong password must stay on the Login page).
const realAuthApi = {
  login: (req: LoginRequest) =>
    apiFetch<LoginResponse>('/api/auth/login', { method: 'POST', body: req, anonymous: true, real: true }),
  signup: (req: SignupRequest) =>
    apiFetch<User>('/api/auth/signup', { method: 'POST', body: req, anonymous: true, real: true }),
  me: (token: string) =>
    apiFetch<User>('/api/auth/me', { anonymous: true, real: true, headers: { Authorization: `Bearer ${token}` } }),
}

export const authApi = {
  login: (req: LoginRequest) => (isMockAuth() ? mockAuthApi.login(req) : realAuthApi.login(req)),
  signup: (req: SignupRequest) => (isMockAuth() ? mockAuthApi.signup(req) : realAuthApi.signup(req)),
  me: (token: string) => (isMockAuth() ? mockAuthApi.me(token) : realAuthApi.me(token)),
}
