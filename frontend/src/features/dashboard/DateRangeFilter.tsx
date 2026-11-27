import { DatePicker, Flex, Segmented, Typography } from 'antd'
import dayjs from 'dayjs'
import { MAX_DAYS, PRESET_LABELS, describeRange, presetRange, type DateRange, type Preset } from './range'

const PRESETS: Preset[] = ['1D', '7D', '1M', 'CUSTOM']

/** Shared 1 Day / 7 Days / 1 Month / Custom filter. Controlled: the page keeps the range in the URL. */
export function DateRangeFilter({ value, onChange }: { value: DateRange; onChange: (r: DateRange) => void }) {
  return (
    <Flex vertical gap={8} style={{ marginBottom: 16 }}>
      <Flex wrap gap={8} align="center">
        <Segmented<Preset>
          aria-label="Date range"
          value={value.preset}
          options={PRESETS.map((p) => ({ label: PRESET_LABELS[p], value: p }))}
          onChange={(p) => {
            if (p === 'CUSTOM') {
              onChange({ ...value, preset: 'CUSTOM' })
            } else {
              onChange(presetRange(p))
            }
          }}
        />
        {value.preset === 'CUSTOM' ? (
          <DatePicker.RangePicker
            aria-label="Custom date range"
            allowClear={false}
            value={[dayjs(value.from), dayjs(value.to)]}
            disabledDate={(d) => d.isAfter(dayjs().endOf('day'))}
            onChange={(dates) => {
              const [from, to] = dates ?? []
              if (!from || !to) return
              const start = from.startOf('day')
              let end = to.endOf('day').add(1, 'millisecond')
              if (end.diff(start, 'day', true) > MAX_DAYS) end = start.add(MAX_DAYS, 'day')
              onChange({ preset: 'CUSTOM', from: start.toISOString(), to: end.toISOString() })
            }}
          />
        ) : null}
      </Flex>
      <Typography.Text type="secondary" data-testid="range-in-words">
        {describeRange(value)}
      </Typography.Text>
    </Flex>
  )
}
