'use client'

import { useCallback, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { useReactFlow } from '@xyflow/react'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  projectStudioGroupCornerResize,
  type StudioGroupResizeCorner
} from '@/utils/studio/studioGroup'

function GroupCornerHandle({
  groupId,
  corner,
  label
}: {
  groupId: string
  corner: StudioGroupResizeCorner
  label: string
}) {
  const { getViewport } = useReactFlow()

  const onPointerDown = useCallback((event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()

    const state = useStudioUiStore.getState()
    const group = state.groups.find((item) => item.id === groupId)
    if (!group) return

    const startClient = { x: event.clientX, y: event.clientY }
    const origin = {
      ...group,
      position: { ...group.position },
      width: group.width,
      height: group.height
    }
    const zoom = Math.max(getViewport().zoom, 0.05)
    const target = event.currentTarget
    target.setPointerCapture(event.pointerId)

    window.dispatchEvent(new CustomEvent('studio:group-resize', {
      detail: {
        active: true,
        groupId,
        frame: { position: origin.position, width: origin.width, height: origin.height }
      }
    }))
    state.beginGraphTransaction()

    const onPointerMove = (moveEvent: PointerEvent) => {
      const delta = {
        x: (moveEvent.clientX - startClient.x) / zoom,
        y: (moveEvent.clientY - startClient.y) / zoom
      }
      const latest = useStudioUiStore.getState()
      const next = projectStudioGroupCornerResize(latest.nodes, origin, corner, delta)
      if (!next) return
      window.dispatchEvent(new CustomEvent('studio:group-resize', {
        detail: { active: true, groupId, frame: next }
      }))
    }

    const finish = (endEvent: PointerEvent) => {
      try {
        target.releasePointerCapture(endEvent.pointerId)
      } catch {
        // capture may already be released
      }
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', finish)

      const delta = {
        x: (endEvent.clientX - startClient.x) / zoom,
        y: (endEvent.clientY - startClient.y) / zoom
      }
      const latest = useStudioUiStore.getState()
      const next = projectStudioGroupCornerResize(latest.nodes, origin, corner, delta)

      window.dispatchEvent(new CustomEvent('studio:group-resize', {
        detail: { active: false, groupId }
      }))
      if (next) latest.resizeGroup(groupId, next)
      else latest.commitGraphTransaction()
    }

    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', finish)
    window.addEventListener('pointercancel', finish)
  }, [corner, getViewport, groupId])

  const onKeyDown = useCallback((event: KeyboardEvent<HTMLButtonElement>) => {
    const step = event.shiftKey ? 48 : 24
    let delta = { x: 0, y: 0 }
    if (event.key === 'ArrowUp') delta = { x: 0, y: -step }
    else if (event.key === 'ArrowDown') delta = { x: 0, y: step }
    else if (event.key === 'ArrowLeft') delta = corner === 'top-left' ? { x: -step, y: 0 } : { x: step, y: 0 }
    else if (event.key === 'ArrowRight') delta = corner === 'top-left' ? { x: step, y: 0 } : { x: -step, y: 0 }
    else return

    event.preventDefault()
    event.stopPropagation()
    const state = useStudioUiStore.getState()
    const group = state.groups.find((item) => item.id === groupId)
    if (!group) return
    const next = projectStudioGroupCornerResize(state.nodes, group, corner, delta)
    if (!next) return
    state.beginGraphTransaction()
    state.resizeGroup(groupId, next)
  }, [corner, groupId])

  return (
    <button
      type="button"
      className={`studio-group-resize-handle studio-group-resize-handle--${corner} nodrag nopan`}
      aria-label={`${label}（${corner === 'top-left' ? '左上' : '右上'}）`}
      onPointerDown={onPointerDown}
      onClick={(event) => {
        event.preventDefault()
        event.stopPropagation()
      }}
      onKeyDown={onKeyDown}
    >
      <i aria-hidden />
    </button>
  )
}

/** 打组框角点缩放：自管 pointer 捕获，不依赖 RF NodeResizeControl（parent 节点上不可靠）。 */
export function StudioGroupResizeHandles({
  groupId,
  label = '调整分组大小'
}: {
  groupId: string
  label?: string
}) {
  return (
    <>
      <GroupCornerHandle groupId={groupId} corner="top-left" label={label} />
      <GroupCornerHandle groupId={groupId} corner="top-right" label={label} />
    </>
  )
}
