import { createContext, useContext } from 'react'
import type { LoginRequest, Role, SignupRequest, User } from './types'

export interface AuthValue {
  user: User | null
  roles: Role[]
  isAuthenticated: boolean
  login: (req: LoginRequest) => Promise<User>
  signup: (req: SignupRequest) => Promise<User>
  logout: () => void
}

export const AuthContext = createContext<AuthValue | null>(null)

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
