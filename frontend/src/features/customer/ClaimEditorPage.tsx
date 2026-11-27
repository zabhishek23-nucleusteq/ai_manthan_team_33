import { DeleteOutlined, FlagOutlined, PlusOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Flex,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Typography,
  theme,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState, type KeyboardEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { isApiError } from '../../api/errors'
import { useIdempotencyKey } from '../../api/idempotency'
import type { Category, Claim } from '../../api/types'
import { PageError, PageLoading } from '../../components/PageState'
import { clarificationQuestion } from './claimDetailModel'
import { useClaim, useClaimComments, useSaveClaim, useSubmitClaim } from './claimsApi'
import { ClarificationBanner } from './ClarificationBanner'
import { CustomerPage } from './CustomerPage'
import { claimToForm, emptyLine, formToInput, MAX_LINES, type EditorForm, type LineForm } from './editorModel'
import { CATEGORY_LABEL, CATEGORY_OPTIONS, EDITABLE_STATUSES, inr } from './format'
import { lineHints } from './policyHints'
import { ReceiptField } from './ReceiptField'

function LineHints({ index }: { index: number }) {
  const line = Form.useWatch(['lines', index]) as LineForm | undefined
  const hints = lineHints({ ...line, hasReceipt: !!line?.receipt })
  if (!hints.length) return null
  return (
    <div aria-live="polite" style={{ marginBottom: 12 }}>
      {hints.map((h) => (
        <div key={h.code}>
          <Typography.Text type={h.severity}>
            <FlagOutlined aria-hidden /> {h.text}
          </Typography.Text>
        </div>
      ))}
    </div>
  )
}

function LineCard({ index, onRemove, canRemove }: { index: number; onRemove: () => void; canRemove: boolean }) {
  const label = `Line ${index + 1}`
  const category = Form.useWatch(['lines', index, 'category']) as Category | undefined
  return (
    <Card
      size="small"
      title={category ? `${label} · ${CATEGORY_LABEL[category]}` : label}
      extra={
        canRemove ? (
          <Button type="text" danger icon={<DeleteOutlined />} onClick={onRemove} aria-label={`Remove ${label}`}>
            Remove
          </Button>
        ) : null
      }
      role="group"
      aria-label={label}
    >
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item label="Category" name={[index, 'category']} rules={[{ required: true, message: 'Choose a category' }]}>
            <Select options={CATEGORY_OPTIONS} placeholder="Choose" aria-label={`${label} category`} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            label="Date"
            name={[index, 'expenseDate']}
            rules={[
              { required: true, message: 'Choose the expense date' },
              {
                validator: (_, v: Dayjs | null) =>
                  v && v.isAfter(dayjs(), 'day') ? Promise.reject(new Error('Date cannot be in the future')) : Promise.resolve(),
              },
            ]}
          >
            <DatePicker
              format="DD MMM YYYY"
              style={{ width: '100%' }}
              disabledDate={(d) => d.isAfter(dayjs(), 'day')}
              inputReadOnly
              aria-label={`${label} date`}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item
            label="Amount (INR)"
            name={[index, 'amount']}
            rules={[
              { required: true, message: 'Enter the amount' },
              {
                validator: (_, v: number | null) =>
                  v == null || (v > 0 && v <= 10_000_000)
                    ? Promise.resolve()
                    : Promise.reject(new Error('Amount must be more than 0 and at most 1,00,00,000')),
              },
            ]}
          >
            <InputNumber<number>
              style={{ width: '100%' }}
              min={0}
              precision={2}
              inputMode="decimal"
              prefix="INR"
              aria-label={`${label} amount`}
            />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item
            label="Vendor"
            name={[index, 'vendor']}
            rules={[
              { required: true, whitespace: true, message: 'Enter the vendor' },
              { max: 120, message: 'At most 120 characters' },
            ]}
          >
            <Input aria-label={`${label} vendor`} autoComplete="off" />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label="Description" name={[index, 'description']} rules={[{ max: 500, message: 'At most 500 characters' }]}>
            <Input aria-label={`${label} description`} autoComplete="off" />
          </Form.Item>
        </Col>
        {category === 'HOTEL' ? (
          <>
            <Col xs={24} md={12}>
              <Form.Item label="City" name={[index, 'city']} rules={[{ required: true, whitespace: true, message: 'Enter the hotel city' }]}>
                <Input aria-label={`${label} city`} placeholder="e.g. Pune" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                label="Nights"
                name={[index, 'nights']}
                rules={[
                  { required: true, message: 'Enter the number of nights' },
                  { type: 'number', min: 1, max: 30, message: 'Nights must be 1 to 30' },
                ]}
              >
                <InputNumber<number> style={{ width: '100%' }} precision={0} inputMode="numeric" aria-label={`${label} nights`} />
              </Form.Item>
            </Col>
          </>
        ) : null}
      </Row>
      <LineHints index={index} />
      <Form.Item label="Receipt" name={[index, 'receipt']} style={{ marginBottom: 0 }}>
        <ReceiptField lineLabel={label} />
      </Form.Item>
    </Card>
  )
}

function Total() {
  const lines = (Form.useWatch('lines') as LineForm[] | undefined) ?? []
  const total = lines.reduce((s, l) => s + (Number(l?.amount) || 0), 0)
  return (
    <Typography.Text strong aria-live="polite">
      Total {inr(Math.round(total * 100) / 100)} · {lines.length} line{lines.length === 1 ? '' : 's'}
    </Typography.Text>
  )
}

function ClaimEditor({ claim, initial }: { claim?: Claim; initial: EditorForm }) {
  const [form] = Form.useForm<EditorForm>()
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const { token } = theme.useToken()
  const save = useSaveClaim()
  const submit = useSubmitClaim()
  const idem = useIdempotencyKey()
  const [claimId, setClaimId] = useState<number | undefined>(claim?.id)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const [blocked, setBlocked] = useState(false)
  const [errorSummary, setErrorSummary] = useState<string[]>([])
  const busy = save.isPending || submit.isPending
  const resubmit = claim?.status === 'CLARIFICATION_REQUESTED'

  const validate = async (): Promise<EditorForm | null> => {
    try {
      const v = await form.validateFields()
      setErrorSummary([])
      return v
    } catch (e) {
      const fields = (e as { errorFields?: Array<{ name: (string | number)[]; errors: string[] }> }).errorFields ?? []
      const lines = new Set(fields.filter((f) => f.name[0] === 'lines').map((f) => Number(f.name[1]) + 1))
      const summary = [
        ...(fields.some((f) => f.name[0] === 'title') ? ['Title'] : []),
        ...[...lines].sort((a, b) => a - b).map((n) => `Line ${n}`),
      ]
      setErrorSummary(summary)
      if (fields[0]) form.scrollToField(fields[0].name, { behavior: 'smooth', block: 'center' })
      return null
    }
  }

  const handleError = (e: unknown, action: 'save' | 'submit') => {
    if (isApiError(e, 'ILLEGAL_STATE_TRANSITION')) setBlocked(true)
    else if (isApiError(e, 'CLAIM_EMPTY')) void message.error('Add at least one expense line before submitting.')
    else if (isApiError(e, 'VALIDATION_FAILED')) void message.error('Some details are not valid. Check the highlighted fields.')
    else void message.error(`Could not ${action} the claim. Your changes are kept - please try again.`)
  }

  const persist = async (v: EditorForm): Promise<Claim> => {
    const saved = await save.mutateAsync({ id: claimId, input: formToInput(v) })
    setClaimId(saved.id)
    setSavedAt(dayjs().format('HH:mm'))
    return saved
  }

  const onSaveDraft = async () => {
    const v = await validate()
    if (!v) return
    try {
      const isNew = !claimId
      const saved = await persist(v)
      void message.success('Draft saved')
      // First save of a new claim: move to its edit URL (detail is already in the query cache).
      if (isNew) navigate(`/claims/${saved.id}/edit`, { replace: true })
    } catch (e) {
      handleError(e, 'save')
    }
  }

  const onSubmit = async () => {
    const v = await validate()
    if (!v) return
    if (!v.lines?.length) {
      setErrorSummary(['Add at least one line'])
      return
    }
    const total = v.lines.reduce((s, l) => s + Number(l.amount), 0)
    modal.confirm({
      title: resubmit ? 'Resubmit this claim?' : 'Submit this claim?',
      content: `${v.lines.length} line${v.lines.length === 1 ? '' : 's'}, ${inr(Math.round(total * 100) / 100)}. You cannot edit it after submitting.`,
      okText: resubmit ? 'Resubmit' : 'Submit',
      cancelText: 'Keep editing',
      onOk: async () => {
        try {
          const saved = await persist(v)
          const result = await submit.mutateAsync({ id: saved.id, idempotencyKey: idem.key() })
          idem.reset()
          navigate(`/claims/${result.id}/submitted`, { replace: true, state: { claim: result } })
        } catch (e) {
          if (isApiError(e) && e.status >= 400 && e.status < 500) idem.reset()
          handleError(e, 'submit')
        }
      },
    })
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (!(e.ctrlKey || e.metaKey)) return
    if (e.key === 's') {
      e.preventDefault()
      void onSaveDraft()
    } else if (e.key === 'Enter') {
      e.preventDefault()
      void onSubmit()
    }
  }

  if (blocked) {
    return (
      <Alert
        type="warning"
        showIcon
        role="alert"
        message="This claim was already submitted"
        description="It can no longer be edited."
        action={claimId ? <Link to={`/claims/${claimId}`}>View claim</Link> : undefined}
      />
    )
  }

  return (
    <div onKeyDown={onKeyDown}>
      {errorSummary.length ? (
        <Alert
          type="error"
          showIcon
          role="alert"
          style={{ marginBottom: 16 }}
          message="Please fix the highlighted fields"
          description={errorSummary.join(', ')}
        />
      ) : null}
      <Form<EditorForm> form={form} layout="vertical" initialValues={initial} disabled={busy} requiredMark="optional">
        <Form.Item
          label="Title"
          name="title"
          rules={[
            { required: true, whitespace: true, message: 'Enter a title, e.g. Client visit Pune' },
            { min: 3, max: 120, message: 'Title must be 3 to 120 characters' },
          ]}
        >
          <Input autoFocus placeholder="e.g. Client visit Pune" maxLength={120} />
        </Form.Item>

        <Form.List name="lines">
          {(fields, { add, remove }) => (
            <Flex vertical gap={12}>
              {fields.map((f) => (
                <LineCard key={f.key} index={f.name} canRemove={fields.length > 1} onRemove={() => remove(f.name)} />
              ))}
              <Button
                type="dashed"
                icon={<PlusOutlined />}
                block
                disabled={busy || fields.length >= MAX_LINES}
                onClick={() => add(emptyLine())}
              >
                Add line
              </Button>
              {fields.length >= MAX_LINES ? (
                <Typography.Text type="secondary">A claim can have at most {MAX_LINES} lines.</Typography.Text>
              ) : null}
            </Flex>
          )}
        </Form.List>

        <div
          style={{
            position: 'sticky',
            bottom: 0,
            zIndex: 2,
            marginTop: 16,
            padding: '12px 0',
            background: token.colorBgLayout,
            borderTop: `1px solid ${token.colorBorderSecondary}`,
          }}
        >
          <Flex justify="space-between" align="center" wrap gap={8}>
            <div>
              <Total />
              {savedAt ? (
                <div>
                  <Typography.Text type="secondary">Saved {savedAt}</Typography.Text>
                </div>
              ) : null}
            </div>
            <Flex gap={8}>
              <Button icon={<SaveOutlined />} onClick={() => void onSaveDraft()} loading={save.isPending && !submit.isPending}>
                Save draft
              </Button>
              <Button type="primary" icon={<SendOutlined />} onClick={() => void onSubmit()} loading={submit.isPending}>
                {resubmit ? 'Resubmit' : 'Submit'}
              </Button>
            </Flex>
          </Flex>
        </div>
      </Form>
    </div>
  )
}

/** /claims/new (optionally pre-filled via state.copyFrom) and /claims/:id/edit. */
export function ClaimEditorPage() {
  const { id } = useParams()
  const location = useLocation()
  const claimId = id ? Number(id) : undefined
  const { data: claim, isPending, isError, error, refetch } = useClaim(claimId)
  const copyFrom = (location.state as { copyFrom?: Claim } | null)?.copyFrom
  const comments = useClaimComments(claimId, claim?.status === 'CLARIFICATION_REQUESTED')
  const question = claim ? clarificationQuestion(claim, comments.data) : null

  const initialNew = useMemo<EditorForm>(
    () =>
      copyFrom
        ? { ...claimToForm(copyFrom), title: `${copyFrom.title} (copy)`.slice(0, 120) }
        : { title: '', lines: [emptyLine()] },
    [copyFrom],
  )

  useEffect(() => {
    document.title = claimId ? 'Edit claim - ExpenseFlow' : 'New claim - ExpenseFlow'
  }, [claimId])

  const back = { to: '/claims', label: 'My claims' }

  if (claimId === undefined) {
    return (
      <CustomerPage title="New claim" back={back}>
        <ClaimEditor initial={initialNew} />
      </CustomerPage>
    )
  }
  if (isPending)
    return (
      <CustomerPage title="Edit claim" back={back}>
        <PageLoading label="Loading claim…" />
      </CustomerPage>
    )
  if (isError) {
    const notFound = isApiError(error) && error.status === 404
    return (
      <CustomerPage title="Edit claim" back={back}>
        <PageError
          title={notFound ? 'Claim not found' : 'Could not load the claim'}
          message={notFound ? 'It does not exist or is not yours.' : 'Please try again.'}
          onRetry={notFound ? undefined : () => void refetch()}
        />
      </CustomerPage>
    )
  }
  if (!EDITABLE_STATUSES.includes(claim.status)) {
    return (
      <CustomerPage title={`Edit ${claim.code}`} back={back}>
        <Alert
          type="warning"
          showIcon
          message="This claim was already submitted"
          description="Only drafts and claims sent back for clarification can be edited."
          action={<Link to={`/claims/${claim.id}`}>View claim</Link>}
        />
      </CustomerPage>
    )
  }
  return (
    <CustomerPage title={`Edit ${claim.code}`} back={back}>
      {question ? <ClarificationBanner question={question} /> : null}
      <ClaimEditor key={claim.id} claim={claim} initial={claimToForm(claim)} />
    </CustomerPage>
  )
}
