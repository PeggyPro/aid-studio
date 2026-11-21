'use client'

import { forwardRef, memo, useCallback, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  placeStudioImageEditToolbar
} from '@/utils/studio/studioImageEditToolbarLayout'
import {
  maskLayoutsEqual,
  toMaskSurfaceLayout,
  type StudioMaskSurfaceLayout
} from '@/utils/studio/studioImageMaskOverlayLayout'
import { placeStudioImageMaskTopToolbar } from '@/utils/studio/studioImageMaskToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { StudioImageCropToolbar } from './StudioImageCropOverlay'
import { StudioImageExpandToolbar } from './StudioImageExpandOverlay'
import { StudioImageGridBottomToolbar } from './StudioImageGridChrome'
import { StudioImageGridOverlay } from './StudioImageGridOverlay'
import { StudioImageMaskBottomToolbar, StudioImageMaskTopToolbar } from './StudioImageMaskChrome'
import { StudioImageMaskOverlay } from './StudioImageMaskOverlay'
import '@/components/studio/composer/studio-image-ai-tools.css'

export interface StudioImageEditDockHandle {
  reposition: (
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect,
    previewRect?: StudioAgentDockRect | null
  ) => void
}

export const StudioImageEditDock = memo(forwardRef<StudioImageEditDockHandle>(
  function StudioImageEditDock(_, ref) {
    const bottomRef = useRef<HTMLDivElement>(null)
    const topRef = useRef<HTMLDivElement>(null)
    const maskSurfaceRef = useRef<HTMLDivElement>(null)
    const gridSurfaceRef = useRef<HTMLDivElement>(null)
    const lastRectsRef = useRef<{
      selection: StudioAgentDockRect
      viewport: StudioAgentDockRect
      preview: StudioAgentDockRect | null
    } | null>(null)
    const [maskLayout, setMaskLayout] = useState<StudioMaskSurfaceLayout | null>(null)
    const [gridLayout, setGridLayout] = useState<StudioMaskSurfaceLayout | null>(null)
    const session = useStudioUiStore((state) => state.imageEditSession)
    const node = useStudioUiStore((state) => (
      state.imageEditSession
        ? state.nodes.find((item) => item.id === state.imageEditSession?.nodeId)
        : undefined
    ))
    const isMaskMode = session?.mode === 'erase' || session?.mode === 'repaint'
    const isGridMode = session?.mode === 'grid'

    const applyPosition = useCallback((
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect,
      previewRect: StudioAgentDockRect | null = null
    ) => {
      lastRectsRef.current = { selection: selectionRect, viewport: viewportRect, preview: previewRect }
      const bottom = bottomRef.current
      if (bottom) {
        const measured = bottom.getBoundingClientRect()
        const position = placeStudioImageEditToolbar(
          selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 160), height: Math.max(measured.height, 40) }
        )
        bottom.style.left = `${position.left}px`
        bottom.style.top = `${position.top}px`
      }
      const top = topRef.current
      if (top && isMaskMode) {
        const measured = top.getBoundingClientRect()
        const position = placeStudioImageMaskTopToolbar(
          selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 160), height: Math.max(measured.height, 40) }
        )
        top.style.left = `${position.left}px`
        top.style.top = `${position.top}px`
      }
      const maskSurface = maskSurfaceRef.current
      if (maskSurface && isMaskMode && previewRect) {
        maskSurface.style.left = `${previewRect.left}px`
        maskSurface.style.top = `${previewRect.top}px`
        maskSurface.style.width = `${previewRect.width}px`
        maskSurface.style.height = `${previewRect.height}px`
        maskSurface.style.visibility = 'visible'
        const nextLayout = toMaskSurfaceLayout(previewRect)
        setMaskLayout((current) => (maskLayoutsEqual(current, nextLayout) ? current : nextLayout))
      } else if (maskSurface && isMaskMode) {
        maskSurface.style.visibility = 'hidden'
      }
      const gridSurface = gridSurfaceRef.current
      if (gridSurface && isGridMode && previewRect) {
        gridSurface.style.left = `${previewRect.left}px`
        gridSurface.style.top = `${previewRect.top}px`
        gridSurface.style.width = `${previewRect.width}px`
        gridSurface.style.height = `${previewRect.height}px`
        gridSurface.style.visibility = 'visible'
        const nextLayout = toMaskSurfaceLayout(previewRect)
        setGridLayout((current) => (maskLayoutsEqual(current, nextLayout) ? current : nextLayout))
      } else if (gridSurface && isGridMode) {
        gridSurface.style.visibility = 'hidden'
      }
    }, [isGridMode, isMaskMode])

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect, previewRect = null) {
        applyPosition(selectionRect, viewportRect, previewRect)
      }
    }), [applyPosition])

    useLayoutEffect(() => {
      if (typeof ResizeObserver === 'undefined') return
      const observer = new ResizeObserver(() => {
        const last = lastRectsRef.current
        if (last) applyPosition(last.selection, last.viewport, last.preview)
      })
      if (bottomRef.current) observer.observe(bottomRef.current)
      if (topRef.current) observer.observe(topRef.current)
      return () => observer.disconnect()
    }, [applyPosition, node?.id, session?.mode])

    if (!session || !node) return null

    const ariaLabel = session.mode === 'crop'
      ? '裁剪操作栏'
      : session.mode === 'expand'
        ? '扩图操作栏'
        : session.mode === 'grid'
          ? '宫格切分操作栏'
          : session.mode === 'erase'
            ? '擦除操作栏'
            : '重绘操作栏'

    return (
      <>
        {isGridMode ? (
          <div
            ref={gridSurfaceRef}
            className="studio-image-grid-surface nodrag nopan nowheel"
            style={{ visibility: 'hidden', position: 'absolute', pointerEvents: 'auto' }}
          >
            <StudioImageGridOverlay layout={gridLayout} />
          </div>
        ) : null}
        {isMaskMode ? (
          <div
            ref={maskSurfaceRef}
            className={`studio-image-mask-surface nodrag nopan nowheel studio-image-mask-surface--${session.mode}`}
            style={{ visibility: 'hidden' }}
          >
            <StudioImageMaskOverlay nodeId={node.id} layout={maskLayout} />
          </div>
        ) : null}
        {isMaskMode ? (
          <div
            ref={topRef}
            className={`studio-image-edit-dock studio-image-edit-dock--top studio-image-edit-dock--${session.mode} nodrag nowheel`}
            role="toolbar"
            aria-label={`${ariaLabel}·顶部`}
            style={{ left: -9999, top: 0 }}
            onPointerDown={(event) => event.stopPropagation()}
            onWheel={(event) => event.stopPropagation()}
          >
            <StudioImageMaskTopToolbar nodeId={node.id} />
          </div>
        ) : null}
        <div
          ref={bottomRef}
          className={`studio-image-edit-dock studio-image-edit-dock--bottom studio-image-edit-dock--${session.mode} nodrag nowheel`}
          role="toolbar"
          aria-label={ariaLabel}
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          {session.mode === 'crop' ? (
            <StudioImageCropToolbar nodeId={node.id} />
          ) : session.mode === 'expand' ? (
            <StudioImageExpandToolbar nodeId={node.id} />
          ) : session.mode === 'grid' ? (
            <StudioImageGridBottomToolbar nodeId={node.id} />
          ) : (
            <StudioImageMaskBottomToolbar nodeId={node.id} />
          )}
        </div>
      </>
    )
  }
))
