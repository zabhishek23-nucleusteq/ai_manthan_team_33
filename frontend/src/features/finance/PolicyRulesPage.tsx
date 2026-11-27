import { EditOutlined } from '@ant-design/icons'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Drawer,
  Flex,
  Form,
  Grid,
  InputNumber,
  List,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from 'antd'
import { useState } from 'react'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { useAuth } from '../auth/authContext'
import { CustomerPage } from '../customer/CustomerPage'
import { fmtDate } from '../customer/format'
import { checkLimit, isRate, isSetting, usePolicyRules, useUpdatePolicyRule, type PolicyAction, type PolicyRule } from './financeApi'
import { ACTION_META, limitText, UNIT_LABEL } from './financeFormat'

function ActionTag({ r }: { r: PolicyRule }) {
  if (isSetting(r)) return <Tag>Setting</Tag>
  const m = ACTION_META[r.action as PolicyAction]
  return <Tag color={m.color}>{m.label}</Tag>
}

interface EditValues {
  limitAmount?: number | null
  action?: PolicyAction
  enabled?: boolean
}

function EditRuleDrawer({ rule, onClose }: { rule: PolicyRule | null; onClose: () => void }) {
  const { message } = AntApp.useApp()
  const screens = Grid.useBreakpoint()
  const [form] = Form.useForm<EditValues>()
  const update = useUpdatePolicyRule()
  const hasLimit = rule ? rule.limitAmount !== null && rule.limitAmount !== undefined : false

  const close = () => {
    update.reset()
    onClose()
  }

  const onFinish = (v: EditValues) => {
    if (!rule) return
    const input = {
      ...(hasLimit && v.limitAmount !== rule.limitAmount ? { limitAmount: v.limitAmount ?? undefined } : {}),
      ...(!isSetting(rule) && v.action !== rule.action ? { action: v.action } : {}),
      ...(!isSetting(rule) && v.enabled !== rule.enabled ? { enabled: v.enabled } : {}),
    }
    if (Object.keys(input).length === 0) {
      close()
      return
    }
    update.mutate(
      { code: rule.code, input },
      {
        onSuccess: (r) => {
          void message.success(`${r.code} saved: ${limitText(r)}`)
          close()
        },
      },
    )
  }

  return (
    <Drawer
      open={rule !== null}
      onClose={close}
      title={rule ? `Edit ${rule.code}` : 'Edit rule'}
      width={screens.md ? 480 : '100%'}
      destroyOnHidden
      extra={
        <Button type="primary" loading={update.isPending} onClick={() => form.submit()}>
          Save
        </Button>
      }
    >
      {rule ? (
        <Form<EditValues>
          form={form}
          layout="vertical"
          requiredMark={false}
          disabled={update.isPending}
          initialValues={{ limitAmount: rule.limitAmount, action: rule.action ?? undefined, enabled: rule.enabled }}
          onFinish={onFinish}
        >
          <Typography.Paragraph type="secondary">{rule.description}</Typography.Paragraph>
          {hasLimit ? (
            <Form.Item
              label={isRate(rule) ? 'Rate (0 to 1)' : `Limit (INR${rule.unit && UNIT_LABEL[rule.unit] ? `, ${UNIT_LABEL[rule.unit]}` : ''})`}
              name="limitAmount"
              extra={`Now: ${limitText(rule)}`}
              rules={[
                {
                  validator: (_, value: number | null) => {
                    const msg = checkLimit(rule, value)
                    return msg ? Promise.reject(new Error(msg)) : Promise.resolve()
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={0}
                max={isRate(rule) ? 1 : undefined}
                step={isRate(rule) ? 0.01 : 100}
                precision={2}
                prefix={isRate(rule) ? undefined : '₹'}
              />
            </Form.Item>
          ) : null}
          {!isSetting(rule) ? (
            <>
              <Form.Item label="When the rule is broken" name="action">
                <Select
                  options={(Object.keys(ACTION_META) as PolicyAction[]).map((value) => ({ value, label: ACTION_META[value].label }))}
                />
              </Form.Item>
              <Form.Item label="Rule active" name="enabled" valuePropName="checked">
                <Switch />
              </Form.Item>
            </>
          ) : null}
          {update.isError ? (
            <Alert type="error" showIcon role="alert" message={update.error instanceof Error ? update.error.message : 'Could not save the rule.'} />
          ) : null}
          <Typography.Text type="secondary">Changes apply to claims submitted from now on and are recorded in the audit log.</Typography.Text>
        </Form>
      ) : null}
    </Drawer>
  )
}

/** /policy-rules (FR-5): FINANCE_HEAD edits limit/action/enabled in a Drawer; MANAGER, AUDITOR, ADMIN read. */
export function PolicyRulesPage() {
  const { roles } = useAuth()
  const canEdit = roles.includes('FINANCE_HEAD')
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md
  const { data, isPending, isError, refetch } = usePolicyRules()
  const [editing, setEditing] = useState<PolicyRule | null>(null)

  const editButton = (r: PolicyRule) =>
    canEdit ? (
      <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(r)} aria-label={`Edit ${r.code}`}>
        Edit
      </Button>
    ) : null

  const columns: TableColumnsType<PolicyRule> = [
    { title: 'Code', dataIndex: 'code', key: 'code', render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    { title: 'Rule', dataIndex: 'description', key: 'description' },
    { title: 'Limit', key: 'limit', align: 'right', render: (_: unknown, r) => limitText(r) },
    { title: 'Outcome', key: 'action', render: (_: unknown, r) => <ActionTag r={r} /> },
    { title: 'Active', dataIndex: 'enabled', key: 'enabled', render: (e: boolean) => (e ? 'Yes' : <Tag>Off</Tag>) },
    { title: 'Changed', dataIndex: 'updatedAt', key: 'updatedAt', render: (v?: string) => fmtDate(v) },
    ...(canEdit ? [{ title: 'Edit', key: 'edit', render: (_: unknown, r: PolicyRule) => editButton(r) }] : []),
  ]

  const rules = data ?? []
  let body
  if (isPending) body = <PageLoading label="Loading policy rules…" />
  else if (isError) body = <PageError title="Could not load policy rules" onRetry={() => void refetch()} />
  else if (rules.length === 0) body = <PageEmpty description="No policy rules configured" />
  else if (isMobile)
    body = (
      <List
        dataSource={rules}
        split={false}
        renderItem={(r) => (
          <List.Item style={{ padding: '0 0 12px' }}>
            <Card size="small" style={{ width: '100%' }}>
              <Flex justify="space-between" align="center" gap={8} wrap>
                <Typography.Text code>{r.code}</Typography.Text>
                <ActionTag r={r} />
              </Flex>
              <div>{r.description}</div>
              <Flex justify="space-between" align="center" gap={8} style={{ marginTop: 8 }}>
                <Typography.Text strong>{limitText(r)}</Typography.Text>
                {editButton(r)}
              </Flex>
            </Card>
          </List.Item>
        )}
      />
    )
  else body = <Table<PolicyRule> rowKey="code" columns={columns} dataSource={rules} pagination={false} scroll={{ x: 900 }} />

  return (
    <CustomerPage title="Policy rules">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!canEdit ? <Alert type="info" showIcon message="Read-only: only the finance head can change policy rules." /> : null}
        {body}
      </Space>
      <EditRuleDrawer rule={editing} onClose={() => setEditing(null)} />
    </CustomerPage>
  )
}
