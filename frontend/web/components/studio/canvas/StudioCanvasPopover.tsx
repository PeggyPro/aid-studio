'use client'

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { StudioCanvasPopoverCloseContext } from './StudioCanvasPopoverContext'
import { Popover, type PopoverProps } from 'antd'
import type { TooltipRef } from 'antd/es/tooltip'
import { registerStudioComposerPopupAlign } from '@/utils/studio/studioComposerPopupAlign'
import { STUDIO_COMPOSER_POPUP_GAP } from '@/utils/studio/studioComposerPopupContainer'
import { useStudioComposerPopupLayout } from '@/hooks/studio/useStudioComposerPopupLayout'
import './studio-canvas-popover.css'

/** 定位层只管理显隐生命周期，展开动画在内部内容层执行。 */
export const STUDIO_CANVAS_POPOVER_MOTION = 'studio-canvas-popover-in'

/**
 * 画布弹出层的统一外壳。
 * 对齐完成前保持透明，进入 active 后沿最终 placement 展开，避免闪现和重播。
 * 不在定位元素上做缩放或位移，避免测量盒子变化后把面板拽偏。
 * 画布平移只移动触发按钮，弹出层必须在浮层重排后按按钮的新位置重新对齐。
 */
export function StudioCanvasPopover({
  motion,
  onOpenChange,
  nodePlacement = false,
  surface = 'container',
  placement,
  align,
  autoAdjustOverflow,
  content,
  classNames,
  ...props
}: PopoverProps & { nodePlacement?: boolean; surface?: 'container' | 'content' }) {
  const popoverRef = useRef<TooltipRef>(null)
  const [uncontrolledOpen, setUncontrolledOpen] = useState(props.defaultOpen ?? false)
  const visible = props.open ?? uncontrolledOpen
  const changeOpen = useCallback((next: boolean) => {
    if (props.open === undefined) setUncontrolledOpen(next)
    onOpenChange?.(next)
  }, [props.open, onOpenChange])
  const close = useCallback(() => changeOpen(false), [changeOpen])
  const [contentElement, setContentElement] = useState<HTMLDivElement | null>(null)
  const nodeSide = useStudioComposerPopupLayout(popoverRef, contentElement, visible && nodePlacement)
  const [homeScope, setHomeScope] = useState(false)
  const fitViewport = homeScope ? autoAdjustOverflow !== false : autoAdjustOverflow === true
  const popupMotion = useMemo(() => ({
    ...motion,
    motionName: STUDIO_CANVAS_POPOVER_MOTION,
    motionDeadline: motion?.motionDeadline ?? 280
  }), [motion])

  useLayoutEffect(() => {
    if (!visible) return
    const trigger = popoverRef.current?.nativeElement
    if (trigger) setHomeScope(Boolean(trigger.closest('[data-home-theme]')))
  }, [visible])

  useEffect(() => {
    if (!visible || nodePlacement) return
    return registerStudioComposerPopupAlign(() => {
      popoverRef.current?.forceAlign()
    })
  }, [visible, nodePlacement])

  const wrapContent = (value: ReactNode) => (
    <StudioCanvasPopoverCloseContext.Provider value={close}>
      {nodePlacement
        ? <div ref={setContentElement} className="studio-composer-popup-fit studio-canvas-popover-scroll">{value}</div>
        : value}
    </StudioCanvasPopoverCloseContext.Provider>
  )

  return (
    <Popover
      {...props}
      classNames={(info) => {
        const names = typeof classNames === 'function' ? classNames(info) : classNames
        return { ...names, root: ['studio-canvas-popover-motion', surface === 'content' && 'studio-canvas-popover--content-surface', names?.root].filter(Boolean).join(' ') }
      }}
      open={visible}
      content={typeof content === 'function'
        ? () => wrapContent(content())
        : wrapContent(content)}
      ref={popoverRef}
      placement={nodePlacement ? nodeSide : placement}
      align={nodePlacement ? {
        ...align,
        htmlRegion: 'visible',
        offset: [0, nodeSide === 'topLeft' ? -STUDIO_COMPOSER_POPUP_GAP : STUDIO_COMPOSER_POPUP_GAP],
        overflow: { adjustX: true, shiftX: true, adjustY: false, shiftY: false }
      } : fitViewport
        ? {
          htmlRegion: 'visible',
          offset: [0, 0],
          ...align,
          overflow: { adjustX: true, adjustY: true, shiftX: true, shiftY: true, ...align?.overflow }
        }
        : align}
      autoAdjustOverflow={nodePlacement ? false : autoAdjustOverflow}
      onOpenChange={changeOpen}
      motion={popupMotion}
    />
  )
}
