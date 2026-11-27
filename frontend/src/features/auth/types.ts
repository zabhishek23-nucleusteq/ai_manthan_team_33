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

export { ApiError, type ApiErrorBody } from '../../api/errors'
