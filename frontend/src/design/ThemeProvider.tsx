import { App as AntApp, ConfigProvider } from 'antd'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { getThemeConfig, readStoredMode, storeMode, type ThemeMode } from './theme'
import { ThemeModeContext } from './themeModeContext'

interface Props {
  children: ReactNode
  initialMode?: ThemeMode
}

/** Wraps the app in Ant ConfigProvider with our tokens and a light/dark mode switch. */
export function ThemeProvider({ children, initialMode }: Props) {
  const [mode, setModeState] = useState<ThemeMode>(() => initialMode ?? readStoredMode())

  const setMode = useCallback((m: ThemeMode) => {
    setModeState(m)
    storeMode(m)
  }, [])
  const toggle = useCallback(() => setMode(mode === 'dark' ? 'light' : 'dark'), [mode, setMode])

  useEffect(() => {
    document.documentElement.dataset.theme = mode
    document.documentElement.style.colorScheme = mode
  }, [mode])

  const value = useMemo(() => ({ mode, setMode, toggle }), [mode, setMode, toggle])
  const themeConfig = useMemo(() => getThemeConfig(mode), [mode])

  return (
    <ThemeModeContext.Provider value={value}>
      <ConfigProvider theme={themeConfig}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </ThemeModeContext.Provider>
  )
}
