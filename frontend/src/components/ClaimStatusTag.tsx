import { Tag, theme } from 'antd'
import type { ReactNode } from 'react'
import { claimStatusMeta, contrastRatio, TAG_TONES } from '../design/claimStatus'

/**
 * Tag with explicit AA-contrast colours (TAG_TONES) instead of antd presets, whose filled background is derived
 * from the status colour and fails contrast (T-506). Works with or without ThemeProvider.
 */
export function ToneTag({ tone, icon, children, ...rest }: { tone: string; icon?: ReactNode; children: ReactNode; 'data-status'?: string }) {
  const { token } = theme.useToken()
  const dark = /^#[0-9a-f]{6}$/i.test(token.colorBgContainer) ? contrastRatio(token.colorBgContainer, '#000000') < 2 : false
  const palette = TAG_TONES[dark ? 'dark' : 'light']
  const t = palette[tone] ?? palette.default
  return (
    <Tag icon={icon} style={{ color: t.fg, background: t.bg, borderColor: t.bg }} {...rest}>
      {children}
    </Tag>
  )
}

export function ClaimStatusTag({ status }: { status: string }) {
  const { color, label } = claimStatusMeta(status)
  return (
    <ToneTag tone={color} data-status={status}>
      {label}
    </ToneTag>
  )
}
