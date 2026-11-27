import { Button, Card, List, Tag, Typography } from 'antd'
import { Navigate, useLocation } from 'react-router-dom'
import { landingRoute, ROLE_LABEL, ROLE_PRIORITY } from './roles'
import { DEMO_USERS, useSession } from './session'

/**
 * TEMPORARY until the real Login page (T-005, L4) lands: pick a demo user to see each role's
 * menu and landing dashboard. No password, no token - nothing here talks to the backend.
 */
export function DemoLogin() {
  const { user, signIn } = useSession()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from

  // Single redirect after sign-in: back to the page that sent us here, else the role's landing.
  if (user) return <Navigate to={from && from !== '/' ? from : (landingRoute(user.roles) ?? '/')} replace />

  return (
    <main style={{ maxWidth: 520, margin: '0 auto', padding: 16 }}>
      <Card>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          ExpenseFlow - demo sign in
        </Typography.Title>
        <Typography.Paragraph type="secondary">
          Temporary role picker until the login page is ready. Choose a demo user.
        </Typography.Paragraph>
        <List
          dataSource={[...DEMO_USERS]}
          renderItem={(u) => (
            <List.Item
              actions={[
                <Button
                  key="go"
                  type="primary"
                  aria-label={`Sign in as ${u.name}`}
                  onClick={() => signIn(u)}
                >
                  Sign in
                </Button>,
              ]}
            >
              <List.Item.Meta
                title={u.name}
                description={ROLE_PRIORITY.filter((r) => u.roles.includes(r)).map((r) => (
                  <Tag key={r}>{ROLE_LABEL[r]}</Tag>
                ))}
              />
            </List.Item>
          )}
        />
      </Card>
    </main>
  )
}
