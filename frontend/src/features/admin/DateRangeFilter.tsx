import { DatePicker, Segmented, Space } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import type { Range } from './adminApi'
import { type Preset, rangeFor } from './adminModel'

interface Props {
  value: Range
  onChange: (r: Range) => void
}

/** Shared dashboard date filter: 1D / 7D / 1M / Custom (custom = whole days, to exclusive). */
export function DateRangeFilter({ onChange }: Props) {
  const [preset, setPreset] = useState<Preset>('7D')
  return (
    <Space wrap>
      <Segmented<Preset>
        aria-label="Date range"
        options={['1D', '7D', '1M', 'Custom']}
        value={preset}
        onChange={(p) => {
          setPreset(p)
          if (p !== 'Custom') onChange(rangeFor(p))
        }}
      />
      {preset === 'Custom' ? (
        <DatePicker.RangePicker
          aria-label="Custom date range"
          allowClear={false}
          disabledDate={(d) => d.isAfter(dayjs(), 'day')}
          onChange={(v) => {
            if (v && v[0] && v[1]) {
              onChange({ from: v[0].startOf('day').toISOString(), to: v[1].add(1, 'day').startOf('day').toISOString() })
            }
          }}
        />
      ) : null}
    </Space>
  )
}
