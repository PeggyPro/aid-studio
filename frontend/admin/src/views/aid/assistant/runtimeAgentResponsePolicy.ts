import type { RuntimeAgentProtocolVersion, RuntimeAgentResponsePolicy } from '@/api/aid/assistantRuntimeAgent';

export function normalizeRuntimeAgentResponsePolicy(
  policy?: RuntimeAgentResponsePolicy,
  protocolVersion: RuntimeAgentProtocolVersion = 'assistant-loop/native-v1'
): RuntimeAgentResponsePolicy {
  return {
    ...policy,
    mode: policy?.mode ?? (protocolVersion === 'assistant-loop/v1' ? 'STRICT_LOCAL_JSON' : 'NATIVE_TOOL_STREAM'),
    maxRepairAttempts: 0
  };
}
