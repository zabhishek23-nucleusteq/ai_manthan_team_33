import { describe, expect, it } from 'vitest'
import { getThemeConfig } from './theme'
import { CLAIM_STATUS, contrastRatio, TAG_TONES } from './claimStatus'

describe('accessible colours (T-506)', () => {
  it('computes WCAG contrast ratios', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 0)
    expect(contrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 5)
    expect(contrastRatio('#d48806', '#ffffff')).toBeLessThan(4.5) // the old warning colour
  })

  it.each(['light', 'dark'] as const)('every %s status tag tone is >= 4.5:1', (mode) => {
    for (const [tone, { fg, bg }] of Object.entries(TAG_TONES[mode])) {
      expect(contrastRatio(fg, bg), `${mode}/${tone}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('every claim status maps to a defined tone', () => {
    for (const { color } of Object.values(CLAIM_STATUS)) expect(TAG_TONES.light[color]).toBeDefined()
  })

  it('light theme warning and primary text are >= 4.5:1 on white', () => {
    const t = getThemeConfig('light').token!
    expect(contrastRatio(t.colorWarning as string, '#ffffff')).toBeGreaterThanOrEqual(4.5)
    expect(contrastRatio(t.colorPrimary as string, '#ffffff')).toBeGreaterThanOrEqual(4.5)
  })
})
