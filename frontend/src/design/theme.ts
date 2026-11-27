import { theme, type ThemeConfig } from 'antd'

export type ThemeMode = 'light' | 'dark'

/**
 * Design tokens for ExpenseFlow. Defined once here; every screen gets them through ConfigProvider.
 * colorPrimary is Ant blue-7 (#0958d9): ~6:1 contrast on white, so primary links/text pass WCAG AA.
 */
export const brandTokens = {
  colorPrimary: '#0958d9',
  colorSuccess: '#389e0d',
  colorWarning: '#d48806',
  colorError: '#cf1322',
  colorInfo: '#0958d9',
  borderRadius: 8,
  fontSize: 14,
  fontFamily:
    "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif",
} as const

export function getThemeConfig(mode: ThemeMode): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      ...brandTokens,
      // Dark mode: a lighter primary keeps AA contrast on the dark background.
      ...(mode === 'dark' ? { colorPrimary: '#4096ff', colorInfo: '#4096ff' } : {}),
    },
    components: {
      Layout: mode === 'dark' ? {} : { headerBg: '#ffffff', bodyBg: '#f5f7fa' },
    },
  }
}

export const THEME_STORAGE_KEY = 'ef.theme'

export function readStoredMode(): ThemeMode {
  try {
    const v = window.localStorage.getItem(THEME_STORAGE_KEY)
    if (v === 'light' || v === 'dark') return v
  } catch {
    /* storage blocked: fall through */
  }
  try {
    if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) return 'dark'
  } catch {
    /* no matchMedia */
  }
  return 'light'
}

export function storeMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode)
  } catch {
    /* ignore */
  }
}
