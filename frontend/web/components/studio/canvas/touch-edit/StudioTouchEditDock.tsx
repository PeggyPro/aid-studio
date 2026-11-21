'use client'

import { forwardRef, memo, useCallback, useImperativeHandle, useRef } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import { placeStudioImageMaskTopToolbar } from '@/utils/studio/studioImageMaskToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { StudioTouchEditContentDock } from './StudioTouchEditContentDock'
import { StudioTouchEditOverlay } from './StudioTouchEditOverlay'
import { StudioTouchEditRegionMenu } from './StudioTouchEditRegionMenu'
import { StudioTouchEditTopToolbar } from './StudioTouchEditTopToolbar'
import './studio-touch-edit.css'

export interface StudioTouchEditDockHandle {
  reposition: (
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect,
    previewRect?: StudioAgentDockRect | null
  ) => void
}

export const StudioTouchEditDock = memo(forwardRef<StudioTouchEditDockHandle>(
  function StudioTouchEditDock(_, ref) {
    const topRef = useRef<HTMLDivElement>(null)
    const bottomRef = useRef<HTMLDivElement>(null)
    const menuRef = useRef<HTMLDivElement>(null)
    const surfaceRef = useRef<HTMLDivElement>(null)
    const session = useStudioUiStore((state) => state.touchEditSession)
    const node = useStudioUiStore((state) => (
      state.touchEditSession
        ? state.nodes.find((item) => item.id === state.touchEditSession?.nodeId)
        : undefined
    ))

    const applyPosition = useCallback((
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect,
      previewRect: StudioAgentDockRect | null = null
    ) => {
      const top = topRef.current
      if (top) {
        const measured = top.getBoundingClientRect()
        const position = placeStudioImageMaskTopToolbar(
          previewRect ?? selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 200), height: Math.max(measured.height, 36) }
        )
        top.style.left = `${position.left}px`
        top.style.top = `${position.top}px`
      }
      const bottom = bottomRef.current
      if (bottom) {
        const measured = bottom.getBoundingClientRect()
        const position = placeStudioImageEditToolbar(
          previewRect ?? selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 280), height: Math.max(measured.height, 80) }
        )
        bottom.style.left = `${position.left}px`
        bottom.style.top = `${position.top}px`
      }
      const surface = surfaceRef.current
      if (surface && previewRect) {
        surface.style.left = `${previewRect.left}px`
        surface.style.top = `${previewRect.top}px`
        surface.style.width = `${previewRect.width}px`
        surface.style.height = `${previewRect.height}px`
        surface.style.visibility = 'visible'
      } else if (surface) {
        surface.style.visibility = 'hidden'
      }
      const menu = menuRef.current
      if (menu && previewRect && session?.draft) {
        const measured = menu.getBoundingClientRect()
        const draft = session.draft
        const anchorLeft = previewRect.left + (draft.rect.x + draft.rect.w) * previewRect.width + 8
        const anchorTop = previewRect.top + draft.rect.y * previewRect.height - 8
        const left = Math.min(
          Math.max(viewportRect.left + 8, anchorLeft),
          viewportRect.right - measured.width - 8
        )
        const topPos = Math.min(
          Math.max(viewportRect.top + 8, anchorTop),
          viewportRect.bottom - measured.height - 8
        )
        menu.style.left = `${left}px`
        menu.style.top = `${topPos}px`
        menu.style.visibility = 'visible'
      } else if (menu) {
        menu.style.visibility = 'hidden'
      }
    }, [session?.draft])

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect, previewRect = null) {
        applyPosition(selectionRect, viewportRect, previewRect)
      }
    }), [applyPosition])

    if (!session || !node?.data.mediaUrl) return null

    return (
      <>
        <div
          ref={surfaceRef}
          className="studio-touch-edit-surface nodrag nopan nowheel"
          style={{ visibility: 'hidden' }}
        >
          <StudioTouchEditOverlay nodeId={node.id} imageUrl={node.data.mediaUrl} />
        </div>
        <div
          ref={topRef}
          className="studio-touch-edit-dock nodrag nowheel"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          <StudioTouchEditTopToolbar nodeId={node.id} />
        </div>
        <div
          ref={menuRef}
          className="studio-touch-edit-dock nodrag nowheel"
          style={{ left: -9999, top: 0, visibility: 'hidden' }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          <StudioTouchEditRegionMenu nodeId={node.id} />
        </div>
        <div
          ref={bottomRef}
          className="studio-touch-edit-dock nodrag nowheel"
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          <StudioTouchEditContentDock nodeId={node.id} />
        </div>
      </>
    )
  }
))
