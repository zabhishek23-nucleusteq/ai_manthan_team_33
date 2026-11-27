import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Button, Result } from 'antd'
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { DesignPage } from './design/DesignPage'
import { ThemeProvider } from './design/ThemeProvider'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

function NotFound() {
  return (
    <Result
      status="404"
      title="Page not found"
      subTitle="The page you opened does not exist."
      extra={
        <Link to="/">
          <Button type="primary">Go home</Button>
        </Link>
      }
    />
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/design" replace />} />
      <Route path="/design" element={<DesignPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
