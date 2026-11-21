import type { AssistantCapabilities, AssistantModule } from '~/types/assistant-api'

const unavailable = '当前助手暂不可用，请稍后重试。'
const executionUnavailable = '当前创作服务暂不可用。'
const safeReasons = new Map([
  ['MODULE_AGENT_UNAVAILABLE', '当前助手未绑定或暂不可用。'],
  ['MODULE_EXECUTOR_UNAVAILABLE', '当前助手的执行能力暂不可用。'],
  ['SPATIAL_ENGINE_UNAVAILABLE', '空间预演引擎尚未接通。']
])

/** Module readiness comes from the server; never infer it from another module's summary. */
export function assistantCapabilitiesForModule(value: AssistantCapabilities, moduleCode: AssistantModule): AssistantCapabilities {
  // MAIN has a separate deployment lifecycle. Keep its existing contract until it
  // advertises the new list; Canvas must always provide explicit module readiness.
  if (value.transport === 'MAIN' && value.moduleCapabilities === undefined) return value
  const candidates = Array.isArray(value.moduleCapabilities)
    ? value.moduleCapabilities.filter(item => item?.moduleCode === moduleCode) : []
  const selected = candidates.length === 1 && value.modules.includes(moduleCode) ? candidates[0] : undefined
  const operationPolicyReady = moduleCode !== 'SPATIAL_PREVIS'
    || selected?.operationMode === 'GUIDANCE_ONLY' && selected.engineAvailable === false
  const planningAvailable = operationPolicyReady && selected?.planningAvailable === true
  const executionAvailable = planningAvailable && selected?.executionAvailable === true
  return {
    ...value,
    planningAvailable,
    executionAvailable,
    planningUnavailableReason: planningAvailable ? undefined : safeReasons.get(selected?.planningUnavailableReasonCode || '') || unavailable,
    executionUnavailableReason: executionAvailable ? undefined : safeReasons.get(selected?.executionUnavailableReasonCode || '')
      || (!planningAvailable ? safeReasons.get(selected?.planningUnavailableReasonCode || '') : undefined) || executionUnavailable
  }
}
