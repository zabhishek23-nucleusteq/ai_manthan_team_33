import { CopyOutlined, EditOutlined, FlagOutlined, PaperClipOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Empty,
  Flex,
  Form,
  Input,
  List,
  Skeleton,
  Space,
  Steps,
  Tabs,
  Timeline,
  Typography,
} from 'antd'
import dayjs from 'dayjs'
import { useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import type { ApprovalStep, Claim } from '../../api/types'
import { ClaimStatusTag, ToneTag } from '../../components/ClaimStatusTag'
import { PageError, PageLoading } from '../../components/PageState'
import {
  auditActionText,
  auditActor,
  auditDetail,
  clarificationQuestion,
  DECISION_LABEL,
  LEVEL_LABEL,
} from './claimDetailModel'
import { useAddComment, useClaim, useClaimAudit, useClaimComments } from './claimsApi'
import { ClarificationBanner } from './ClarificationBanner'
import { CustomerPage } from './CustomerPage'
import { CATEGORY_LABEL, fmtDate, inr } from './format'
import { VIOLATION_SHORT, VIOLATION_TEXT } from './policyHints'

const fmtTime = (iso?: string) => (iso ? dayjs(iso).format('DD MMM YYYY, HH:mm') : '')

function LinesTab({ claim }: { claim: Claim }) {
  if (!claim.lines.length) return <Empty description="No expense lines yet" />
  return (
    <List
      dataSource={claim.lines}
      split={false}
      renderItem={(l, i) => (
        <List.Item style={{ padding: '0 0 12px' }}>
          <Card size="small" style={{ width: '100%' }} title={`Line ${i + 1} · ${CATEGORY_LABEL[l.category]}`} extra={<strong>{inr(l.amount)}</strong>}>
            <Typography.Text type="secondary">
              {fmtDate(l.expenseDate)} · {l.vendor}
              {l.category === 'HOTEL' && l.city ? ` · ${l.city}, ${l.nights} night${l.nights === 1 ? '' : 's'}` : ''}
            </Typography.Text>
            {l.description ? <div>{l.description}</div> : null}
            <div style={{ marginTop: 4 }}>
              {l.receipt || l.receiptId ? (
                <Typography.Text>
                  <PaperClipOutlined aria-hidden /> {l.receipt?.fileName ?? 'Receipt attached'}
                </Typography.Text>
              ) : (
                <Typography.Text type="secondary">No receipt</Typography.Text>
              )}
            </div>
            {(l.violations ?? []).length ? (
              <Space direction="vertical" size={2} style={{ marginTop: 8 }}>
                {(l.violations ?? []).map((v) => (
                  <div key={v.code}>
                    <ToneTag tone={v.action === 'REJECT' ? 'error' : 'warning'} icon={<FlagOutlined />}>
                      {VIOLATION_SHORT[v.code] ?? v.code}
                    </ToneTag>
                    <Typography.Text type="secondary">{v.message || VIOLATION_TEXT[v.code]}</Typography.Text>
                  </div>
                ))}
              </Space>
            ) : null}
          </Card>
        </List.Item>
      )}
    />
  )
}

function stepStatus(s: ApprovalStep): 'finish' | 'process' | 'error' | 'wait' {
  if (s.decision === 'APPROVED') return 'finish'
  if (s.decision === 'PENDING') return 'process'
  if (s.decision === 'REJECTED' || s.decision === 'SENT_BACK') return 'error'
  return 'wait'
}

function ApprovalTab({ claim }: { claim: Claim }) {
  const steps = claim.steps ?? []
  if (!steps.length) {
    const text =
      claim.status === 'DRAFT'
        ? 'Not submitted yet.'
        : claim.status === 'AUTO_REJECTED'
          ? 'Rejected by policy - no approvers involved.'
          : 'No approval needed - approved automatically by policy.'
    return <Empty description={text} />
  }
  return (
    <Steps
      direction="vertical"
      size="small"
      items={steps.map((s) => {
        const who = s.actedBy ?? s.assignee
        const delegated = s.onBehalfOf ? ` on behalf of ${s.onBehalfOf.name}` : ''
        return {
          status: stepStatus(s),
          title: `${LEVEL_LABEL[s.level]}: ${DECISION_LABEL[s.decision]}`,
          description: (
            <>
              <div>
                {who ? `${who.name}${delegated}` : 'Not assigned yet'}
                {s.decidedAt ? ` · ${fmtTime(s.decidedAt)}` : s.createdAt ? ` · waiting since ${fmtDate(s.createdAt)}` : ''}
              </div>
              {s.comment ? <Typography.Text type="secondary">“{s.comment}”</Typography.Text> : null}
            </>
          ),
        }
      })}
    />
  )
}

function TimelineTab({ claimId }: { claimId: number }) {
  const { data, isPending, isError, refetch } = useClaimAudit(claimId)
  if (isPending) return <Skeleton active paragraph={{ rows: 4 }} />
  if (isError) return <PageError title="Could not load the timeline" onRetry={() => void refetch()} />
  if (!data.length) return <Empty description="Nothing has happened yet" />
  return (
    <Timeline
      items={data.map((e) => {
        const detail = auditDetail(e)
        return {
          key: e.id,
          color: /REJECT|EXCEPTION/.test(e.action) ? 'red' : /SENT_BACK/.test(e.action) ? 'orange' : /APPROV|PAID/.test(e.action) ? 'green' : 'blue',
          children: (
            <>
              <Typography.Text strong>{auditActionText(e.action)}</Typography.Text>
              <div>
                <Typography.Text type="secondary">
                  {fmtTime(e.at)} · {auditActor(e)}
                </Typography.Text>
              </div>
              {detail ? <div>{detail}</div> : null}
            </>
          ),
        }
      })}
    />
  )
}

function CommentsTab({ claimId }: { claimId: number }) {
  const { data, isPending, isError, refetch } = useClaimComments(claimId)
  const add = useAddComment(claimId)
  const [form] = Form.useForm<{ text: string }>()
  const { message } = App.useApp()

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {isPending ? (
        <Skeleton active paragraph={{ rows: 2 }} />
      ) : isError ? (
        <PageError title="Could not load comments" onRetry={() => void refetch()} />
      ) : data.length === 0 ? (
        <Empty description="No comments yet" />
      ) : (
        <List
          dataSource={data}
          renderItem={(c) => (
            <List.Item>
              <List.Item.Meta title={`${c.author.name} · ${fmtTime(c.createdAt)}`} description={c.text} />
            </List.Item>
          )}
        />
      )}
      <Form
        form={form}
        layout="vertical"
        onFinish={async ({ text }) => {
          try {
            await add.mutateAsync(text.trim())
            form.resetFields()
            void message.success('Comment sent')
          } catch {
            void message.error('Could not send the comment. Your text is kept - please try again.')
          }
        }}
      >
        <Form.Item
          label="Add a comment"
          name="text"
          rules={[
            { required: true, whitespace: true, message: 'Write a comment first' },
            { max: 2000, message: 'At most 2000 characters' },
          ]}
        >
          <Input.TextArea rows={3} maxLength={2000} showCount />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={add.isPending}>
          Send comment
        </Button>
      </Form>
    </Space>
  )
}

