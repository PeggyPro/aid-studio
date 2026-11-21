'use client'

import type { KeyboardEvent } from 'react'
import {
  NodeResizeControl,
  type OnResize,
  type OnResizeEnd,
  type OnResizeStart,
  type ShouldResize
} from '@xyflow/react'

interface StudioVerticalResizeHandlesProps {
  label: string
  minHeight: number
  maxHeight?: number
  onResizeStart?: OnResizeStart
  onResize?: OnResize
  onResizeEnd?: OnResizeEnd
  shouldResize?: ShouldResize
  onKeyboardResize: (direction: 'expand' | 'shrink' | 'widen' | 'narrow', step: number) => void
}

/** 分镜组等业务节点的竖直缩放（非 parent 分组框）。 */
export function StudioVerticalResizeHandles({
  label,
  minHeight,
  maxHeight,
  onResizeStart,
  onResize,
  onResizeEnd,
  shouldResize,
  onKeyboardResize
}: StudioVerticalResizeHandlesProps) {
  const handleKeyDown = (event: KeyboardEvent<HTMLSpanElement>) => {
    const direction = event.key === 'ArrowUp'
      ? 'expand'
      : event.key === 'ArrowDown'
        ? 'shrink'
        : null
    if (!direction) return
    event.preventDefault()
    event.stopPropagation()
    onKeyboardResize(direction, event.shiftKey ? 48 : 24)
  }

  return (['top-left', 'top-right'] as const).map((position) => (
    <NodeResizeControl
      key={position}
      position={position}
      resizeDirection="vertical"
      minHeight={minHeight}
      maxHeight={maxHeight}
      className={`studio-vertical-resize-control studio-vertical-resize-control--${position} nodrag nopan`}
      shouldResize={shouldResize}
      onResizeStart={onResizeStart}
      onResize={onResize}
      onResizeEnd={onResizeEnd}
    >
      <span
        role="separator"
        aria-label={`${label}，方向上键扩大，方向下键缩小`}
        aria-orientation="horizontal"
        tabIndex={0}
        onKeyDown={handleKeyDown}
      >
        <i aria-hidden />
      </span>
    </NodeResizeControl>
  ))
}
