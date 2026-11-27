/** Mirrors contracts/openapi.yaml (Role, User, LoginRequest, LoginResponse, SignupRequest, Error). */
export type Role = 'EMPLOYEE' | 'MANAGER' | 'FINANCE_AUDITOR' | 'FINANCE_HEAD' | 'SUPER_ADMIN'

export interface UserRef {
  id: number
  name: string
  email: string
}

export interface User {
  id: number
  name: string
  email: string
  roles: Role[]
  employeeCode?: string
  department?: { id: number; name: string }
  manager?: UserRef
  enabled?: boolean
}

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: User
}

export interface SignupRequest {
  name: string
  email: string
  password: string
  departmentId?: number
}

export interface ApiErrorBody {
  code: string
  message: string
  details?: Record<string, unknown>
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details?: Record<string, unknown>

  constructor(status: number, body: Partial<ApiErrorBody>) {
    super(body.message ?? `Request failed (${status})`)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code ?? (status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN')
    this.details = body.details
  }
}