/** /claims/:id - read-only claim view: lines, approval chain, timeline, comments (wireframes §4-5). */
export function ClaimDetailPage() {
  const { id } = useParams()
  const claimId = Number(id)
  const navigate = useNavigate()
  const { data: claim, isPending, isError, error, refetch } = useClaim(claimId)
  const comments = useClaimComments(claimId)
  const back = { to: '/claims', label: 'My claims' }

  useEffect(() => {
    if (claim) document.title = `${claim.code} - ExpenseFlow`
  }, [claim])

  if (isPending)
    return (
      <CustomerPage title="Claim" back={back}>
        <PageLoading label="Loading claim…" />
      </CustomerPage>
    )
  if (isError) {
    const notFound = isApiError(error) && error.status === 404
    return (
      <CustomerPage title="Claim" back={back}>
        <PageError
          title={notFound ? 'Claim not found' : 'Could not load the claim'}
          message={notFound ? 'It does not exist or is not yours.' : 'Please try again.'}
          onRetry={notFound ? undefined : () => void refetch()}
        />
      </CustomerPage>
    )
  }

  const question = clarificationQuestion(claim, comments.data)
  const actions =
    claim.status === 'DRAFT' ? (
      <Button type="primary" icon={<EditOutlined />} onClick={() => navigate(`/claims/${claim.id}/edit`)}>
        Edit draft
      </Button>
    ) : claim.status === 'AUTO_REJECTED' || claim.status === 'REJECTED' ? (
      <Button icon={<CopyOutlined />} onClick={() => navigate('/claims/new', { state: { copyFrom: claim } })}>
        Copy to new draft
      </Button>
    ) : null

  return (
    <CustomerPage title={claim.code} back={back} extra={actions}>
      {question ? (
        <ClarificationBanner
          question={question}
          action={
            <Button type="primary" icon={<EditOutlined />} onClick={() => navigate(`/claims/${claim.id}/edit`)}>
              Edit and resubmit
            </Button>
          }
        />
      ) : null}
      {claim.status === 'REJECTED' ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="This claim was rejected"
          description={[...(claim.steps ?? [])].reverse().find((s) => s.decision === 'REJECTED')?.comment}
        />
      ) : null}
      <Card style={{ marginBottom: 16 }}>
        <Flex justify="space-between" align="center" wrap gap={8}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {claim.title}
          </Typography.Title>
          <ClaimStatusTag status={claim.status} />
        </Flex>
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }} style={{ marginTop: 12 }}>
          <Descriptions.Item label="Total">{inr(claim.total)}</Descriptions.Item>
          <Descriptions.Item label="Lines">{claim.lines.length}</Descriptions.Item>
          <Descriptions.Item label="Submitted">{claim.submittedAt ? fmtDate(claim.submittedAt) : 'Not yet'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card>
        <Tabs
          defaultActiveKey={question ? 'comments' : 'lines'}
          items={[
            { key: 'lines', label: 'Lines', children: <LinesTab claim={claim} /> },
            { key: 'approval', label: 'Approval', children: <ApprovalTab claim={claim} /> },
            { key: 'timeline', label: 'Timeline', children: <TimelineTab claimId={claim.id} /> },
            {
              key: 'comments',
              label: `Comments${comments.data?.length ? ` (${comments.data.length})` : ''}`,
              children: <CommentsTab claimId={claim.id} />,
            },
          ]}
        />
      </Card>
    </CustomerPage>
  )
}
