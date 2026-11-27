import { Input, Select, Space, Table, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import type { AuditEvent } from '../../api/types'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { AdminShell } from './AdminShell'
import { errorText } from './adminModel'
import { type AuditFilter, useAdminAudit } from './adminApi'

const ENTITY_TYPES = ['CLAIM', 'POLICY_RULE', 'DELEGATION', 'PAYOUT_BATCH', 'USER']

/** /admin/audit (FR-15): append-only audit trail, filterable by action and entity type. */
export function AdminAuditPage() {
  const [filter, setFilter] = useState<AuditFilter>({})
  const [page, setPage] = useState(0)
  const audit = useAdminAudit(filter, page)
  const update = (f: Partial<AuditFilter>) => {
    setFilter((cur) => ({ ...cur, ...f }))
    setPage(0)
  }

  const filters = (
    <Space wrap>
      <Input.Search
        aria-label="Filter by action"
        placeholder="Action, e.g. CLAIM_APPROVED"
        allowClear
        onSearch={(v) => update({ action: v.trim().toUpperCase() || undefined })}
        style={{ width: 240 }}
      />
      <Select
        aria-label="Filter by entity type"
        style={{ width: 180 }}
        value={filter.entityType ?? ''}
        onChange={(v) => update({ entityType: v || undefined })}
        options={[{ value: '', label: 'All entities' }, ...ENTITY_TYPES.map((e) => ({ value: e, label: e }))]}
      />
    </Space>
  )

  let body
  if (audit.isPending) body = <PageLoading label="Loading audit log…" />
  else if (audit.isError) body = <PageError message={errorText(audit.error)} onRetry={() => void audit.refetch()} />
  else if (audit.data.items.length === 0) body = <PageEmpty description="No audit events match these filters" />
  else
    body = (
      <Table<AuditEvent>
        rowKey="id"
        size="small"
        dataSource={audit.data.items}
        scroll={{ x: 760 }}
        pagination={{ current: page + 1, pageSize: audit.data.size, total: audit.data.total, onChange: (p) => setPage(p - 1), showSizeChanger: false }}
        columns={[
          { title: 'When', dataIndex: 'at', render: (v: string) => dayjs(v).format('DD MMM YYYY HH:mm') },
          { title: 'Action', dataIndex: 'action' },
          { title: 'Entity', render: (_, e) => `${e.entityType ?? '-'} ${e.entityId ?? ''}` },
          {
            title: 'Actor',
            render: (_, e) => (e.actor ? e.actor.name : 'System') + (e.onBehalfOf ? ` (for ${e.onBehalfOf.name})` : ''),
          },
          {
            title: 'Change',
            render: (_, e) => (
              <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis={{ tooltip: JSON.stringify(e.after ?? {}) }}>
                {JSON.stringify(e.after ?? {})}
              </Typography.Text>
            ),
          },
        ]}
      />
    )

  return (
    <AdminShell title="Audit log" extra={filters}>
      {body}
    </AdminShell>
  )
}
