import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Layout,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Typography,
  theme as antTheme,
  type TableColumnsType,
} from 'antd'
import { useState } from 'react'
import { ClaimStatusTag } from '../components/ClaimStatusTag'
import { PageEmpty, PageError, PageLoading } from '../components/PageState'
import { CLAIM_STATUS } from './claimStatus'
import { brandTokens } from './theme'
import { useThemeMode } from './themeModeContext'

const { Header, Content } = Layout
const { Title, Paragraph, Text } = Typography

interface SampleClaim {
  key: string
  id: string
  title: string
  category: string
  amount: number
  status: string
}

/** Synthetic sample rows for the showcase only. */
const SAMPLE_CLAIMS: SampleClaim[] = [
  { key: '1', id: 'DEMO-1001', title: 'Client visit taxi', category: 'Travel', amount: 1250, status: 'DRAFT' },
  { key: '2', id: 'DEMO-1002', title: 'Team lunch', category: 'Meals', amount: 4800, status: 'PENDING_MANAGER' },
  { key: '3', id: 'DEMO-1003', title: 'Conference ticket', category: 'Training', amount: 26000, status: 'APPROVED' },
  { key: '4', id: 'DEMO-1004', title: 'Hotel stay', category: 'Lodging', amount: 9200, status: 'CLARIFICATION_REQUESTED' },
  { key: '5', id: 'DEMO-1005', title: 'Laptop bag', category: 'Equipment', amount: 3100, status: 'REJECTED' },
  { key: '6', id: 'DEMO-1006', title: 'Flight to Pune', category: 'Travel', amount: 7400, status: 'PAID' },
]

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })

const columns: TableColumnsType<SampleClaim> = [
  { title: 'Claim', dataIndex: 'id', key: 'id', width: 110 },
  { title: 'Title', dataIndex: 'title', key: 'title' },
  { title: 'Category', dataIndex: 'category', key: 'category' },
  {
    title: 'Amount',
    dataIndex: 'amount',
    key: 'amount',
    align: 'right',
    sorter: (a, b) => a.amount - b.amount,
    render: (v: number) => inr.format(v),
  },
  { title: 'Status', dataIndex: 'status', key: 'status', render: (s: string) => <ClaimStatusTag status={s} /> },
]

type StateDemo = 'loading' | 'empty' | 'error' | 'success'

export function DesignPage() {
  const { mode, toggle } = useThemeMode()
  const { token } = antTheme.useToken()
  const [stateDemo, setStateDemo] = useState<StateDemo>('loading')
  const [submitted, setSubmitted] = useState<string | null>(null)

  const swatches: Array<[string, string]> = [
    ['colorPrimary', token.colorPrimary],
    ['colorSuccess', token.colorSuccess],
    ['colorWarning', token.colorWarning],
    ['colorError', token.colorError],
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          paddingInline: 16,
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        <Title level={4} style={{ margin: 0 }}>
          ExpenseFlow design
        </Title>
        <Space>
          <Text id="theme-switch-label">Dark mode</Text>
          <Switch
            aria-labelledby="theme-switch-label"
            checked={mode === 'dark'}
            onChange={toggle}
            checkedChildren={<MoonOutlined />}
            unCheckedChildren={<SunOutlined />}
          />
        </Space>
      </Header>

      <Content style={{ padding: 16, maxWidth: 1200, width: '100%', margin: '0 auto' }}>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Card title="Theme tokens">
            <Row gutter={[16, 16]}>
              {swatches.map(([name, value]) => (
                <Col key={name} xs={12} sm={6}>
                  <div
                    aria-hidden="true"
                    style={{ height: 40, borderRadius: token.borderRadius, background: value }}
                  />
                  <Text strong>{name}</Text>
                  <br />
                  <Text type="secondary" copyable>
                    {value}
                  </Text>
                </Col>
              ))}
            </Row>
            <Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
              borderRadius {brandTokens.borderRadius}px · fontSize {brandTokens.fontSize}px · mode {mode}
            </Paragraph>
          </Card>

          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} lg={6}>
              <Card>
                <Statistic title="Claims this week" value={42} />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card>
                <Statistic title="Pending approval" value={9} valueStyle={{ color: token.colorWarning }} />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card>
                <Statistic title="Approved amount" value={inr.format(184500)} />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card>
                <Statistic title="Avg approval time" value={6.4} suffix="h" precision={1} />
              </Card>
            </Col>
          </Row>

          <Card title="Claim status tags">
            <Space wrap>
              {Object.keys(CLAIM_STATUS).map((s) => (
                <ClaimStatusTag key={s} status={s} />
              ))}
            </Space>
          </Card>

          <Row gutter={[16, 16]}>
            <Col xs={24} lg={10}>
              <Card title="Form">
                <Form
                  layout="vertical"
                  requiredMark="optional"
                  onFinish={(v: { title: string }) => setSubmitted(v.title)}
                >
                  <Form.Item label="Title" name="title" rules={[{ required: true, message: 'Enter a title' }]}>
                    <Input placeholder="e.g. Client visit taxi" />
                  </Form.Item>
                  <Form.Item
                    label="Category"
                    name="category"
                    rules={[{ required: true, message: 'Choose a category' }]}
                  >
                    <Select
                      placeholder="Choose"
                      options={['Travel', 'Meals', 'Lodging', 'Training', 'Equipment'].map((c) => ({
                        value: c,
                        label: c,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item
                    label="Amount (INR)"
                    name="amount"
                    rules={[{ required: true, message: 'Enter an amount greater than 0' }]}
                  >
                    <InputNumber min={1} style={{ width: '100%' }} />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block>
                    Save draft
                  </Button>
                  <div aria-live="polite" style={{ marginTop: 8 }}>
                    {submitted ? <Text type="success">Saved “{submitted}” (demo only)</Text> : null}
                  </div>
                </Form>
              </Card>
            </Col>
            <Col xs={24} lg={14}>
              <Card title="Table">
                <Table<SampleClaim>
                  columns={columns}
                  dataSource={SAMPLE_CLAIMS}
                  pagination={false}
                  size="middle"
                  scroll={{ x: 600 }}
                />
              </Card>
            </Col>
          </Row>

          <Card
            title="Page states"
            extra={
              <Select<StateDemo>
                aria-label="Choose page state"
                value={stateDemo}
                onChange={setStateDemo}
                style={{ width: 130 }}
                options={(['loading', 'empty', 'error', 'success'] as const).map((s) => ({ value: s, label: s }))}
              />
            }
          >
            {stateDemo === 'loading' && <PageLoading label="Loading claims…" />}
            {stateDemo === 'empty' && (
              <PageEmpty
                description="No claims between 20 Nov and 27 Nov"
                actionLabel="Widen range"
                onAction={() => setStateDemo('success')}
              />
            )}
            {stateDemo === 'error' && (
              <PageError message="Could not load claims." onRetry={() => setStateDemo('loading')} />
            )}
            {stateDemo === 'success' && <Text type="success">Loaded 6 claims.</Text>}
          </Card>
        </Space>
      </Content>
    </Layout>
  )
}
