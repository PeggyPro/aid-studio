/** 总运行授权与单次报价独立；scope数组只回传服务器权威投影。 */
export interface AssistantBudgetTargetRef {
  kind: 'FREE_CANVAS' | 'FLOW_ENTITY' | 'SCRIPT' | 'CANVAS_RESOURCE'
  resourceId?: string
  workspaceId?: string; objectId?: string; version?: string
  projectId?: string; episodeId?: string; entityType?: string; entityId?: string
  scriptId?: string; contentHash?: string; reason?: string
  selection?: { start: number; end: number; selectedTextHash: string }
}
export interface AssistantBudgetPermissions {
  allowedToolCodes: string[]
  allowedModelCodes: string[]
  allowedCapabilityCodes: string[]
  allowedMediaTypes: string[]
  targetRefs: AssistantBudgetTargetRef[]
  automaticForbiddenOperationKinds: string[]
}
export interface AssistantRunBudgetScope extends AssistantBudgetPermissions {
  /** 服务端发现的当前能力与对象范围摘要，供提交授权时核对。 */
  scopeDigest: string
  availableModes: ('MANUAL' | 'AUTO')[]
  maxTtlSeconds: number
  maxIterations: number
  maxOperations: number
  amountScale: number
  conversationBudget?: { absoluteMaxCharge: string; perCallMaxCharge: string; maxIterations: number; maxOperations: number; ttlSeconds: number }
}
export interface AssistantRunBudgetInput extends AssistantBudgetPermissions {
  /** 使用 availableRunBudgetScope.scopeDigest，不回传已保存策略的摘要。 */
  scopeDigest: string
  mode: 'MANUAL' | 'AUTO'
  absoluteMaxCharge: string
  perCallMaxCharge: string
  expiresAt: string
  maxIterations: number
  maxOperations: number
}
export interface AssistantRunBudgetPolicy extends Omit<AssistantRunBudgetInput, 'scopeDigest'> {
  /** 已保存的完整策略摘要，包含模式、金额、次数与期限；不能与能力范围摘要比较。 */
  scopeDigest: string
  runId: string
  runRevision: number
  lastEventSeq: number
  policyRevision: number
  authorizationId: string
  authorizationRevision: number
  status: string
  canRenew?: boolean
  renewBlockReason?: string | null
  renewalNeedsConfirmation?: boolean
  reserved: string
  settled: string
  remaining: string
  currencyUnit: 'BALANCE'
  precisionScale: 4
  usedIterations: number
  usedOperations: number
  scopeState: 'CURRENT' | 'CONTEXT_CHANGED' | 'HISTORICAL'
  availableRunBudgetScope?: AssistantRunBudgetScope | null
}

export interface AssistantBudgetRevisionResult {
  runRevision: number
  budgetPolicy: AssistantRunBudgetPolicy
  invalidatedApprovalIds: string[]
  renewedExpired?: boolean
}

export type CanvasBudget = Pick<AssistantRunBudgetInput, 'absoluteMaxCharge' | 'perCallMaxCharge'>
