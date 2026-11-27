import { createContext, useContext } from 'react'
import type { ThemeMode } from './theme'

export interface ThemeModeValue {
  mode: ThemeMode
  setMode: (mode: ThemeMode) => void
  toggle: () => void
}

export const ThemeModeContext = createContext<ThemeModeValue | null>(null)

export function useThemeMode(): ThemeModeValue {
  const ctx = useContext(ThemeModeContext)
  if (!ctx) throw new Error('useThemeMode must be used inside <ThemeProvider>')
  return ctx
}
