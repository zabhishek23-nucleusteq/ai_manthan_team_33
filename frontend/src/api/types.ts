/**
 * Shared types mirroring contracts/openapi.yaml components/schemas.
 * Add a type here when a feature starts using that schema; never add fields the contract does not have.
 */
export type ClaimStatus =
  | 'DRAFT'
  | 'AUTO_REJECTED'
  | 'PENDING_MANAGER'
  | 'PENDING_AUDITOR'
  | 'PENDING_HEAD'
  | 'CLARIFICATION_REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'IN_PAYOUT'
  | 'PAID'

export type Category = 'MEAL' | 'HOTEL' | 'CAB' | 'TRAVEL' | 'OTHER'

export type ViolationCode =
  | 'MEAL_DAILY_LIMIT'
  | 'HOTEL_NIGHTLY_LIMIT'
  | 'CAB_TRIP_LIMIT'
  | 'RECEIPT_REQUIRED'
  | 'DUPLICATE_LINE'
  | 'DUPLICATE_RECEIPT'
  | 'WEEKEND_SPEND'

export type ApprovalLevel = 'MANAGER' | 'AUDITOR' | 'HEAD'

export interface Department {
  id: number
  name: string
}

export interface UserRef {
  id: number
  name: string
  email: string
}

export interface Receipt {
  id: number
  fileName: string
  contentType: string
  size: number
  sha256: string
}

export interface ClaimLineInput {
  category: Category
  amount: number
  expenseDate: string // yyyy-mm-dd
  vendor: string
  description?: string
  city?: string
  nights?: number
  receiptId?: number
}

export interface ClaimInput {
  title: string
  lines: ClaimLineInput[]
}

export interface Violation {
  code: ViolationCode
  action: 'ROUTE' | 'REJECT'
  message: string
}

export interface ClaimLine extends ClaimLineInput {
  id: number
  receipt?: Receipt
  violations?: Violation[]
}

export interface PolicyResult {
  outcome: 'AUTO_APPROVED' | 'ROUTED' | 'AUTO_REJECTED'
  reasons: string[]
  lineResults: Array<{ lineId: number; violations: Violation[] }>
  requiredLevels: ApprovalLevel[]
}

export interface ApprovalStep {
  level: ApprovalLevel
  assignee?: UserRef
  actedBy?: UserRef
  onBehalfOf?: UserRef
  decision: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SENT_BACK' | 'SUPERSEDED'
  comment?: string
  createdAt?: string
  decidedAt?: string
}

export interface Claim {
  id: number
  code: string
  title: string
  status: ClaimStatus
  total: number
  employee?: UserRef
  department?: Department
  lines: ClaimLine[]
  policyResult?: PolicyResult
  steps?: ApprovalStep[]
  sampleAudit?: 'NONE' | 'PENDING' | 'OK' | 'EXCEPTION'
  createdAt?: string
  submittedAt?: string
  decidedAt?: string
  version?: number
}

export interface ClaimSummary {
  id: number
  code: string
  title: string
  status: ClaimStatus
  total: number
  lineCount: number
  violationCodes: ViolationCode[]
  submittedAt?: string
  updatedAt?: string
}

export interface Comment {
  id: number
  author: UserRef
  text: string
  createdAt: string
}

export interface AuditEvent {
  id: number
  at: string
  actor?: UserRef
  onBehalfOf?: UserRef
  action: string
  entityType?: string
  entityId?: string
  before?: Record<string, unknown>
  after?: Record<string, unknown>
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export type ClaimSummaryPage = Page<ClaimSummary>
