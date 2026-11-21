'use client'

import { forwardRef, memo, useCallback, useImperativeHandle, useRef, useState } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import {
  maskLayoutsEqual,
  toMaskSurfaceLayout,
  type StudioMaskSurfaceLayout
} from '@/utils/studio/studioImageMaskOverlayLayout'
import { placeStudioImageMaskTopToolbar } from '@/utils/studio/studioImageMaskToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { StudioLayerComposeDock } from './StudioLayerComposeDock'
import { StudioLayerComposeOverlay } from './StudioLayerComposeOverlay'
import { StudioLayerComposeTopToolbar } from './StudioLayerComposeTopToolbar'
import '@/components/studio/composer/studio-image-ai-tools.css'

export interface StudioLayerComposeChromeHandle {
  reposition: (
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect,
    previewRect?: StudioAgentDockRect | null
  ) => void
}

export const StudioLayerComposeChrome = memo(forwardRef<StudioLayerComposeChromeHandle>(
  function StudioLayerComposeChrome(_, ref) {
    const bottomRef = useRef<HTMLDivElement>(null)
    const topRef = useRef<HTMLDivElement>(null)
    const surfaceRef = useRef<HTMLDivElement>(null)
    const [maskLayout, setMaskLayout] = useState<StudioMaskSurfaceLayout | null>(null)
    const selectedId = useStudioUiStore((state) => state.activeNodeId)
    const node = useStudioUiStore((state) => (
      selectedId ? state.nodes.find((item) => item.id === selectedId) : undefined
    ))
    const sourceNode = useStudioUiStore((state) => {
      const sourceId = node?.data.layerSeparation?.sourceNodeId
      return sourceId ? state.nodes.find((item) => item.id === sourceId) : undefined
    })
    const layerSplitSession = useStudioUiStore((state) => state.layerSplitSession)

    const applyPosition = useCallback((
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect,
      previewRect: StudioAgentDockRect | null = null
    ) => {
      const anchorRect = previewRect ?? selectionRect
      const bottom = bottomRef.current
      if (bottom) {
        const measured = bottom.getBoundingClientRect()
        const position = placeStudioImageEditToolbar(
          anchorRect,
          viewportRect,
          { width: Math.max(measured.width, 320), height: Math.max(measured.height, 52) }
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
          { width: Math.max(measured.width, 220), height: Math.max(measured.height, 44) }
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

    if (
      layerSplitSession
      || !node
      || node.data.imagePurpose !== 'layer_separation'
      || !node.data.layerSeparation
      || !sourceNode
    ) {
      return null
    }

    return (
      <>
        <div
          ref={surfaceRef}
          className="studio-image-mask-surface nodrag nopan nowheel"
          style={{ visibility: 'hidden', position: 'absolute', pointerEvents: 'auto' }}
        >
          {maskLayout ? (
            <StudioLayerComposeOverlay node={node} sourceNode={sourceNode} layout={maskLayout} />
          ) : null}
        </div>
        <div
          ref={topRef}
          className="studio-image-edit-dock studio-image-edit-dock--top nodrag nowheel"
          role="toolbar"
          aria-label="图层编辑·顶部"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <StudioLayerComposeTopToolbar node={node} />
        </div>
        <div
          ref={bottomRef}
          className="studio-image-edit-dock studio-image-edit-dock--bottom nodrag nowheel"
          role="toolbar"
          aria-label="图层合并"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <StudioLayerComposeDock node={node} sourceNode={sourceNode} />
        </div>
      </>
    )
  }
))
