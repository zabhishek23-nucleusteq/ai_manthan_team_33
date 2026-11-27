import { CheckOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tooltip,
  Typography,
  type TableColumnsType,
} from 'antd'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import type { Category, ViolationCode } from '../../api/types'
import { ToneTag } from '../../components/ClaimStatusTag'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { CATEGORY_OPTIONS, fmtDate, inr } from '../customer/format'
import { VIOLATION_SHORT } from '../customer/policyHints'
import type { ApprovalTask } from './approvalsApi'
import {
  BULK_CODE_TEXT,
  filtersFromParams,
  filtersToParams,
  useAuditQueue,
  useBulkApprove,
  useDepartments,
  useSampleAudit,
  type AuditQueueFilters,
  type BulkResult,
} from './auditQueueApi'

const OLDER_THAN = [
  { value: 24, label: 'Older than 1 day' },
  { value: 48, label: 'Older than 2 days' },
  { value: 72, label: 'Older than 3 days' },
  { value: 168, label: 'Older than 7 days' },
]

const VIOLATION_OPTIONS = (Object.keys(VIOLATION_SHORT) as ViolationCode[]).map((v) => ({ value: v, label: VIOLATION_SHORT[v] }))

/** /audit-queue (FR-9, FR-10): dense, filterable queue for the finance auditor with bulk approve and sample audits. */
export function AuditQueuePage() {
  const [params, setParams] = useSearchParams()
  const filters = useMemo(() => filtersFromParams(params), [params])
  const { data, isPending, isError, error, refetch, isFetching } = useAuditQueue(filters)
  const departments = useDepartments()
  const bulk = useBulkApprove()
  const sample = useSampleAudit()
  const { modal, message } = App.useApp()
  const [selected, setSelected] = useState<number[]>([])
  const [results, setResults] = useState<{ items: BulkResult[]; codes: Record<number, string> } | null>(null)
  const [sampleRow, setSampleRow] = useState<{ task: ApprovalTask; result: 'OK' | 'EXCEPTION' } | null>(null)
  const [sampleForm] = Form.useForm<{ comment?: string }>()

  const update = (patch: Partial<AuditQueueFilters>) => {
    const next = { ...filters, ...patch }
    if (!('page' in patch)) next.page = 0 // any filter change goes back to page 1
    setParams(filtersToParams(next), { replace: !('page' in patch) })
    setSelected([])
  }

  const items = data?.items ?? []
  const codeOf = (id: number) => items.find((t) => t.claimId === id)?.code ?? `#${id}`

  const onBulkApprove = () => {
    const ids = [...selected]
    modal.confirm({
      title: `Approve ${ids.length} claim${ids.length === 1 ? '' : 's'}?`,
      content: 'Each claim moves to the next step (finance head above 1,00,000 INR) or to Approved. Claims that changed meanwhile are skipped and listed.',
      okText: 'Approve',
      onOk: async () => {
        try {
          const res = await bulk.mutateAsync({ claimIds: ids })
          setResults({ items: res.results, codes: Object.fromEntries(ids.map((id) => [id, codeOf(id)])) })
          setSelected([])
        } catch (e) {
          void message.error(isApiError(e) ? `Bulk approve failed: ${e.message}` : 'Bulk approve failed. Please try again.')
        }
      },
    })
  }

  const submitSample = async () => {
    if (!sampleRow) return
    let comment: string | undefined
    try {
      ;({ comment } = await sampleForm.validateFields())
    } catch {
      return // the form shows the field error
    }
    try {
      await sample.mutateAsync({ claimId: sampleRow.task.claimId, result: sampleRow.result, comment: comment?.trim() || undefined })
      void message.success(
        sampleRow.result === 'OK' ? `${sampleRow.task.code}: sample audit OK` : `${sampleRow.task.code}: exception recorded, claim rejected`,
      )
      setSampleRow(null)
      sampleForm.resetFields()
    } catch (e) {
      void message.error(isApiError(e, 'ILLEGAL_STATE_TRANSITION') ? 'This claim was already audited.' : 'Could not record the sample audit. Please try again.')
    }
  }

  const sortOrder = (field: 'submittedAt' | 'total') =>
    filters.sort === field ? 'ascend' : filters.sort === `-${field}` ? 'descend' : null

  const columns: TableColumnsType<ApprovalTask> = [
    {
      title: 'Claim',
      dataIndex: 'code',
      key: 'code',
      fixed: 'left',
      width: 130,
      render: (code: string, t) => <Link to={`/approvals/${t.claimId}`}>{code}</Link>,
    },
    { title: 'Employee', key: 'employee', render: (_, t) => t.employee?.name ?? '-' },
    { title: 'Department', key: 'department', render: (_, t) => t.department?.name ?? '-' },
    {
      title: 'Amount',
      dataIndex: 'total',
      key: 'total',
      align: 'right',
      sorter: true,
      sortOrder: sortOrder('total'),
      render: (v: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{inr(v)}</span>,
    },
    {
      title: 'Flags',
      key: 'flags',
      render: (_, t) => (
        <Space size={4} wrap>
          {t.sampleAudit ? <ToneTag tone="cyan">Sample audit</ToneTag> : null}
          {(t.violationCodes ?? []).map((v) => (
            <ToneTag key={v} tone="warning">
              {VIOLATION_SHORT[v] ?? v}
            </ToneTag>
          ))}
        </Space>
      ),
    },
    {
      title: 'Submitted',
      dataIndex: 'submittedAt',
      key: 'submittedAt',
      sorter: true,
      sortOrder: sortOrder('submittedAt'),
      render: (v: string | undefined, t) => (
        <Tooltip title={t.ageHours !== undefined ? `${Math.round(t.ageHours)} h waiting` : undefined}>{fmtDate(v)}</Tooltip>
      ),
    },
    {
      title: 'Action',
      key: 'action',
      render: (_, t) =>
        t.sampleAudit ? (
          <Space size={4}>
            <Button size="small" icon={<CheckOutlined />} onClick={() => setSampleRow({ task: t, result: 'OK' })} aria-label={`Sample audit OK for ${t.code}`}>
              OK
            </Button>
            <Button size="small" danger icon={<ExclamationCircleOutlined />} onClick={() => setSampleRow({ task: t, result: 'EXCEPTION' })} aria-label={`Sample audit exception for ${t.code}`}>
              Exception
            </Button>
          </Space>
        ) : (
          <Link to={`/approvals/${t.claimId}`} aria-label={`Review ${t.code}`}>
            Review
          </Link>
        ),
    },
  ]

  const failed = results?.items.filter((r) => !r.ok) ?? []
  const okCount = (results?.items.length ?? 0) - failed.length

  return (
    <main style={{ padding: 16, maxWidth: 1400, margin: '0 auto' }}>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          Audit queue
        </Typography.Title>
        <Button icon={<ReloadOutlined />} onClick={() => void refetch()} loading={isFetching}>
          Refresh
        </Button>
      </Flex>

      <Card size="small" style={{ marginBottom: 16 }} role="search" aria-label="Filter the audit queue">
        <Row gutter={[12, 12]}>
          <Col xs={24} sm={12} lg={4}>
            <Select
              aria-label="Department"
              placeholder="Department"
              allowClear
              style={{ width: '100%' }}
              loading={departments.isPending}
              value={filters.departmentId}
              onChange={(v?: number) => update({ departmentId: v })}
              options={(departments.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
            />
          </Col>
          <Col xs={24} sm={12} lg={3}>
            <Select
              aria-label="Category"
              placeholder="Category"
              allowClear
              style={{ width: '100%' }}
              value={filters.category}
              onChange={(v?: Category) => update({ category: v })}
              options={CATEGORY_OPTIONS}
            />
          </Col>
          <Col xs={12} sm={6} lg={3}>
            <InputNumber<number>
              aria-label="Minimum amount"
              placeholder="Min INR"
              min={0}
              style={{ width: '100%' }}
              value={filters.minAmount}
              onChange={(v) => update({ minAmount: v ?? undefined })}
            />
          </Col>
          <Col xs={12} sm={6} lg={3}>
            <InputNumber<number>
              aria-label="Maximum amount"
              placeholder="Max INR"
              min={0}
              style={{ width: '100%' }}
              value={filters.maxAmount}
              onChange={(v) => update({ maxAmount: v ?? undefined })}
            />
          </Col>
          <Col xs={24} sm={12} lg={4}>
            <Select
              aria-label="Violation"
              placeholder="Violation"
              allowClear
              style={{ width: '100%' }}
              value={filters.violation}
              onChange={(v?: ViolationCode) => update({ violation: v })}
              options={VIOLATION_OPTIONS}
            />
          </Col>
          <Col xs={24} sm={12} lg={4}>
            <Select
              aria-label="Waiting time"
              placeholder="Any age"
              allowClear
              style={{ width: '100%' }}
              value={filters.olderThanHours}
              onChange={(v?: number) => update({ olderThanHours: v })}
              options={OLDER_THAN}
            />
          </Col>
          <Col xs={24} lg={3}>
            <Flex align="center" gap={8} style={{ height: '100%' }}>
              <Switch
                id="sample-only"
                checked={!!filters.sampleOnly}
                onChange={(v) => update({ sampleOnly: v || undefined })}
                aria-label="Sample audits only"
              />
              <label htmlFor="sample-only">Samples only</label>
            </Flex>
          </Col>
        </Row>
        {params.toString() ? (
          <Button type="link" style={{ paddingInline: 0, marginTop: 8 }} onClick={() => setParams(new URLSearchParams(), { replace: true })}>
            Clear filters
          </Button>
        ) : null}
      </Card>

      <div aria-live="polite">
        {results ? (
          <Alert
            style={{ marginBottom: 16 }}
            showIcon
            closable
            onClose={() => setResults(null)}
            type={failed.length ? (okCount ? 'warning' : 'error') : 'success'}
            message={`${okCount} approved${failed.length ? `, ${failed.length} not approved` : ''}`}
            description={
              failed.length ? (
                <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                  {failed.map((r) => (
                    <li key={r.claimId}>
                      {results.codes[r.claimId] ?? `#${r.claimId}`}: {BULK_CODE_TEXT[r.code ?? ''] ?? r.code ?? 'failed'}
                    </li>
                  ))}
                </ul>
              ) : undefined
            }
          />
        ) : null}
      </div>

      {isPending ? (
        <PageLoading label="Loading the audit queue…" />
      ) : isError ? (
        <PageError
          title="Could not load the audit queue"
          message={isApiError(error) && error.status === 403 ? 'Only finance auditors and the finance head can see this queue.' : 'Please try again.'}
          onRetry={() => void refetch()}
        />
      ) : items.length === 0 ? (
        <PageEmpty
          description={params.toString() ? 'No claims match these filters' : 'The audit queue is empty'}
          actionLabel={params.toString() ? 'Clear filters' : undefined}
          onAction={params.toString() ? () => setParams(new URLSearchParams(), { replace: true }) : undefined}
        />
      ) : (
        <Card size="small">
          <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 12 }}>
            <Typography.Text>
              {selected.length ? `${selected.length} selected` : `${data.total} claim${data.total === 1 ? '' : 's'} in the queue`}
            </Typography.Text>
            <Button type="primary" disabled={!selected.length} loading={bulk.isPending} onClick={onBulkApprove}>
              Approve selected{selected.length ? ` (${selected.length})` : ''}
            </Button>
          </Flex>
          <Table<ApprovalTask>
            rowKey="claimId"
            size="small"
            columns={columns}
            dataSource={items}
            loading={isFetching && !isPending}
            scroll={{ x: 900 }}
            rowSelection={{
              selectedRowKeys: selected,
              onChange: (keys) => setSelected(keys as number[]),
              // Sample audits are already approved: they get OK / Exception, not bulk approve.
              getCheckboxProps: (t) => ({ disabled: !!t.sampleAudit, 'aria-label': `Select ${t.code}` }),
            }}
            onChange={(_p, _f, sorter) => {
              const s = Array.isArray(sorter) ? sorter[0] : sorter
              const field = s?.columnKey === 'total' ? 'total' : s?.columnKey === 'submittedAt' ? 'submittedAt' : undefined
              update({ sort: field && s.order ? ((s.order === 'descend' ? `-${field}` : field) as AuditQueueFilters['sort']) : undefined })
            }}
            pagination={{
              current: filters.page + 1,
              pageSize: filters.size,
              total: data.total,
              showSizeChanger: true,
              pageSizeOptions: [20, 50, 100],
              onChange: (p, size) => update({ page: p - 1, size }),
            }}
          />
        </Card>
      )}

      <Modal
        open={!!sampleRow}
        title={sampleRow ? `Sample audit ${sampleRow.result === 'OK' ? 'OK' : 'exception'}: ${sampleRow.task.code}` : ''}
        okText={sampleRow?.result === 'OK' ? 'Record OK' : 'Record exception'}
        okButtonProps={{ danger: sampleRow?.result === 'EXCEPTION', loading: sample.isPending }}
        onOk={() => void submitSample()}
        onCancel={() => setSampleRow(null)}
        destroyOnHidden
      >
        <Typography.Paragraph>
          {sampleRow?.result === 'EXCEPTION'
            ? 'An exception rejects this auto-approved claim. Explain what is wrong so the employee can fix it.'
            : 'Confirms the auto-approved claim follows policy.'}
        </Typography.Paragraph>
        <Form form={sampleForm} layout="vertical">
          <Form.Item
            label="Comment"
            name="comment"
            rules={sampleRow?.result === 'EXCEPTION' ? [{ required: true, whitespace: true, message: 'Explain the exception' }] : []}
          >
            <Input.TextArea rows={3} maxLength={2000} />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  )
}
