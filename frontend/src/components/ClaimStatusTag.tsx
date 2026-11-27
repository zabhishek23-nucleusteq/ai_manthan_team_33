import { Tag } from 'antd'
import { claimStatusMeta } from '../design/claimStatus'

export function ClaimStatusTag({ status }: { status: string }) {
  const { color, label } = claimStatusMeta(status)
  return (
    <Tag color={color} data-status={status}>
      {label}
    </Tag>
  )
}
