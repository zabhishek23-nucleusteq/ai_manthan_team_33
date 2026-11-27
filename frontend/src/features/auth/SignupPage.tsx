import { LockOutlined, MailOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Form, Input, Typography } from 'antd'
import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { AuthLayout } from './AuthLayout'
import { useAuth } from './authContext'
import { landingRouteFor } from './roles'
import { ApiError } from './types'

interface SignupForm {
  name: string
  email: string
  password: string
  confirm: string
}

export function SignupPage() {
  const { signup, isAuthenticated, roles } = useAuth()
  const navigate = useNavigate()
  const [form] = Form.useForm<SignupForm>()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (isAuthenticated) return <Navigate to={landingRouteFor(roles)} replace />

  const onFinish = async ({ name, email, password }: SignupForm) => {
    setSubmitting(true)
    setError(null)
    try {
      const user = await signup({ name, email, password })
      navigate(`/login?created=1&email=${encodeURIComponent(user.email)}`, { replace: true })
    } catch (e) {
      setSubmitting(false)
      if (e instanceof ApiError && e.code === 'EMAIL_TAKEN') {
        form.setFields([{ name: 'email', errors: ['An account with this email already exists. Log in instead.'] }])
      } else if (e instanceof ApiError && e.status === 400) {
        setError('Some details are not valid. Check the fields and try again.')
      } else if (e instanceof ApiError && e.status === 0) {
        setError('Cannot reach the server. Check your connection and try again.')
      } else {
        setError('Sign-up failed. Please try again.')
      }
    }
  }

  return (
    <AuthLayout title="Create account" subtitle="New accounts start as Employee. An admin can add more roles.">
      {error ? <Alert type="error" showIcon role="alert" message={error} style={{ marginBottom: 16 }} /> : null}
      <Form<SignupForm> form={form} layout="vertical" requiredMark={false} onFinish={onFinish} disabled={submitting}>
        <Form.Item
          label="Full name"
          name="name"
          rules={[
            { required: true, whitespace: true, message: 'Enter your full name' },
            { min: 2, max: 100, message: 'Name must be 2 to 100 characters' },
          ]}
        >
          <Input prefix={<UserOutlined aria-hidden />} autoComplete="name" autoFocus />
        </Form.Item>
        <Form.Item
          label="Work email"
          name="email"
          rules={[
            { required: true, message: 'Enter your email' },
            { type: 'email', message: 'Enter a valid email, e.g. name@company.com' },
          ]}
        >
          <Input prefix={<MailOutlined aria-hidden />} autoComplete="email" inputMode="email" />
        </Form.Item>
        <Form.Item
          label="Password"
          name="password"
          extra="At least 8 characters."
          rules={[
            { required: true, message: 'Choose a password' },
            { min: 8, max: 100, message: 'Password must be 8 to 100 characters' },
          ]}
        >
          <Input.Password prefix={<LockOutlined aria-hidden />} autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          label="Confirm password"
          name="confirm"
          dependencies={['password']}
          rules={[
            { required: true, message: 'Type the password again' },
            ({ getFieldValue }) => ({
              validator: (_, v) =>
                !v || v === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error('Passwords do not match')),
            }),
          ]}
        >
          <Input.Password prefix={<LockOutlined aria-hidden />} autoComplete="new-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          Create account
        </Button>
      </Form>
      <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0, textAlign: 'center' }}>
        Already have an account? <Link to="/login" style={{ textDecoration: 'underline' }}>Log in</Link>
      </Typography.Paragraph>
    </AuthLayout>
  )
}
