import { LockOutlined, MailOutlined } from '@ant-design/icons'
import { Alert, Button, Collapse, Form, Input, Typography } from 'antd'
import { useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { AuthLayout } from './AuthLayout'
import { useAuth } from './authContext'
import { isMockAuth } from './authApi'
import { DEMO_PASSWORD } from './mockAuth'
import { landingRouteFor, safeNext } from './roles'
import { ApiError, type LoginRequest } from './types'

const DEMO_ACCOUNTS: Array<[string, string]> = [
  ['employee@expenseflow.test', 'Employee'],
  ['manager@expenseflow.test', 'Manager'],
  ['auditor@expenseflow.test', 'Finance auditor'],
  ['head@expenseflow.test', 'Finance head + admin'],
  ['admin@expenseflow.test', 'Super admin'],
]

function loginErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return 'Email or password is incorrect.'
    if (e.status === 0) return 'Cannot reach the server. Check your connection and try again.'
    if (e.status === 429) return 'Too many attempts. Wait a minute and try again.'
  }
  return 'Login failed. Please try again.'
}

export function LoginPage() {
  const { login, isAuthenticated, roles } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [form] = Form.useForm<LoginRequest>()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const next = safeNext(params.get('next'))
  const expired = params.get('expired') === '1'
  const created = params.get('created') === '1'

  if (isAuthenticated) return <Navigate to={next ?? landingRouteFor(roles)} replace />

  const onFinish = async (values: LoginRequest) => {
    setSubmitting(true)
    setError(null)
    try {
      const user = await login(values)
      navigate(next ?? landingRouteFor(user.roles), { replace: true })
    } catch (e) {
      setError(loginErrorMessage(e))
      form.setFieldValue('password', '')
      setSubmitting(false)
    }
  }

  return (
    <AuthLayout title="Log in" subtitle="Submit and track your expense claims.">
      <div aria-live="polite">
        {expired && !error ? (
          <Alert type="warning" showIcon message="Your session ended. Please log in again." style={{ marginBottom: 16 }} />
        ) : null}
        {created && !error ? (
          <Alert type="success" showIcon message="Account created. Log in to continue." style={{ marginBottom: 16 }} />
        ) : null}
      </div>
      {error ? (
        <Alert type="error" showIcon role="alert" message={error} style={{ marginBottom: 16 }} />
      ) : null}

      <Form<LoginRequest>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        onValuesChange={() => error && setError(null)}
        initialValues={{ email: params.get('email') ?? '' }}
        disabled={submitting}
      >
        <Form.Item
          label="Email"
          name="email"
          rules={[
            { required: true, message: 'Enter your email' },
            { type: 'email', message: 'Enter a valid email, e.g. name@company.com' },
          ]}
        >
          <Input prefix={<MailOutlined aria-hidden />} autoComplete="username" inputMode="email" autoFocus />
        </Form.Item>
        <Form.Item label="Password" name="password" rules={[{ required: true, message: 'Enter your password' }]}>
          <Input.Password prefix={<LockOutlined aria-hidden />} autoComplete="current-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          Log in
        </Button>
      </Form>

      <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0, textAlign: 'center' }}>
        New here? <Link to="/signup" style={{ textDecoration: 'underline' }}>Create an account</Link>
      </Typography.Paragraph>

      {isMockAuth() || import.meta.env.DEV ? (
        <Collapse
          ghost
          size="small"
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'demo',
              label: 'Demo accounts',
              children: (
                <ul style={{ paddingInlineStart: 16, margin: 0 }}>
                  {DEMO_ACCOUNTS.map(([email, role]) => (
                    <li key={email}>
                      <Button
                        type="link"
                        size="small"
                        style={{ padding: 0 }}
                        onClick={() => form.setFieldsValue({ email, password: DEMO_PASSWORD })}
                      >
                        {email}
                      </Button>{' '}
                      <Typography.Text type="secondary">({role})</Typography.Text>
                    </li>
                  ))}
                  <li>
                    <Typography.Text type="secondary">Password for all: {DEMO_PASSWORD}</Typography.Text>
                  </li>
                </ul>
              ),
            },
          ]}
        />
      ) : null}
    </AuthLayout>
  )
}
