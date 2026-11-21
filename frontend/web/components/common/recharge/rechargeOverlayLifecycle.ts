import type { CSSProperties } from 'react'

export const RECHARGE_MODAL_Z_INDEX = 1000
export const RECHARGE_HISTORY_DRAWER_Z_INDEX = RECHARGE_MODAL_Z_INDEX + 100

/**
 * Drawer 退场动画期间仍会保留 Portal 和 mask。
 * 受控状态关闭后立刻禁用 mask 命中，并在动画结束后销毁 Portal，避免透明遮罩吞掉后续点击。
 */
export function rechargeHistoryDrawerOverlayProps(open: boolean): {
  destroyOnHidden: true
  zIndex: number
  styles: { mask: CSSProperties }
} {
  return {
    destroyOnHidden: true,
    zIndex: RECHARGE_HISTORY_DRAWER_Z_INDEX,
    styles: {
      mask: { pointerEvents: open ? 'auto' : 'none' }
    }
  }
}
