import type { User } from './types'

/**
 * Session store: token kept in memory and mirrored to sessionStorage (team-standards §5),
 * so a refresh keeps you logged in but closing the tab logs you out.
 * The API client (T-010) should use getAccessToken() for the Bearer header and call
 * expireSession() on any 401.
 */
export interface Session {
  accessToken: string
  expiresAt: number // epoch ms
  user: User
}

export const SESSION_KEY = 'ef.session'

type Listener = (s: Session | null) => void

let current: Session | null = null
let loaded = false
const listeners = new Set<Listener>()

function load(): Session | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as Session
    if (!s?.accessToken || !s.user || typeof s.expiresAt !== 'number') return null
    return s
  } catch {
    return null
  }
}

export function getSession(): Session | null {
  if (!loaded) {
    current = load()
    loaded = true
  }
  if (current && current.expiresAt <= Date.now()) {
    clearSession()
  }
  return current
}

export function getAccessToken(): string | null {
  return getSession()?.accessToken ?? null
}

export function setSession(s: Session): void {
  current = s
  loaded = true
  try {
    window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(s))
  } catch {
    /* storage blocked: memory only */
  }
  listeners.forEach((l) => l(s))
}

export function clearSession(): void {
  current = null
  loaded = true
  try {
    window.sessionStorage.removeItem(SESSION_KEY)
  } catch {
    /* ignore */
  }
  listeners.forEach((l) => l(null))
}

export function subscribeSession(l: Listener): () => void {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** Build the login URL that returns the user to `path` afterwards. */
export function loginUrlFor(path: string): string {
  return path && path !== '/' ? `/login?next=${encodeURIComponent(path)}` : '/login'
}

/** Call on any 401 from the API: clears the session and goes to Login, remembering where we were. */
export function expireSession(): void {
  clearSession()
  const { pathname, search } = window.location
  if (pathname.startsWith('/login')) return
  window.location.assign(`${loginUrlFor(pathname + search)}${pathname + search === '/' ? '?' : '&'}expired=1`)
}

/** Test helper: forget the in-memory copy so the next read goes back to sessionStorage. */
export function resetSessionCacheForTests(): void {
  current = null
  loaded = false
  listeners.clear()
}
