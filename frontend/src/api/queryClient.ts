import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './errors'

/** Retry only transient failures (network / 5xx), never 4xx business or auth errors. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= 2) return false
  if (error instanceof ApiError) return error.status === 0 || error.status >= 500
  return true
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, refetchOnWindowFocus: false, staleTime: 30_000 },
      mutations: { retry: false },
    },
  })
}
