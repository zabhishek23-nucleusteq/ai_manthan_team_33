import { Card, Empty, Typography } from 'antd'

export interface BarItem {
  label: string
  value: number
}

/**
 * Accessible horizontal bar chart without a chart library: a list of label + bar + printed value.
 * One hue only and the number is always written next to the bar, so colour never carries meaning
 * (colour-blind safe); screen readers get a plain list.
 */
export function BarList({
  title,
  items,
  format = (v) => v.toLocaleString('en-IN'),
}: {
  title: string
  items: BarItem[]
  format?: (v: number) => string
}) {
  const max = Math.max(0, ...items.map((i) => i.value))
  return (
    <Card title={title} size="small" style={{ height: '100%' }}>
      {items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No data in this range" />
      ) : (
        <ul aria-label={title} style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {items.map((i) => (
            <li key={i.label} style={{ marginBottom: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                <Typography.Text ellipsis style={{ maxWidth: '60%' }}>
                  {i.label}
                </Typography.Text>
                <Typography.Text strong>{format(i.value)}</Typography.Text>
              </div>
              <div aria-hidden style={{ background: 'var(--ef-bar-track, #e8edf3)', borderRadius: 4, height: 8 }}>
                <div
                  style={{
                    width: `${max > 0 ? Math.max(2, (i.value / max) * 100) : 0}%`,
                    height: 8,
                    borderRadius: 4,
                    background: '#1f5fa8',
                  }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
