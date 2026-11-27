import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { Role } from './roles'

/**
 * What the app shell needs from authentication. L4's AuthProvider (T-005) supplies this value
 * through <SessionContext.Provider>; until then DemoSessionProvider fakes a login per demo role.
 */
export interface SessionUser {
  id: string
  name: string
  email: string
  roles: Role[]
}

export interface SessionValue {
  user: SessionUser | null
  signIn: (user: SessionUser) => void
  logout: () => void
}

const noop = () => {}
export const SessionContext = createContext<SessionValue>({ user: null, signIn: noop, logout: noop })

export function useSession(): SessionValue {
  return useContext(SessionContext)
}

/** Demo users from rbac.md §4 (synthetic). Used by the temporary demo login until T-005 lands. */
export const DEMO_USERS: readonly SessionUser[] = [
  { id: 'demo-employee', name: 'Priya Employee', email: 'employee@expenseflow.test', roles: ['EMPLOYEE'] },
  { id: 'demo-manager', name: 'Rahul Manager', email: 'manager@expenseflow.test', roles: ['EMPLOYEE', 'MANAGER'] },
  { id: 'demo-auditor', name: 'Anita Auditor', email: 'auditor@expenseflow.test', roles: ['EMPLOYEE', 'FINANCE_AUDITOR'] },
  {
    id: 'demo-head',
    name: 'Farhan Head',
    email: 'head@expenseflow.test',
    roles: ['EMPLOYEE', 'FINANCE_HEAD', 'SUPER_ADMIN'],
  },
  { id: 'demo-admin', name: 'Sam Admin', email: 'admin@expenseflow.test', roles: ['SUPER_ADMIN'] },
]

const STORAGE_KEY = 'ef.demoSession'

function readStored(): SessionUser | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as SessionUser) : null
  } catch {
    return null
  }
}

function store(user: SessionUser | null) {
  try {
    if (user) window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user))
    else window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* storage blocked: session lives in memory only */
  }
}

export function DemoSessionProvider({ children, initialUser }: { children: ReactNode; initialUser?: SessionUser | null }) {
  const [user, setUser] = useState<SessionUser | null>(() => (initialUser !== undefined ? initialUser : readStored()))
  const signIn = useCallback((u: SessionUser) => {
    store(u)
    setUser(u)
  }, [])
  const logout = useCallback(() => {
    store(null)
    setUser(null)
  }, [])
  const value = useMemo(() => ({ user, signIn, logout }), [user, signIn, logout])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
