import { Button, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { VIOLATION_SHORT } from '../customer/policyHints'
import { inr } from '../customer/format'
import { approvalErrorText, LEVEL_TEXT, useInbox, type ApprovalTask } from './approvalsApi'

const PAGE_SIZE = 20

/** /approvals (FR-6): dense inbox of open steps for me, incl. delegated ones. */
export function ApprovalsInboxPage() {
  const [page, setPage] = useState(0)
  const inbox = useInbox(page, PAGE_SIZE)
  const navigate = useNavigate()

  const columns: ColumnsType<ApprovalTask> = [
    {
      title: 'Claim',
      dataIndex: 'code',
      render: (code: string, t) => (
        <Space direction="vertical" size={0}>
          <Link to={`/approvals/${t.claimId}`}>{code}</Link>
          <Typography.Text type="secondary" ellipsis style={{ maxWidth: 220 }}>
            {t.title}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: 'Employee',
      key: 'employee',
      render: (_, t) => (
        <Space direction="vertical" size={0}>
          <span>{t.employee?.name ?? '-'}</span>
          <Typography.Text type="secondary">{t.department?.name ?? ''}</Typography.Text>
        </Space>
      ),
      responsive: ['sm'],
    },
    { title: 'Total', dataIndex: 'total', align: 'right', render: (v: number) => inr(v) },
    {
      title: 'Level',
      key: 'level',
      render: (_, t) => (
        <Space size={4} wrap>
          <Tag>{LEVEL_TEXT[t.level]}</Tag>
          {t.onBehalfOf ? (
            <Tooltip title={`Delegated to you by ${t.onBehalfOf.name}`}>
              <Tag color="purple">On behalf of {t.onBehalfOf.name}</Tag>
            </Tooltip>
          ) : null}
        </Space>
      ),
    },
    {
      title: 'Age',
      dataIndex: 'ageHours',
      align: 'right',
      render: (h: number) => (h >= 48 ? <Tag color="red">{Math.round(h)} h</Tag> : `${Math.round(h)} h`),
      responsive: ['md'],
    },
    {
      title: 'Flags',
      key: 'flags',
      render: (_, t) => (
        <Space size={4} wrap>
          {t.violationCodes.map((c) => (
            <Tag key={c} color={c === 'WEEKEND_SPEND' ? 'gold' : 'orange'}>
              {VIOLATION_SHORT[c] ?? c}
            </Tag>
          ))}
          {t.sampleAudit ? <Tag color="blue">Sample audit</Tag> : null}
        </Space>
      ),
      responsive: ['md'],
    },
    {
      title: 'Action',
      key: 'open',
      render: (_, t) => (
        <Button size="small" onClick={() => navigate(`/approvals/${t.claimId}`)} aria-label={`Review ${t.code}`}>
          Review
        </Button>
      ),
    },
  ]

  return (
    <main style={{ padding: 16, maxWidth: 1200, margin: '0 auto' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={2} style={{ margin: 0 }}>
          Approvals
        </Typography.Title>
        <Link to="/delegations">Delegation</Link>
      </Space>
      <div style={{ marginTop: 16 }}>
        {inbox.isPending ? (
          <PageLoading label="Loading your approvals…" />
        ) : inbox.isError ? (
          <PageError title="Could not load approvals" message={approvalErrorText(inbox.error)} onRetry={() => void inbox.refetch()} />
        ) : inbox.data.total === 0 ? (
          <PageEmpty description="Nothing is waiting for your approval." />
        ) : (
          <Table<ApprovalTask>
            rowKey="claimId"
            size="small"
            columns={columns}
            dataSource={inbox.data.items}
            scroll={{ x: true }}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: inbox.data.total,
              onChange: (p) => setPage(p - 1),
              showSizeChanger: false,
            }}
            aria-label="Claims waiting for my approval"
          />
        )}
      </div>
    </main>
  )
}
