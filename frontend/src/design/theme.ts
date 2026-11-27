import { theme, type ThemeConfig } from 'antd'

export type ThemeMode = 'light' | 'dark'

/**
 * Design tokens for ExpenseFlow. Defined once here; every screen gets them through ConfigProvider.
 * colorPrimary is Ant blue-7 (#0958d9): ~6:1 contrast on white, so primary links/text pass WCAG AA.
 */
export const brandTokens = {
  colorPrimary: '#0958d9',
  colorSuccess: '#389e0d',
  // Ant gold-9: ~6.8:1 on white and ~5.5:1 on antd 6's filled warning tag background rgb(237,232,213).
  // gold-6 #d48806 (~2.9:1) and gold-8 #ad6800 (~3.6:1 on the tag) failed axe color-contrast (T-506).
  colorWarning: '#874d00',
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
      // Secondary/description text (Typography type="secondary", Result subtitles, form help) at >= 4.5:1;
      // Ant's default 45% alpha is ~3.4:1 on white (T-506).
      colorTextDescription: mode === 'dark' ? 'rgba(255, 255, 255, 0.68)' : 'rgba(0, 0, 0, 0.65)',
      // Dark mode: lighter primary/warning keep AA contrast on the dark background.
      ...(mode === 'dark' ? { colorPrimary: '#4096ff', colorInfo: '#4096ff', colorWarning: '#d89614' } : {}),
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
