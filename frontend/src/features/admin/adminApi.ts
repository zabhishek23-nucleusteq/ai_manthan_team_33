import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { AuditEvent, Department, Page, UserRef } from '../../api/types'
import type { Role } from '../auth/types'

/** Shapes of /api/admin/** (contracts/openapi.yaml tag admin, backend AdminDtos / AuthDtos.UserView). */
export interface AdminUser {
  id: number
  name: string
  email: string
  employeeCode?: string | null
  roles: Role[]
  department?: Department | null
  manager?: UserRef | null
  enabled: boolean
}

export interface CreateUserInput {
  name: string
  email: string
  password: string
  roles: Role[]
}

export interface Totals {
  spend: number
  claims: number
  pendingApprovals: number
  violations: number
  autoApprovedRate: number
  avgApprovalHours?: number | null
}

export interface Overview {
  totals?: Totals
  usersByRole: Record<string, number>
  activeUsers: number
  totalUsers: number
  newUsersInRange: number
}

export interface ServiceStatus {
  name: string
  status: string
  latencyMs: number
}

export interface Health {
  services: ServiceStatus[]
  errorRate5m: number
  oldestPendingApprovalHours?: number | null
}

export interface Range {
  from: string
  to: string
}

export const adminKeys = {
  users: (q: string, role: string, page: number) => ['admin', 'users', q, role, page] as const,
  audit: (f: AuditFilter, page: number) => ['admin', 'audit', f, page] as const,
  overview: (r: Range) => ['admin', 'overview', r.from, r.to] as const,
  health: ['admin', 'health'] as const,
}

export function useAdminUsers(q: string, role: string, page: number, size = 20) {
  return useQuery({
    queryKey: adminKeys.users(q, role, page),
    queryFn: () => api.get<Page<AdminUser>>('/api/admin/users', { q: q || undefined, role: role || undefined, page, size }),
    placeholderData: keepPreviousData,
  })
}

export function useCreateUser() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateUserInput) => api.post<AdminUser>('/api/admin/users', input),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useReplaceRoles() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, roles }: { id: number; roles: Role[] }) => api.put<AdminUser>(`/api/admin/users/${id}/roles`, { roles }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useSetEnabled() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => api.patch<AdminUser>(`/api/admin/users/${id}`, { enabled }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export interface AuditFilter {
  action?: string
  entityType?: string
  from?: string
  to?: string
}

export function useAdminAudit(f: AuditFilter, page: number, size = 20) {
  return useQuery({
    queryKey: adminKeys.audit(f, page),
    queryFn: () =>
      api.get<Page<AuditEvent>>('/api/admin/audit', {
        action: f.action || undefined,
        entityType: f.entityType || undefined,
        from: f.from,
        to: f.to,
        page,
        size,
      }),
    placeholderData: keepPreviousData,
  })
}

export function useAdminOverview(r: Range) {
  return useQuery({
    queryKey: adminKeys.overview(r),
    queryFn: () => api.get<Overview>('/api/admin/overview', { from: r.from, to: r.to }),
  })
}

export function useAdminHealth() {
  return useQuery({
    queryKey: adminKeys.health,
    queryFn: () => api.get<Health>('/api/admin/health'),
    refetchInterval: 30_000,
  })
}
