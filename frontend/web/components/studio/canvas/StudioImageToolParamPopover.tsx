'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import type { ReactNode } from 'react'
import type { TooltipPlacement } from 'antd/es/tooltip'
import '@/components/studio/composer/studio-image-ai-tools.css'

const PARAM_POPOVER_CLASS =
  'studio-composer-popover studio-composer-popover--canvas studio-image-tool-param-popover'

/** 画布图片工具栏单项参数弹层（比例 / 清晰度 / 张数 / 精细度等，各自独立面板）。 */
export function StudioImageToolParamPopover({
  label,
  trigger,
  children,
  placement = 'top',
  open,
  onOpenChange
}: {
  label: string
  trigger: ReactNode
  children: ReactNode
  placement?: TooltipPlacement
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={onOpenChange}
      trigger="click"
      placement={placement}
      arrow={false}
      destroyOnHidden={false}
      getPopupContainer={(node) => node.closest('.studio-canvas') ?? document.body}
      classNames={{ root: PARAM_POPOVER_CLASS }}
      content={(
        <div className="studio-image-tool-param-panel" role="group" aria-label={label}>
          <div className="studio-image-tool-param-panel__row">
            <span className="studio-image-tool-param-panel__label">{label}</span>
            <div className="studio-image-tool-param-panel__control">{children}</div>
          </div>
        </div>
      )}
    >
      <span className="studio-image-tool-param-popover__trigger">{trigger}</span>
    </StudioCanvasPopover>
  )
}
