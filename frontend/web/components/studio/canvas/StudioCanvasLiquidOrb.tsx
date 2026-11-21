'use client'

import { memo, useCallback, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { LiquidOrb } from '@/components/common/liquid-orb/LiquidOrb'
import {
  LIQUID_ORB_DOCK_GAP,
  LIQUID_ORB_DOCK_SIZE,
  clampLiquidOrbDock,
  defaultLiquidOrbDock,
  isLiquidOrbDrag,
  readLiquidOrbDock,
  snapLiquidOrbDock,
  rightLiquidOrbDock,
  writeLiquidOrbDock,
  type LiquidOrbDockAnchor,
  type LiquidOrbDockPoint,
  type LiquidOrbDockViewport
} from '@/components/common/liquid-orb/liquidOrbDock'
import { useStudioFreeTheme } from '@/components/studio/theme/StudioFreeThemeProvider'
import { useStudioUiStore } from '@/stores/studioUi'

export const StudioCanvasLiquidOrb = memo(function StudioCanvasLiquidOrb({ scopeKey, panelPresent = false }: { scopeKey: string; panelPresent?: boolean }) {
  const collapsed = useStudioUiStore((state) => state.rightCollapsed)
  const visible = collapsed && !panelPresent
  const setCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
  const busy = useStudioUiStore((state) =>
    state.messages.some((item) => item.status === 'thinking' || item.status === 'generating'))
  const canvasEmpty = useStudioUiStore((state) => state.nodes.length === 0)
  const { theme } = useStudioFreeTheme()
  const hostRef = useRef<HTMLButtonElement>(null)
  const skipClickRef = useRef(false)
  const lastVisibleEmptyRef = useRef(canvasEmpty)
  const visiblePositionInitializedRef = useRef(false)
  const autoPositionRef = useRef(true)
  const dragRef = useRef<{
    pointerId: number
    originX: number
    originY: number
    startX: number
    startY: number
    dragging: boolean
    scaleX: number
    scaleY: number
    lastPoint: LiquidOrbDockPoint
  } | null>(null)
  const [point, setPoint] = useState<LiquidOrbDockPoint | null>(null)
  const [dragging, setDragging] = useState(false)

  const readWorkspace = useCallback(() => {
    const workspace = hostRef.current?.closest('.studio-workspace')
    return workspace instanceof HTMLElement ? workspace : null
  }, [])

  const readViewport = useCallback((): LiquidOrbDockViewport => {
    const workspace = readWorkspace()
    if (workspace) return { width: workspace.clientWidth, height: workspace.clientHeight }
    return {
      width: typeof window === 'undefined' ? 1280 : window.innerWidth,
      height: typeof window === 'undefined' ? 720 : window.innerHeight
    }
  }, [readWorkspace])

  const readEmptyGuideAnchor = useCallback((): LiquidOrbDockAnchor | null => {
    const workspace = readWorkspace()
    const guide = workspace?.querySelector('.studio-canvas-empty-guide.is-free .studio-canvas-empty-guide__hint.is-click')
    if (!workspace || !(guide instanceof HTMLElement)) return null
    const workspaceRect = workspace.getBoundingClientRect()
    const guideRect = guide.getBoundingClientRect()
    if (!workspaceRect.width || !workspaceRect.height || !guideRect.width || !guideRect.height) return null
    const scaleX = workspace.clientWidth / workspaceRect.width
    const scaleY = workspace.clientHeight / workspaceRect.height
    return {
      left: (guideRect.left - workspaceRect.left) * scaleX,
      top: (guideRect.top - workspaceRect.top) * scaleY,
      width: guideRect.width * scaleX,
      height: guideRect.height * scaleY
    }
  }, [readWorkspace])

  const resolveAutoPoint = useCallback((viewport = readViewport()) => canvasEmpty
    ? defaultLiquidOrbDock(LIQUID_ORB_DOCK_SIZE, viewport, LIQUID_ORB_DOCK_GAP, readEmptyGuideAnchor())
    : rightLiquidOrbDock(LIQUID_ORB_DOCK_SIZE, viewport, LIQUID_ORB_DOCK_GAP),
  [canvasEmpty, readEmptyGuideAnchor, readViewport])

  const resolveInitialPoint = useCallback((viewport = readViewport()) => clampLiquidOrbDock(
    (canvasEmpty ? null : readLiquidOrbDock(scopeKey)) ?? resolveAutoPoint(viewport),
    LIQUID_ORB_DOCK_SIZE,
    viewport,
    LIQUID_ORB_DOCK_GAP
  ), [canvasEmpty, readViewport, resolveAutoPoint, scopeKey])

  const commitPoint = useCallback((next: LiquidOrbDockPoint) => {
    const snapped = snapLiquidOrbDock(next, LIQUID_ORB_DOCK_SIZE, readViewport(), LIQUID_ORB_DOCK_GAP)
    autoPositionRef.current = false
    setPoint(snapped)
    writeLiquidOrbDock(scopeKey, snapped)
  }, [readViewport, scopeKey])

  useLayoutEffect(() => {
    dragRef.current = null
    skipClickRef.current = false
    setDragging(false)
    if (!visible) return
    const viewport = readViewport()
    const stateChanged = lastVisibleEmptyRef.current !== canvasEmpty
    if (!visiblePositionInitializedRef.current && !stateChanged && !canvasEmpty && readLiquidOrbDock(scopeKey)) {
      autoPositionRef.current = false
    }
    visiblePositionInitializedRef.current = true
    lastVisibleEmptyRef.current = canvasEmpty
    if (stateChanged) autoPositionRef.current = true
    setPoint((current) => {
      const next = clampLiquidOrbDock(
        (stateChanged || autoPositionRef.current) ? resolveAutoPoint(viewport) : current ?? resolveInitialPoint(viewport),
        LIQUID_ORB_DOCK_SIZE,
        viewport,
        LIQUID_ORB_DOCK_GAP
      )
      writeLiquidOrbDock(scopeKey, next)
      return current?.x === next.x && current.y === next.y ? current : next
    })
  }, [canvasEmpty, visible, readViewport, resolveAutoPoint, resolveInitialPoint, scopeKey])

  useLayoutEffect(() => {
    if (!visible) return
    const workspace = readWorkspace()
    if (!workspace || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      const viewport = { width: workspace.clientWidth, height: workspace.clientHeight }
      setPoint((current) => {
        const next = clampLiquidOrbDock(
          autoPositionRef.current ? resolveAutoPoint(viewport) : current ?? resolveInitialPoint(viewport),
          LIQUID_ORB_DOCK_SIZE,
          viewport,
          LIQUID_ORB_DOCK_GAP
        )
        writeLiquidOrbDock(scopeKey, next)
        return current?.x === next.x && current.y === next.y ? current : next
      })
    })
    observer.observe(workspace)
    // Docked panels resize the canvas while the outer workspace stays unchanged.
    const canvas = workspace.querySelector('.studio-canvas')
    if (canvas instanceof HTMLElement) observer.observe(canvas)
    return () => observer.disconnect()
  }, [visible, readWorkspace, resolveAutoPoint, resolveInitialPoint, scopeKey])

  const handlePointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0 || !event.isPrimary || dragRef.current) return
    event.stopPropagation()
    const origin = point ?? resolveInitialPoint()
    const workspace = hostRef.current?.closest('.studio-workspace')
    const rect = workspace?.getBoundingClientRect()
    skipClickRef.current = false
    dragRef.current = {
      pointerId: event.pointerId,
      originX: event.clientX,
      originY: event.clientY,
      startX: origin.x,
      startY: origin.y,
      dragging: false,
      scaleX: workspace instanceof HTMLElement && rect?.width ? workspace.clientWidth / rect.width : 1,
      scaleY: workspace instanceof HTMLElement && rect?.height ? workspace.clientHeight / rect.height : 1,
      lastPoint: origin
    }
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch {
      // 浏览器拒绝捕获时仍保留点击打开能力。
    }
  }

  const handlePointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    const dx = event.clientX - drag.originX
    const dy = event.clientY - drag.originY
    if (!drag.dragging && !isLiquidOrbDrag(dx, dy)) return
    if (!drag.dragging) {
      drag.dragging = true
      skipClickRef.current = true
      setDragging(true)
    }
    event.preventDefault()
    drag.lastPoint = clampLiquidOrbDock(
      { x: drag.startX + dx * drag.scaleX, y: drag.startY + dy * drag.scaleY },
      LIQUID_ORB_DOCK_SIZE,
      readViewport(),
      LIQUID_ORB_DOCK_GAP
    )
    setPoint(drag.lastPoint)
  }

  const finishPointer = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    try {
      event.currentTarget.releasePointerCapture(event.pointerId)
    } catch {
      // 指针可能已经由浏览器释放。
    }
    if (drag.dragging) {
      event.preventDefault()
      event.stopPropagation()
      commitPoint(drag.lastPoint)
    }
    setDragging(false)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!point || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
    event.preventDefault()
    const step = event.shiftKey ? 48 : 16
    const next = clampLiquidOrbDock({
      x: point.x + (event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0),
      y: point.y + (event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0)
    }, LIQUID_ORB_DOCK_SIZE, readViewport(), LIQUID_ORB_DOCK_GAP)
    setPoint(next)
  }

  const finishKeyboardMove = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (point && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) commitPoint(point)
  }

  const parked = !visible

  return (
    <button
      ref={hostRef}
      type="button"
      hidden={parked}
      tabIndex={parked ? -1 : 0}
      aria-hidden={parked || undefined}
      aria-label="打开 AI 助手；可拖动或使用方向键移动"
      className={`studio-canvas-liquid-orb${point ? ' is-ready' : ''}${dragging ? ' is-dragging' : ''}`}
      data-studio-guide="liquid-orb"
      style={{
        width: LIQUID_ORB_DOCK_SIZE,
        height: LIQUID_ORB_DOCK_SIZE,
        ...(point ? { transform: `translate3d(${point.x}px, ${point.y}px, 0)` } : null)
      }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={finishPointer}
      onPointerCancel={finishPointer}
      onLostPointerCapture={finishPointer}
      onKeyDown={handleKeyDown}
      onKeyUp={finishKeyboardMove}
      onClick={(event) => {
        event.stopPropagation()
        if (skipClickRef.current && event.detail !== 0) {
          event.preventDefault()
          skipClickRef.current = false
          return
        }
        setCollapsed(false)
      }}
    >
      <LiquidOrb
        className="studio-canvas-liquid-orb__hit"
        size={LIQUID_ORB_DOCK_SIZE}
        theme={theme === 'opal' ? 'light' : 'dark'}
        ariaHidden
        mode={busy ? 'thinking' : 'listening'}
      />
    </button>
  )
})
