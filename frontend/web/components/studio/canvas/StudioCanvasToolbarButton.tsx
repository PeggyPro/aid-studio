'use client'

import type { ReactNode } from 'react'
import { DownOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioChromeIcon, type StudioChromeIconName } from '../theme/StudioChromeIcon'

export interface StudioCanvasToolbarButtonProps {
  label: string
  icon: ReactNode
  iconName?: StudioChromeIconName
  active?: boolean
  disabled?: boolean
  /** 显示图标旁文字标签，且不展示 tooltip */
  showLabel?: boolean
  /** 下拉触发器：图标+文案后显示小三角 */
  caret?: boolean
  /** 下拉展开态，用于 aria / 高亮 */
  expanded?: boolean
  onClick?: () => void
}

/**
 * 画布玻璃工具栏按钮。
 * 注意：禁止在此 stopPropagation——下拉（Popover/Dropdown）依赖点击冒泡到触发壳；
 * 画布隔离由工具栏容器的 onPointerDown stopPropagation 负责。
 */
export function StudioCanvasToolbarButton({
  label,
  icon,
  iconName,
  active = false,
  disabled = false,
  showLabel = false,
  caret = false,
  expanded = false,
  onClick
}: StudioCanvasToolbarButtonProps) {
  const button = (
    <button
      type="button"
      className={[
        'studio-canvas-glass-toolbar__btn',
        showLabel ? 'studio-canvas-glass-toolbar__btn--labeled' : '',
        caret && !showLabel ? 'studio-canvas-glass-toolbar__btn--icon-caret' : '',
        active || expanded ? 'is-active' : ''
      ].filter(Boolean).join(' ')}
      aria-label={label}
      aria-haspopup={caret ? 'menu' : undefined}
      aria-expanded={caret ? expanded : undefined}
      disabled={disabled}
      onClick={onClick}
    >
      {iconName ? <StudioChromeIcon name={iconName} fallback={icon} /> : icon}
      {showLabel ? <span className="studio-canvas-glass-toolbar__label">{label}</span> : null}
      {caret ? <DownOutlined className="studio-canvas-glass-toolbar__caret" aria-hidden /> : null}
    </button>
  )

  // 下拉触发器 / 已展示文案：不再包 Tooltip，避免多一层壳挡掉 Popover 点击
  if (showLabel || caret) return button

  return (
    <StudioCanvasTooltip title={disabled ? `${label}暂不可用` : label}>
      {button}
    </StudioCanvasTooltip>
  )
}

export default StudioCanvasToolbarButton
