import { BarChartOutlined, TableOutlined } from '@ant-design/icons'
import { Card, Empty, Segmented, Table, Typography, theme } from 'antd'
import { useState, type ReactNode } from 'react'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { SERIES_COLORS } from './palette'

export interface Datum {
  label: string
  value: number
}

const compact = (v: number) =>
  v >= 1e7 ? `${(v / 1e7).toFixed(1)}Cr` : v >= 1e5 ? `${(v / 1e5).toFixed(1)}L` : v >= 1e3 ? `${(v / 1e3).toFixed(0)}k` : `${v}`

/**
 * Card around a chart with a "Show as table" toggle (every chart has a table fallback for screen readers and
 * exact numbers). Charts are SVG with role="img" and a summary label.
 */
export function ChartCard({
  title,
  data,
  format,
  children,
  extra,
  valueTitle = 'Value',
}: {
  title: string
  data: Datum[]
  format: (v: number) => string
  children: ReactNode
  extra?: ReactNode
  valueTitle?: string
}) {
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const summary = `${title}: ${data.map((d) => `${d.label} ${format(d.value)}`).join(', ')}`
  return (
    <Card
      size="small"
      title={title}
      style={{ height: '100%' }}
      extra={
        <Segmented
          size="small"
          value={view}
          onChange={(v) => setView(v as 'chart' | 'table')}
          options={[
            { value: 'chart', icon: <BarChartOutlined />, title: 'Show as chart' },
            { value: 'table', icon: <TableOutlined />, title: 'Show as table' },
          ]}
          aria-label={`${title}: chart or table`}
        />
      }
    >
      {extra}
      {data.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No data in this range" />
      ) : view === 'table' ? (
        <Table<Datum>
          size="small"
          rowKey="label"
          pagination={false}
          dataSource={data}
          columns={[
            { title: 'Item', dataIndex: 'label' },
            { title: valueTitle, dataIndex: 'value', align: 'right', render: (v: number) => format(v) },
          ]}
        />
      ) : (
        <div role="img" aria-label={summary} style={{ width: '100%', height: 240 }}>
          {children}
        </div>
      )}
    </Card>
  )
}

function useAxis() {
  const { token } = theme.useToken()
  return { tick: { fill: token.colorTextSecondary, fontSize: 12 }, grid: token.colorBorderSecondary, bg: token.colorBgElevated, text: token.colorText }
}

export function TrendChart({ data, color, format }: { data: Datum[]; color: string; format: (v: number) => string }) {
  const ax = useAxis()
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id={`trend-${color.slice(1)}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.35} />
            <stop offset="100%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid stroke={ax.grid} strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="label" tick={ax.tick} tickLine={false} axisLine={false} minTickGap={24} />
        <YAxis tick={ax.tick} tickLine={false} axisLine={false} width={48} tickFormatter={compact} />
        <Tooltip formatter={(v) => format(Number(v))} contentStyle={{ background: ax.bg, color: ax.text, borderRadius: 6 }} />
        <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2} fill={`url(#trend-${color.slice(1)})`} activeDot={{ r: 5 }} />
      </AreaChart>
    </ResponsiveContainer>
  )
}

export function BarsChart({
  data,
  format,
  horizontal = false,
  colors = SERIES_COLORS,
  onSelect,
}: {
  data: Datum[]
  format: (v: number) => string
  horizontal?: boolean
  colors?: string[]
  onSelect?: (label: string) => void
}) {
  const ax = useAxis()
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout={horizontal ? 'vertical' : 'horizontal'} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid stroke={ax.grid} strokeDasharray="3 3" horizontal={!horizontal} vertical={horizontal} />
        {horizontal ? (
          <>
            <XAxis type="number" tick={ax.tick} tickLine={false} axisLine={false} tickFormatter={compact} />
            <YAxis type="category" dataKey="label" tick={ax.tick} tickLine={false} axisLine={false} width={110} />
          </>
        ) : (
          <>
            <XAxis dataKey="label" tick={ax.tick} tickLine={false} axisLine={false} />
            <YAxis tick={ax.tick} tickLine={false} axisLine={false} width={48} tickFormatter={compact} />
          </>
        )}
        <Tooltip formatter={(v) => format(Number(v))} cursor={{ fillOpacity: 0.08 }} contentStyle={{ background: ax.bg, color: ax.text, borderRadius: 6 }} />
        <Bar dataKey="value" radius={horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]} onClick={onSelect ? (d) => onSelect(String((d as unknown as Datum).label)) : undefined} cursor={onSelect ? 'pointer' : undefined}>
          {data.map((d, i) => (
            <Cell key={d.label} fill={colors[i % colors.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

/** Donut with an interactive legend (click / Enter on a row highlights that slice). */
export function DonutWithLegend({
  title,
  data,
  format,
  focus,
  onFocus,
}: {
  title: string
  data: Datum[]
  format: (v: number) => string
  focus: string | null
  onFocus: (label: string | null) => void
}) {
  const ax = useAxis()
  const total = data.reduce((s, d) => s + d.value, 0) || 1
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
      <div style={{ flex: '1 1 180px', height: 200, minWidth: 160 }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              nameKey="label"
              innerRadius="55%"
              outerRadius="90%"
              paddingAngle={2}
              onClick={(d) => onFocus(focus === String(d.name) ? null : String(d.name))}
              cursor="pointer"
            >
              {data.map((d, i) => (
                <Cell key={d.label} fill={SERIES_COLORS[i % SERIES_COLORS.length]} fillOpacity={focus && focus !== d.label ? 0.25 : 1} />
              ))}
            </Pie>
            <Tooltip formatter={(v) => format(Number(v))} contentStyle={{ background: ax.bg, color: ax.text, borderRadius: 6 }} />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul aria-label={title} style={{ listStyle: 'none', margin: 0, padding: 0, flex: '1 1 180px' }}>
        {data.map((d, i) => {
          const active = focus === d.label
          return (
            <li key={d.label}>
              <button
                type="button"
                aria-pressed={active}
                onClick={() => onFocus(active ? null : d.label)}
                style={{
                  display: 'flex',
                  width: '100%',
                  alignItems: 'center',
                  gap: 8,
                  padding: '6px 8px',
                  border: 0,
                  borderRadius: 6,
                  cursor: 'pointer',
                  background: active ? 'rgba(31, 95, 191, 0.10)' : 'transparent',
                  color: 'inherit',
                  font: 'inherit',
                  textAlign: 'left',
                  opacity: focus && !active ? 0.55 : 1,
                }}
              >
                <span aria-hidden style={{ width: 12, height: 12, borderRadius: 3, background: SERIES_COLORS[i % SERIES_COLORS.length], flex: 'none' }} />
                <span style={{ flex: 1 }}>{d.label}</span>
                <Typography.Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {format(d.value)}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ width: 40, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                  {Math.round((d.value / total) * 100)}%
                </Typography.Text>
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
