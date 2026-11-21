'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useContext, type ReactNode } from 'react'
import { StudioCanvasPopoverCloseContext } from './StudioCanvasPopoverContext'
import type { PopoverProps } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import type { TooltipPlacement } from 'antd/es/tooltip'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import '@/components/studio/composer/studio-canvas-dropdown.css'

export interface StudioCanvasDropdownItem {
  key: string
  label: ReactNode
  description?: string
  /** 模型菜单由行背景表达选中，不额外占用对号列。 */
  showSelectionCheck?: boolean
  icon?: ReactNode
  disabled?: boolean
  /** 禁用项悬停说明（包一层可聚焦壳，避免 disabled button 吃掉 tooltip） */
  disabledHint?: string
  /** 子面板需要继续依附菜单项，选择时由调用方决定何时关闭。 */
  keepOpenOnSelect?: boolean
  renderTrigger?: (button: ReactNode) => ReactNode
  /** A separate row action must not select the row or close its menu. */
  action?: { label: string; icon: ReactNode; disabled?: boolean; onClick: () => void }
}

/** 画布内统一下拉菜单（AI 工具 / 裁剪比例等），8px 圆角，禁止 pill 形态。 */
export function StudioCanvasDropdownMenu({
  items,
  activeKey,
  onSelect,
  ariaLabel
}: {
  items: StudioCanvasDropdownItem[]
  activeKey?: string
  onSelect: (key: string) => void
  ariaLabel?: string
}) {
  const closePopover = useContext(StudioCanvasPopoverCloseContext)
  return (
    <div className="studio-canvas-dropdown-menu studio-canvas-popover-scroll" role="menu" aria-label={ariaLabel}>
      {items.map((item) => {
        const button = (
          <button
            type="button"
            role="menuitem"
            className={`studio-canvas-dropdown-menu__item${activeKey === item.key ? ' is-active' : ''}`}
            disabled={item.disabled}
            aria-current={activeKey === item.key ? 'true' : undefined}
            onClick={() => {
              if (item.disabled) return
              if (!item.keepOpenOnSelect) closePopover?.()
              onSelect(item.key)
            }}
          >
            {item.icon ? <i aria-hidden>{item.icon}</i> : null}
            <span className={item.description ? 'studio-canvas-dropdown-menu__copy' : undefined}>
              {item.description ? <strong>{item.label}</strong> : item.label}
              {item.description ? <small>{item.description}</small> : null}
            </span>
            {item.showSelectionCheck !== false && item.description && activeKey === item.key ? <CheckOutlined className="studio-canvas-dropdown-menu__check" /> : null}
          </button>
        )

        // 所有行使用同一布局壳，Tooltip / 子面板仅装饰行，不决定按钮宽度。
        const row = (
          <span key={item.key} className={`studio-canvas-dropdown-menu__item-shell${item.action ? ' has-action' : ''}`} role="none">
            {item.renderTrigger ? item.renderTrigger(button) : button}
            {item.action && <StudioCanvasTooltip title={item.action.label}>
              <button type="button" role="menuitem" className="studio-canvas-dropdown-menu__action" aria-label={item.action.label}
                disabled={item.action.disabled} onClick={event => { event.stopPropagation(); item.action?.onClick() }}>{item.action.icon}</button>
            </StudioCanvasTooltip>}
          </span>
        )

        if (item.disabled && item.disabledHint) {
          return (
            <StudioCanvasTooltip key={item.key} title={item.disabledHint} placement="right">
              {row}
            </StudioCanvasTooltip>
          )
        }

        return row
      })}
    </div>
  )
}

export const STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS =
  'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover'

/** Popover + 统一下拉菜单，供画布工具栏参数选择复用。 */
export function StudioCanvasDropdownPopover({
  items,
  activeKey,
  onSelect,
  trigger,
  placement = 'top',
  nodePlacement = false,
  autoAdjustOverflow = true,
  align,
  open,
  onOpenChange,
  ariaLabel,
  getPopupContainer
}: {
  items: StudioCanvasDropdownItem[]
  activeKey?: string
  onSelect: (key: string) => void
  trigger: ReactNode
  placement?: TooltipPlacement
  /** 测量触发按钮下方是否能完整容纳弹层，不足时向上展开。 */
  nodePlacement?: boolean
  autoAdjustOverflow?: PopoverProps['autoAdjustOverflow']
  align?: PopoverProps['align']
  open?: boolean
  onOpenChange?: (open: boolean) => void
  ariaLabel?: string
  getPopupContainer?: (triggerNode: HTMLElement) => HTMLElement
}) {
  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={onOpenChange}
      trigger="click"
      placement={placement}
      nodePlacement={nodePlacement}
      autoAdjustOverflow={autoAdjustOverflow}
      align={align}
      arrow={false}
      destroyOnHidden={false}
      getPopupContainer={getPopupContainer ?? ((node) => node.closest<HTMLElement>('[data-studio-free-theme-scope], .studio-canvas') ?? document.body)}
      classNames={{ root: STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS }}
      content={
        <StudioCanvasDropdownMenu
          items={items}
          activeKey={activeKey}
          onSelect={onSelect}
          ariaLabel={ariaLabel}
        />
      }
    >
      <span className="studio-canvas-dropdown-popover__trigger">{trigger}</span>
    </StudioCanvasPopover>
  )
}
