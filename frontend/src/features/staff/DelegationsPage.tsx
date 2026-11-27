import { Alert, Button, Card, DatePicker, Form, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd'
import type { Dayjs } from 'dayjs'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useIdempotencyKey } from '../../api/idempotency'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { fmtDate } from '../customer/format'
import { validateRange } from './delegationRules'
import { approvalErrorText, useApprovers, useCreateDelegation, useDelegations, useRevokeDelegation, type Delegation } from './approvalsApi'

interface FormValues {
  delegateId?: number
  range?: [Dayjs, Dayjs]
}

/** /delegations (FR-8): hand my approvals to a colleague for a date range; list and revoke. */
export function DelegationsPage() {
  const [form] = Form.useForm<FormValues>()
  const [q, setQ] = useState('')
  const [done, setDone] = useState<string | null>(null)
  const list = useDelegations()
  const approvers = useApprovers(q)
  const create = useCreateDelegation()
  const revoke = useRevokeDelegation()
  const idem = useIdempotencyKey()

  const onFinish = (v: FormValues) => {
    setDone(null)
    create.mutate(
      { input: { delegateId: v.delegateId!, from: v.range![0].format('YYYY-MM-DD'), to: v.range![1].format('YYYY-MM-DD') }, idempotencyKey: idem.key() },
      {
        onSuccess: (d) => {
          setDone(`Your approvals go to ${d.delegate.name} from ${fmtDate(d.from)} to ${fmtDate(d.to)}.`)
          form.resetFields()
          idem.reset()
        },
      },
    )
  }

  const columns = [
    { title: 'From', key: 'delegator', render: (_: unknown, d: Delegation) => d.delegator.name },
    { title: 'To', key: 'delegate', render: (_: unknown, d: Delegation) => d.delegate.name },
    { title: 'Period', key: 'period', render: (_: unknown, d: Delegation) => `${fmtDate(d.from)} – ${fmtDate(d.to)}` },
    { title: 'Status', key: 'active', render: (_: unknown, d: Delegation) => (d.active ? <Tag color="green">Active</Tag> : <Tag>Revoked</Tag>) },
    {
      title: 'Action',
      key: 'revoke',
      render: (_: unknown, d: Delegation) =>
        d.active ? (
          <Popconfirm title="Revoke this delegation?" okText="Revoke" onConfirm={() => revoke.mutate(d.id)}>
            <Button size="small" danger aria-label={`Revoke delegation to ${d.delegate.name}`}>
              Revoke
            </Button>
          </Popconfirm>
        ) : null,
    },
  ]

  return (
    <main style={{ padding: 16, maxWidth: 1000, margin: '0 auto' }}>
      <Link to="/approvals">← Back to approvals</Link>
      <Typography.Title level={2}>Delegation</Typography.Title>

      <Card title="Delegate my approvals" style={{ marginBottom: 16 }}>
        <div aria-live="polite">
          {done ? <Alert type="success" showIcon message={done} style={{ marginBottom: 12 }} /> : null}
          {create.isError ? <Alert type="error" showIcon role="alert" message={approvalErrorText(create.error)} style={{ marginBottom: 12 }} /> : null}
        </div>
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark={false} disabled={create.isPending}>
          <Form.Item label="Delegate" name="delegateId" rules={[{ required: true, message: 'Choose who approves for you.' }]}>
            <Select
              showSearch
              filterOption={false}
              onSearch={setQ}
              loading={approvers.isFetching}
              placeholder="Search by name or email"
              notFoundContent={approvers.isError ? 'Could not load approvers' : 'No approver found'}
              options={(approvers.data ?? []).map((u) => ({ value: u.id, label: `${u.name} (${u.email})` }))}
              aria-label="Delegate"
            />
          </Form.Item>
          <Form.Item
            label="Period (max 30 days)"
            name="range"
            rules={[{ validator: (_, v) => (validateRange(v) ? Promise.reject(new Error(validateRange(v)!)) : Promise.resolve()) }]}
          >
            <DatePicker.RangePicker style={{ width: '100%' }} aria-label="Period" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={create.isPending}>
            Delegate
          </Button>
        </Form>
      </Card>

      <Typography.Title level={3}>My delegations</Typography.Title>
      {revoke.isError ? <Alert type="error" showIcon role="alert" message={approvalErrorText(revoke.error)} style={{ marginBottom: 12 }} /> : null}
      {list.isPending ? (
        <PageLoading label="Loading delegations…" rows={2} />
      ) : list.isError ? (
        <PageError title="Could not load delegations" message={approvalErrorText(list.error)} onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <PageEmpty description="No delegations yet." />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Table<Delegation> rowKey="id" size="small" columns={columns} dataSource={list.data} pagination={false} scroll={{ x: true }} aria-label="My delegations" />
        </Space>
      )}
    </main>
  )
}
