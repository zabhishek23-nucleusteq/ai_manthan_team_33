import { CloudUploadOutlined, DownloadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Drawer,
  Flex,
  Grid,
  List,
  Modal,
  Pagination,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from 'antd'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import { newIdempotencyKey } from '../../api/idempotency'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { useAuth } from '../auth/authContext'
import { CustomerPage } from '../customer/CustomerPage'
import { fmtDate, inr } from '../customer/format'
import {
  downloadBatchCsv,
  useCreateBatch,
  usePayoutBatch,
  usePayoutBatches,
  usePayoutEligible,
  useSubmitBatch,
  type PayoutBatch,
  type PayoutBatchStatus,
} from './financeApi'
import { BATCH_STATUS } from './financeFormat'

const PAGE_SIZE = 10

function BatchStatusTag({ status }: { status: PayoutBatchStatus }) {
  const m = BATCH_STATUS[status] ?? { label: status, color: 'default' }
  return (
    <Tag color={m.color} data-status={status}>
      {m.label}
    </Tag>
  )
}

const canSubmit = (b: PayoutBatch) => b.status === 'CREATED' || b.status === 'SUBMIT_FAILED'

function errorText(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** Create-batch confirm: shows what the batch would take right now (count + total), FR-11. */
function CreateBatchModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (b: PayoutBatch) => void }) {
  const eligible = usePayoutEligible(open)
  const create = useCreateBatch()
  const [key, setKey] = useState(newIdempotencyKey)

  const close = () => {
    create.reset()
    setKey(newIdempotencyKey())
    onClose()
  }
  const confirm = () =>
    create.mutate(key, {
      onSuccess: (b) => {
        setKey(newIdempotencyKey())
        onCreated(b)
      },
    })

  const count = eligible.data?.count ?? 0
  let body
  if (eligible.isPending) body = <PageLoading label="Counting approved claims…" rows={1} />
  else if (eligible.isError)
    body = <PageError title="Could not count approved claims" message="Please try again." onRetry={() => void eligible.refetch()} />
  else if (count === 0) body = <PageEmpty description="No approved claims are waiting for payout" />
  else
    body = (
      <Typography.Paragraph>
        Create a payout batch with <strong>{count} approved claim{count === 1 ? '' : 's'}</strong> totalling{' '}
        <strong>{inr(eligible.data?.total)}</strong>? The claims move to <em>In payout</em>.
      </Typography.Paragraph>
    )

  return (
    <Modal
      open={open}
      title="Create payout batch"
      onCancel={close}
      okText={count ? `Create batch (${count})` : 'Create batch'}
      onOk={confirm}
      okButtonProps={{ disabled: count === 0 || eligible.isPending, loading: create.isPending }}
      destroyOnHidden
    >
      {body}
      {create.isError ? (
        <Alert
          type="error"
          showIcon
          role="alert"
          message={isApiError(create.error, 'NO_ELIGIBLE_CLAIMS') ? 'No approved claims are waiting for payout.' : errorText(create.error, 'Could not create the batch.')}
        />
      ) : null}
    </Modal>
  )
}

/** Batch detail: items, CSV download, submit to bank; SUBMIT_FAILED shows the bank error and Retry. */
function BatchDrawer({ id, onClose, isHead }: { id: number | undefined; onClose: () => void; isHead: boolean }) {
  const { message } = AntApp.useApp()
  const screens = Grid.useBreakpoint()
  const { data: b, isPending, isError, refetch } = usePayoutBatch(id)
  const submit = useSubmitBatch()
  const [downloading, setDownloading] = useState(false)

  const doSubmit = (batch: PayoutBatch) =>
    // New key per attempt: a retry after SUBMIT_FAILED is a new action (the bank dedupes by batch on its side).
    submit.mutate(
      { id: batch.id, idempotencyKey: newIdempotencyKey() },
      {
        onSuccess: (r) => {
          void refetch()
          void message.success(`${r.code} sent to the bank: ${r.itemCount} claims, ${inr(r.total)}`)
        },
        onError: () => void refetch(),
      },
    )

  const download = async (batch: PayoutBatch) => {
    setDownloading(true)
    try {
      await downloadBatchCsv(batch)
    } catch (e) {
      void message.error(errorText(e, 'Could not download the CSV.'))
    } finally {
      setDownloading(false)
    }
  }

  let body
  if (isPending) body = <PageLoading label="Loading batch…" />
  else if (isError || !b) body = <PageError title="Could not load the batch" onRetry={() => void refetch()} />
  else {
    const failed = b.status === 'SUBMIT_FAILED'
    const submitError = submit.isError ? errorText(submit.error, 'The bank did not take the file.') : null
    body = (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {failed || submitError ? (
          <Alert
            type="error"
            showIcon
            role="alert"
            message="Submit to bank failed"
            description={submitError ?? b.lastError ?? 'The bank did not take the file.'}
            action={
              isHead && canSubmit(b) ? (
                <Button danger icon={<ReloadOutlined />} loading={submit.isPending} onClick={() => doSubmit(b)}>
                  Retry
                </Button>
              ) : undefined
            }
          />
        ) : null}
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="Status">
            <BatchStatusTag status={b.status} />
          </Descriptions.Item>
          <Descriptions.Item label="Claims">{b.itemCount}</Descriptions.Item>
          <Descriptions.Item label="Total">{inr(b.total)}</Descriptions.Item>
          <Descriptions.Item label="Value date">{fmtDate(b.valueDate)}</Descriptions.Item>
          <Descriptions.Item label="Created">{fmtDate(b.createdAt)}</Descriptions.Item>
          {b.bankReference ? <Descriptions.Item label="Bank reference">{b.bankReference}</Descriptions.Item> : null}
        </Descriptions>
        <Flex gap={8} wrap>
          <Button icon={<DownloadOutlined />} loading={downloading} onClick={() => void download(b)}>
            Download CSV
          </Button>
          {isHead && b.status === 'CREATED' ? (
            <Popconfirm
              title="Submit to bank?"
              description={`Pay ${b.itemCount} claims (${inr(b.total)}). This cannot be undone.`}
              okText="Submit"
              onConfirm={() => doSubmit(b)}
            >
              <Button type="primary" icon={<CloudUploadOutlined />} loading={submit.isPending}>
                Submit to bank
              </Button>
            </Popconfirm>
          ) : null}
        </Flex>
        <Table
          rowKey="claimId"
          size="small"
          pagination={false}
          dataSource={b.items ?? []}
          scroll={{ x: 420 }}
          columns={[
            { title: 'Claim', dataIndex: 'claimCode', key: 'claimCode' },
            { title: 'Employee', key: 'employee', render: (_: unknown, i) => i.employee?.name ?? '-' },
            { title: 'Amount', dataIndex: 'amount', key: 'amount', align: 'right', render: (v: number) => inr(v) },
          ]}
        />
      </Space>
    )
  }

  return (
    <Drawer
      open={id !== undefined}
      onClose={() => {
        submit.reset()
        onClose()
      }}
      title={b ? `Batch ${b.code}` : 'Batch'}
      width={screens.md ? 640 : '100%'}
      destroyOnHidden
    >
      {body}
    </Drawer>
  )
}

