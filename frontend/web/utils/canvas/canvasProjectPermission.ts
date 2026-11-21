import type { CanvasProjectRole } from '@/types/canvas-api'

/** 角色是否允许在画布上本地编辑。加载中（role 未知）不锁死画布；仅 VIEWER 只读。 */
export function canvasProjectCanEdit(role: CanvasProjectRole | null | undefined): boolean {
  return role !== 'VIEWER'
}

/** 是否允许向服务端提交共享写入。需要已确认非 VIEWER 角色，且协作连接就绪。 */
export function canvasProjectCanMutate(
  role: CanvasProjectRole | null | undefined,
  connected: boolean
): boolean {
  return Boolean(role && role !== 'VIEWER' && connected)
}
