import type { CreationStep } from '@/types'
import { creationStepToRoutePath } from '@/utils/createFlowRoutes'

/**
 * Drawer 收起后仍保留步骤组件挂载以承接既有任务恢复，但全局 route snapshot
 * 必须立即回到 Studio，避免后台回调误判仍处于七步路由。
 */
export function studioFlowEmbeddedRoutePath(open: boolean, step: CreationStep | null): string {
  return open && step ? creationStepToRoutePath(step) : '/create/studio'
}
