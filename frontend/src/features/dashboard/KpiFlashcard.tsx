import { Card, Statistic, Typography, theme } from 'antd'
import { useState, type ReactNode } from 'react'

/**
 * KPI "flashcard": front = value, back = what the number means. Click, Enter or Space flips it.
 * Respects prefers-reduced-motion (no 3D turn, instant swap).
 */
export function KpiFlashcard({
  title,
  value,
  icon,
  color,
  definition,
  testId,
}: {
  title: string
  value: ReactNode
  icon: ReactNode
  color: string
  definition: string
  testId: string
}) {
  const [flipped, setFlipped] = useState(false)
  const { token } = theme.useToken()
  const face = {
    position: 'absolute' as const,
    inset: 0,
    backfaceVisibility: 'hidden' as const,
    WebkitBackfaceVisibility: 'hidden' as const,
  }
  return (
    <div
      role="button"
      tabIndex={0}
      aria-pressed={flipped}
      aria-label={`${title}: ${typeof value === 'string' || typeof value === 'number' ? value : ''}. ${flipped ? definition : 'Press to see what this means.'}`}
      data-testid={testId}
      onClick={() => setFlipped((f) => !f)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          setFlipped((f) => !f)
        }
      }}
      className="kpi-flashcard"
      style={{ perspective: 900, height: 128, cursor: 'pointer', outlineOffset: 2 }}
    >
      <div
        className="kpi-flashcard-inner"
        style={{
          position: 'relative',
          height: '100%',
          transformStyle: 'preserve-3d',
          transition: 'transform 0.45s ease',
          transform: flipped ? 'rotateY(180deg)' : 'none',
        }}
      >
        <Card size="small" style={{ ...face, borderTop: `4px solid ${color}` }} styles={{ body: { padding: 14 } }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              aria-hidden
              style={{
                width: 34,
                height: 34,
                borderRadius: 8,
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 18,
                color,
                background: `${color}1f`,
                flex: 'none',
              }}
            >
              {icon}
            </span>
            <Typography.Text type="secondary">{title}</Typography.Text>
          </div>
          {/* Ant Statistic keeps the .ant-statistic-content hook used by the E2E specs (TC-14). */}
          <Statistic
            value={value as string | number}
            style={{ marginTop: 6 }}
            styles={{ content: { fontSize: 26, fontWeight: 600, fontVariantNumeric: 'tabular-nums', color: token.colorText } }}
          />
        </Card>
        <Card
          size="small"
          style={{ ...face, transform: 'rotateY(180deg)', borderTop: `4px solid ${color}`, background: token.colorFillQuaternary }}
          styles={{ body: { padding: 14 } }}
        >
          <Typography.Text strong>{title}</Typography.Text>
          <Typography.Paragraph style={{ marginTop: 6, marginBottom: 0, fontSize: 13 }}>{definition}</Typography.Paragraph>
        </Card>
      </div>
      <style>{`@media (prefers-reduced-motion: reduce){.kpi-flashcard-inner{transition:none!important}}
.kpi-flashcard:focus-visible{outline:2px solid ${token.colorPrimary};border-radius:8px}`}</style>
    </div>
  )
}
