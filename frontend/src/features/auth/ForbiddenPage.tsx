import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuth } from './authContext'
import { landingRouteFor } from './roles'

export function ForbiddenPage() {
  const { roles, isAuthenticated } = useAuth()
  const navigate = useNavigate()
  return (
    <main>
      <Result
        status="403"
        title="403 - No access"
        subTitle="Your role does not allow this page. If you need it, ask an administrator."
        extra={
          <Button type="primary" onClick={() => navigate(isAuthenticated ? landingRouteFor(roles) : '/login')}>
            {isAuthenticated ? 'Go to my dashboard' : 'Log in'}
          </Button>
        }
      />
    </main>
  )
}
