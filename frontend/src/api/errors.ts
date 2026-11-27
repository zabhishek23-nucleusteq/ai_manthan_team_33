/** Error schema from contracts/openapi.yaml: { code, message, details }. */
export interface ApiErrorBody {
  code: string
  message: string
  details?: Record<string, unknown>
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details?: Record<string, unknown>

  constructor(status: number, body: Partial<ApiErrorBody> = {}) {
    super(body.message || defaultMessage(status))
    this.name = 'ApiError'
    this.status = status
    this.code = body.code || defaultCode(status)
    this.details = body.details
  }
}

function defaultCode(status: number): string {
  if (status === 0) return 'NETWORK_ERROR'
  if (status === 401) return 'UNAUTHENTICATED'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status >= 500) return 'SERVER_ERROR'
  return 'UNKNOWN'
}

function defaultMessage(status: number): string {
  if (status === 0) return 'Cannot reach the server. Check your connection.'
  if (status >= 500) return 'The server had a problem. Please try again.'
  return `Request failed (${status})`
}

/** Builds an ApiError from a non-2xx Response; tolerates empty or non-JSON bodies. */
export async function parseApiError(res: Response): Promise<ApiError> {
  let body: Partial<ApiErrorBody> = {}
  try {
    const text = await res.text()
    if (text) {
      const json = JSON.parse(text) as unknown
      if (json && typeof json === 'object') body = json as Partial<ApiErrorBody>
    }
  } catch {
    /* non-JSON body: keep defaults */
  }
  return new ApiError(res.status, body)
}

export function isApiError(e: unknown, code?: string): e is ApiError {
  return e instanceof ApiError && (code === undefined || e.code === code)
}
