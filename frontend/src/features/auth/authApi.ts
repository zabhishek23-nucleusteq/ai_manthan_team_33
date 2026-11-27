import { apiFetch, isMockMode } from '../../api/client'
import { mockAuthApi } from './mockAuth'
import type { LoginRequest, LoginResponse, SignupRequest, User } from './types'

export { isMockMode }

// `anonymous`: no stored token and no 401 -> logout redirect (a wrong password must stay on the Login page).
const realAuthApi = {
  login: (req: LoginRequest) =>
    apiFetch<LoginResponse>('/api/auth/login', { method: 'POST', body: req, anonymous: true }),
  signup: (req: SignupRequest) => apiFetch<User>('/api/auth/signup', { method: 'POST', body: req, anonymous: true }),
  me: (token: string) =>
    apiFetch<User>('/api/auth/me', { anonymous: true, headers: { Authorization: `Bearer ${token}` } }),
}

export const authApi = {
  login: (req: LoginRequest) => (isMockMode() ? mockAuthApi.login(req) : realAuthApi.login(req)),
  signup: (req: SignupRequest) => (isMockMode() ? mockAuthApi.signup(req) : realAuthApi.signup(req)),
  me: (token: string) => (isMockMode() ? mockAuthApi.me(token) : realAuthApi.me(token)),
}