/** /payouts (FR-11): FINANCE_HEAD creates and submits batches; FINANCE_AUDITOR reads and exports. */
export function PayoutsPage() {
  const { roles } = useAuth()
  const isHead = roles.includes('FINANCE_HEAD')
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const openId = params.get('batch') ? Number(params.get('batch')) : undefined
  const [creating, setCreating] = useState(false)
  const { data, isPending, isError, refetch, isFetching } = usePayoutBatches(page, PAGE_SIZE)

  const setParam = (k: string, v: string | null) => {
    const next = new URLSearchParams(params)
    if (v) next.set(k, v)
    else next.delete(k)
    setParams(next)
  }
  const open = (id: number) => setParam('batch', String(id))

  const columns: TableColumnsType<PayoutBatch> = [
    {
      title: 'Batch',
      dataIndex: 'code',
      key: 'code',
      render: (code: string, b) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => open(b.id)}>
          {code}
        </Button>
      ),
    },
    { title: 'Created', dataIndex: 'createdAt', key: 'createdAt', render: (v?: string) => fmtDate(v) },
    { title: 'Claims', dataIndex: 'itemCount', key: 'itemCount', align: 'right' },
    { title: 'Total', dataIndex: 'total', key: 'total', align: 'right', render: (v: number) => inr(v) },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (s: PayoutBatchStatus) => <BatchStatusTag status={s} /> },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: unknown, b) => (
        <Button size="small" onClick={() => open(b.id)}>
          {isHead && canSubmit(b) ? (b.status === 'SUBMIT_FAILED' ? 'Open and retry' : 'Open and submit') : 'Open'}
        </Button>
      ),
    },
  ]

  const items = data?.items ?? []
  let body
  if (isPending) body = <PageLoading label="Loading payout batches…" />
  else if (isError) body = <PageError title="Could not load payout batches" onRetry={() => void refetch()} />
  else if (items.length === 0)
    body = (
      <PageEmpty
        description="No payout batches yet"
        actionLabel={isHead ? 'Create the first batch' : undefined}
        onAction={isHead ? () => setCreating(true) : undefined}
      />
    )
  else if (isMobile)
    body = (
      <List
        dataSource={items}
        loading={isFetching}
        split={false}
        renderItem={(b) => (
          <List.Item style={{ padding: '0 0 12px' }}>
            <Card size="small" hoverable style={{ width: '100%' }} onClick={() => open(b.id)}>
              <Flex justify="space-between" align="center" gap={8}>
                <Button type="link" style={{ padding: 0 }} onClick={() => open(b.id)} aria-label={`${b.code}, ${BATCH_STATUS[b.status]?.label ?? b.status}`}>
                  {b.code}
                </Button>
                <BatchStatusTag status={b.status} />
              </Flex>
              <Typography.Text type="secondary">
                {inr(b.total)} · {b.itemCount} claim{b.itemCount === 1 ? '' : 's'} · {fmtDate(b.createdAt)}
              </Typography.Text>
            </Card>
          </List.Item>
        )}
      />
    )
  else
    body = <Table<PayoutBatch> rowKey="id" columns={columns} dataSource={items} loading={isFetching} pagination={false} scroll={{ x: 720 }} />

  return (
    <CustomerPage
      title="Payout batches"
      extra={
        isHead ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            Create batch
          </Button>
        ) : null
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {body}
        {data && data.total > PAGE_SIZE ? (
          <Flex justify="center">
            <Pagination
              current={page + 1}
              pageSize={PAGE_SIZE}
              total={data.total}
              onChange={(p) => setParam('page', p > 1 ? String(p - 1) : null)}
              simple={isMobile}
              showSizeChanger={false}
            />
          </Flex>
        ) : null}
      </Space>
      <CreateBatchModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(b) => {
          setCreating(false)
          open(b.id)
        }}
      />
      <BatchDrawer id={openId} isHead={isHead} onClose={() => setParam('batch', null)} />
    </CustomerPage>
  )
}
