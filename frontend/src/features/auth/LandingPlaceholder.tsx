import { LogoutOutlined } from '@ant-design/icons'
import { Button, Result, Space, Tag } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuth } from './authContext'
import { ROLE_LABEL } from './roles'

/**
 * TEMPORARY landing page so login/guards are demoable before the role layout and dashboards (T-006, L5) land.
 * L5: replace the routes that use this in App.tsx.
 */
export function LandingPlaceholder({ title }: { title: string }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <main>
      <Result
        status="success"
        title={title}
        subTitle={`Logged in as ${user?.name ?? ''} (${user?.email ?? ''})`}
        extra={
          <Space direction="vertical" align="center">
            <Space wrap>
              {user?.roles.map((r) => (
                <Tag key={r} color="blue">
                  {ROLE_LABEL[r]}
                </Tag>
              ))}
            </Space>
            <Button
              icon={<LogoutOutlined />}
              onClick={() => {
                logout()
                navigate('/login', { replace: true })
              }}
            >
              Log out
            </Button>
          </Space>
        }
      />
    </main>
  )
}
