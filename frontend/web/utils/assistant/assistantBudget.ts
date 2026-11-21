import type { AssistantRunBudgetInput, AssistantRunBudgetPolicy, AssistantRunBudgetScope } from '~/types/assistant-budget'

/** DECIMAL(14,4)按整数精度比较，避免余额上限被浮点取整。 */
export function assistantBudgetUnits(value: string): bigint | null {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,9})(\.\d{1,4})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'))
}
export interface AssistantBudgetFormValues {
  mode: 'MANUAL' | 'AUTO'; absoluteMaxCharge: string; perCallMaxCharge: string
  expiresAt: string; maxIterations: number; maxOperations: number; automaticConsent: boolean
}
export function assistantRunBudgetInput(scope: AssistantRunBudgetScope, values: AssistantBudgetFormValues,
  options: { now?: number; quoteUpperBound?: string; policy?: AssistantRunBudgetPolicy } = {}): AssistantRunBudgetInput {
  if (!scope?.scopeDigest || scope.amountScale !== 4 || !scope.availableModes?.includes(values.mode)
    || !Number.isInteger(scope.maxIterations) || scope.maxIterations < 1
    || !Number.isInteger(scope.maxOperations) || scope.maxOperations < 1
    || !Number.isFinite(scope.maxTtlSeconds) || scope.maxTtlSeconds <= 0
    || ![scope.allowedToolCodes, scope.allowedModelCodes, scope.allowedCapabilityCodes, scope.allowedMediaTypes, scope.targetRefs, scope.automaticForbiddenOperationKinds].every(Array.isArray)) throw new Error('当前授权范围尚未就绪，请重新读取')
  const absolute = assistantBudgetUnits(values.absoluteMaxCharge), perCall = assistantBudgetUnits(values.perCallMaxCharge)
  if (absolute == null || perCall == null || perCall > absolute) throw new Error('请填写非负的总上限和单次上限，最多四位小数；单次不能超过总上限')
  const quote = options.quoteUpperBound ? assistantBudgetUnits(options.quoteUpperBound) : null
  if (quote != null && quote > perCall) throw new Error('单次上限不能低于当前报价的最高消耗')
  const policy = options.policy
  if (policy) {
    const reserved = assistantBudgetUnits(policy.reserved), settled = assistantBudgetUnits(policy.settled)
    if (reserved == null || settled == null) throw new Error('无法核验已占用余额，请重新读取预算')
    if (absolute < reserved + settled) throw new Error('总上限不能低于已预留与已结算金额之和')
  }
  if (!Number.isInteger(values.maxIterations) || values.maxIterations < Math.max(1, policy?.usedIterations || 0) || values.maxIterations > scope.maxIterations
    || !Number.isInteger(values.maxOperations) || values.maxOperations < Math.max(1, policy?.usedOperations || 0) || values.maxOperations > scope.maxOperations) throw new Error('执行次数必须在允许范围内，且不能小于已使用次数')
  const expires = Date.parse(values.expiresAt), now = options.now ?? Date.now()
  if (!Number.isFinite(expires) || expires <= now || expires > now + scope.maxTtlSeconds * 1000) throw new Error('有效期必须在当前时间之后，且不超过服务端允许期限')
  if (values.mode === 'AUTO' && !values.automaticConsent) throw new Error('请明确确认自动执行的范围与限制')
  return { mode: values.mode, absoluteMaxCharge: values.absoluteMaxCharge, perCallMaxCharge: values.perCallMaxCharge,
    expiresAt: new Date(expires).toISOString(), maxIterations: values.maxIterations, maxOperations: values.maxOperations,
    scopeDigest: scope.scopeDigest, allowedToolCodes: [...scope.allowedToolCodes], allowedModelCodes: [...scope.allowedModelCodes],
    allowedCapabilityCodes: [...scope.allowedCapabilityCodes], allowedMediaTypes: [...scope.allowedMediaTypes],
    targetRefs: structuredClone(scope.targetRefs), automaticForbiddenOperationKinds: [...scope.automaticForbiddenOperationKinds] }
}

/** Explicit continuation and automatic conversation use the same server-provided limits. */
export function assistantConversationRunBudgetInput(scope: AssistantRunBudgetScope, mode: 'MANUAL' | 'AUTO', quoteUpperBound: string,
  now = Date.now()): AssistantRunBudgetInput {
  const defaults = scope.conversationBudget
  if (!defaults || !Number.isInteger(defaults.ttlSeconds) || defaults.ttlSeconds < 60 || defaults.ttlSeconds > scope.maxTtlSeconds) {
    throw Object.assign(new Error('助手执行配置尚未就绪，请联系管理员配置会话授权范围'), {
      errorCode: 'ASSISTANT_CONFIGURATION_CONVERSATION_SCOPE',
    })
  }
  return assistantRunBudgetInput(scope, { mode, automaticConsent: mode === 'AUTO', absoluteMaxCharge: defaults.absoluteMaxCharge,
    perCallMaxCharge: defaults.perCallMaxCharge, maxIterations: defaults.maxIterations, maxOperations: defaults.maxOperations,
    expiresAt: new Date(now + defaults.ttlSeconds * 1000 - 1000).toISOString() }, { now, quoteUpperBound })
}
