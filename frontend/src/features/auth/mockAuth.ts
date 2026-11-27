import { ApiError, type LoginRequest, type LoginResponse, type Role, type SignupRequest, type User } from './types'

/**
 * Mock auth (VITE_MOCK=1) used until the backend auth (T-004) is merged.
 * Synthetic demo users from docs/architecture/rbac.md §4; password for all: Demo@123.
 */
export const DEMO_PASSWORD = 'Demo@123'

interface MockUser extends User {
  password: string
}

const users: MockUser[] = [
  { id: 1, name: 'Priya Employee', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'] },
  { id: 2, name: 'Rahul Manager', email: 'manager@expenseflow.test', roles: ['EMPLOYEE', 'MANAGER'] },
  { id: 3, name: 'Anita Auditor', email: 'auditor@expenseflow.test', roles: ['EMPLOYEE', 'FINANCE_AUDITOR'] },
  {
    id: 4,
    name: 'Farhan Head',
    email: 'head@expenseflow.test',
    roles: ['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'],
  },
  { id: 5, name: 'Sam Admin', email: 'admin@expenseflow.test', roles: ['SUPER_ADMIN'] },
  { id: 6, name: 'Meera Backup', email: 'manager2@expenseflow.test', roles: ['EMPLOYEE', 'MANAGER'] },
].map((u) => ({ ...u, roles: u.roles as Role[], password: DEMO_PASSWORD }))

let nextId = 100
const EXPIRES_IN = 8 * 60 * 60

const delay = (ms = 300) => new Promise((r) => setTimeout(r, ms))

function publicUser({ password: _pw, ...u }: MockUser): User {
  return u
}

function tokenFor(u: User): string {
  // Unsigned fake JWT; only the mock backend reads it.
  const payload = btoa(JSON.stringify({ sub: String(u.id), roles: u.roles, exp: Math.floor(Date.now() / 1000) + EXPIRES_IN }))
  return `mock.${payload}.sig`
}

export const mockAuthApi = {
  async login(req: LoginRequest): Promise<LoginResponse> {
    await delay()
    const u = users.find((x) => x.email.toLowerCase() === req.email.trim().toLowerCase())
    if (!u || u.password !== req.password) {
      throw new ApiError(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' })
    }
    const user = publicUser(u)
    return { accessToken: tokenFor(user), tokenType: 'Bearer', expiresIn: EXPIRES_IN, user }
  },

  async signup(req: SignupRequest): Promise<User> {
    await delay()
    if (users.some((x) => x.email.toLowerCase() === req.email.trim().toLowerCase())) {
      throw new ApiError(409, { code: 'EMAIL_TAKEN', message: 'Email already registered' })
    }
    const u: MockUser = {
      id: nextId++,
      name: req.name.trim(),
      email: req.email.trim().toLowerCase(),
      roles: ['EMPLOYEE'],
      password: req.password,
    }
    users.push(u)
    return publicUser(u)
  },

  async me(token: string): Promise<User> {
    await delay(50)
    try {
      const payload = JSON.parse(atob(token.split('.')[1])) as { sub: string; exp: number }
      const u = users.find((x) => String(x.id) === payload.sub)
      if (u && payload.exp * 1000 > Date.now()) return publicUser(u)
    } catch {
      /* fall through */
    }
    throw new ApiError(401, { code: 'UNAUTHENTICATED', message: 'Authentication required' })
  },
}
