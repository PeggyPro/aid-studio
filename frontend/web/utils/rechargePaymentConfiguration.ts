import { setAuthPublicConfigData } from '@/hooks/useAuthPublicConfig'
import { authPublicConfig } from '@/utils/businessApi'
import type { AuthPublicConfigData } from '@/types/business-api'

let pending: Promise<AuthPublicConfigData> | null = null

/** Every recharge host verifies current channels; stale cached flags cannot authorize payment. */
export function loadRechargePaymentConfiguration(): Promise<AuthPublicConfigData> {
  if (pending) return pending
  pending = authPublicConfig().then(config => {
    setAuthPublicConfigData(config)
    return config
  }).finally(() => { pending = null })
  return pending
}
