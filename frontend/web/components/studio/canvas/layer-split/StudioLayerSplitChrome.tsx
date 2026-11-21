'use client'

import { forwardRef, memo, useCallback, useImperativeHandle, useRef, useState } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import {
  maskLayoutsEqual,
  toMaskSurfaceLayout,
  type StudioMaskSurfaceLayout
} from '@/utils/studio/studioImageMaskOverlayLayout'
import { placeStudioImageMaskTopToolbar } from '@/utils/studio/studioImageMaskToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { StudioLayerSplitDock } from './StudioLayerSplitDock'
import { StudioCanvasLayerSplitDock } from './StudioCanvasLayerSplitDock'
import { StudioLayerSplitOverlay } from './StudioLayerSplitOverlay'
import { StudioLayerSplitTopToolbar } from './StudioLayerSplitTopToolbar'
import '@/components/studio/composer/studio-image-ai-tools.css'

export interface StudioLayerSplitChromeHandle {
  reposition: (
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect,
    previewRect?: StudioAgentDockRect | null
  ) => void
}

export const StudioLayerSplitChrome = memo(forwardRef<StudioLayerSplitChromeHandle>(
  function StudioLayerSplitChrome(_, ref) {
    const bottomRef = useRef<HTMLDivElement>(null)
    const topRef = useRef<HTMLDivElement>(null)
    const surfaceRef = useRef<HTMLDivElement>(null)
    const [maskLayout, setMaskLayout] = useState<StudioMaskSurfaceLayout | null>(null)
    const canvasRuntime = useCanvasProjectRuntime()
    const session = useStudioUiStore((state) => state.layerSplitSession)
    const node = useStudioUiStore((state) => (
      state.layerSplitSession
        ? state.nodes.find((item) => item.id === state.layerSplitSession?.nodeId)
        : undefined
    ))

    const applyPosition = useCallback((
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect,
      previewRect: StudioAgentDockRect | null = null
    ) => {
      // The rendered image is the visual owner of all three split layers.
      // React Flow's node box may be stale while media aspect ratio settles.
      const anchorRect = previewRect ?? selectionRect
      const bottom = bottomRef.current
      if (bottom) {
        const measured = bottom.getBoundingClientRect()
        const position = placeStudioImageEditToolbar(
          anchorRect,
          viewportRect,
          { width: Math.max(measured.width, 320), height: Math.max(measured.height, 120) }
        )
        bottom.style.left = `${position.left}px`
        bottom.style.top = `${position.top}px`
      }
      const top = topRef.current
      if (top) {
        const measured = top.getBoundingClientRect()
        const position = placeStudioImageMaskTopToolbar(
          anchorRect,
          viewportRect,
          { width: Math.max(measured.width, 160), height: Math.max(measured.height, 44) }
        )
        top.style.left = `${position.left}px`
        top.style.top = `${position.top}px`
      }
      const surface = surfaceRef.current
      if (surface && previewRect) {
        surface.style.left = `${previewRect.left}px`
        surface.style.top = `${previewRect.top}px`
        surface.style.width = `${previewRect.width}px`
        surface.style.height = `${previewRect.height}px`
        surface.style.visibility = 'visible'
        const nextLayout = toMaskSurfaceLayout(previewRect)
        setMaskLayout((current) => (maskLayoutsEqual(current, nextLayout) ? current : nextLayout))
      } else if (surface) {
        surface.style.visibility = 'hidden'
      }
    }, [])

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect, previewRect = null) {
        applyPosition(selectionRect, viewportRect, previewRect)
      }
    }), [applyPosition])

    if (!session || !node) return null

    return (
      <>
        <div
          ref={surfaceRef}
          className="studio-image-mask-surface nodrag nopan nowheel"
          style={{ visibility: 'hidden', position: 'absolute', pointerEvents: 'auto' }}
        >
          {maskLayout ? <StudioLayerSplitOverlay layout={maskLayout} /> : null}
        </div>
        <div
          ref={topRef}
          className="studio-image-edit-dock studio-image-edit-dock--top nodrag nowheel"
          role="toolbar"
          aria-label="图层分离·顶部"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          <StudioLayerSplitTopToolbar projectMode={Boolean(canvasRuntime)} />
        </div>
        <div
          ref={bottomRef}
          className="studio-image-edit-dock studio-image-edit-dock--bottom nodrag nowheel"
          role="dialog"
          aria-label="图层分离·底部"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          {canvasRuntime ? <StudioCanvasLayerSplitDock node={node} /> : <StudioLayerSplitDock node={node} />}
        </div>
      </>
    )
  }
))
