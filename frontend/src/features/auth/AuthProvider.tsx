import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from './authApi'
import { AuthContext, type AuthValue } from './authContext'
import { clearSession, expireSession, getSession, setSession, subscribeSession, type Session } from './session'
import { ApiError, type LoginRequest, type SignupRequest } from './types'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(() => getSession())

  useEffect(() => subscribeSession(setSessionState), [])

  // Re-validate a restored token once; a 401 means it was revoked or expired server-side.
  useEffect(() => {
    const s = getSession()
    if (!s) return
    let cancelled = false
    authApi
      .me(s.accessToken)
      .then((user) => {
        if (!cancelled && getSession()?.accessToken === s.accessToken) setSession({ ...s, user })
      })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof ApiError && e.status === 401) expireSession()
      })
    return () => {
      cancelled = true
    }
  }, []) // only on mount

  const login = useCallback(async (req: LoginRequest) => {
    const res = await authApi.login({ email: req.email.trim(), password: req.password })
    setSession({ accessToken: res.accessToken, expiresAt: Date.now() + res.expiresIn * 1000, user: res.user })
    return res.user
  }, [])

  const signup = useCallback(
    (req: SignupRequest) => authApi.signup({ ...req, name: req.name.trim(), email: req.email.trim() }),
    [],
  )

  const logout = useCallback(() => clearSession(), [])

  const value = useMemo<AuthValue>(
    () => ({
      user: session?.user ?? null,
      roles: session?.user.roles ?? [],
      isAuthenticated: !!session,
      login,
      signup,
      logout,
    }),
    [session, login, signup, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
