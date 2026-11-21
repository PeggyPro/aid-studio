'use client'

import { useCallback, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { detectCanvasVideoSubject } from '@/hooks/studio/studioVideoSubjectApi'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import type { StudioTouchEditRegion } from '@/types/studio'
import { studioId } from '@/utils/studio/studioGraph'
import {
  brushPathToRect,
  clientPointToNormalized,
  clampRectToImage,
  isBrushPathMeaningful,
  normalizeRect,
  pointToDefaultRect
} from '@/utils/studio/touchEditGeometry'
import { pushTouchEditHistory } from '@/utils/studio/touchEditSessionModel'

function rectStyle(rect: { x: number; y: number; w: number; h: number }) {
  return {
    left: `${rect.x * 100}%`,
    top: `${rect.y * 100}%`,
    width: `${rect.w * 100}%`,
    height: `${rect.h * 100}%`
  }
}

function RegionBox({
  region,
  isDraft,
  onPointerDownMove
}: {
  region: StudioTouchEditRegion
  isDraft?: boolean
  onPointerDownMove?: (event: ReactPointerEvent<HTMLDivElement>) => void
}) {
  return (
    <>
      {region.fromRect ? (
        <>
          <div className="studio-touch-edit-overlay__box is-ghost" style={rectStyle(region.fromRect)} />
          <svg className="studio-touch-edit-overlay__link" viewBox="0 0 1 1" preserveAspectRatio="none">
            <line
              x1={region.fromRect.x}
              y1={region.fromRect.y}
              x2={region.rect.x}
              y2={region.rect.y}
              stroke="rgba(255,255,255,0.75)"
              strokeWidth={0.006}
              strokeDasharray="0.02 0.015"
            />
            <line
              x1={region.fromRect.x + region.fromRect.w}
              y1={region.fromRect.y + region.fromRect.h}
              x2={region.rect.x + region.rect.w}
              y2={region.rect.y + region.rect.h}
              stroke="rgba(255,255,255,0.75)"
              strokeWidth={0.006}
              strokeDasharray="0.02 0.015"
            />
          </svg>
        </>
      ) : null}
      <div
        className={`studio-touch-edit-overlay__box${isDraft ? ' is-draft' : ''}`}
        style={rectStyle(region.rect)}
        onPointerDown={onPointerDownMove}
      >
        <span className="studio-touch-edit-overlay__handle studio-touch-edit-overlay__handle--tl" />
        <span className="studio-touch-edit-overlay__handle studio-touch-edit-overlay__handle--tr" />
        <span className="studio-touch-edit-overlay__handle studio-touch-edit-overlay__handle--bl" />
        <span className="studio-touch-edit-overlay__handle studio-touch-edit-overlay__handle--br" />
      </div>
      {region.brushPath && region.brushPath.length > 1 ? (
        <svg className="studio-touch-edit-overlay__link" viewBox="0 0 1 1" preserveAspectRatio="none">
          <polyline
            className="studio-touch-edit-overlay__brush"
            points={region.brushPath.map((p) => `${p.x},${p.y}`).join(' ')}
            strokeWidth={Math.max(0.008, region.brushSize ?? 0.04)}
          />
        </svg>
      ) : null}
    </>
  )
}

export function StudioTouchEditOverlay({
  nodeId,
  imageUrl
}: {
  nodeId: string
  imageUrl: string
}) {
  const rootRef = useRef<HTMLDivElement>(null)
  const session = useStudioUiStore((state) => state.touchEditSession)
  const runtime = useCanvasProjectRuntime()
  const patchTouchEditSession = useStudioUiStore((state) => state.patchTouchEditSession)
  const [strokePath, setStrokePath] = useState<{ x: number; y: number }[] | null>(null)
  const dragRef = useRef<{
    mode: 'marquee' | 'brush' | 'move'
    start: { x: number; y: number }
    originRect?: { x: number; y: number; w: number; h: number }
  } | null>(null)

  const toNorm = useCallback((clientX: number, clientY: number) => {
    const el = rootRef.current
    if (!el) return { x: 0, y: 0 }
    return clientPointToNormalized(clientX, clientY, el.getBoundingClientRect())
  }, [])

  const finishDraft = useCallback((region: StudioTouchEditRegion) => {
    const current = useStudioUiStore.getState().touchEditSession
    if (!current || current.nodeId !== nodeId) return
    const next = pushTouchEditHistory(current)
    patchTouchEditSession({
      ...next,
      draft: region,
      menuPhase: 'action',
      activeRegionId: region.id,
      draftInstruction: ''
    })
  }, [nodeId, patchTouchEditSession])

  const onPointerDown = async (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!session || session.nodeId !== nodeId) return
    if (session.menuPhase === 'modifyInput' || session.menuPhase === 'moveConfirm') return
    if (event.button !== 0) return
    const point = toNorm(event.clientX, event.clientY)
    event.currentTarget.setPointerCapture(event.pointerId)

    if (session.draft?.kind === 'move' && session.draft.fromRect && session.menuPhase === 'action') {
      dragRef.current = {
        mode: 'move',
        start: point,
        originRect: { ...session.draft.rect }
      }
      return
    }

    if (session.tool === 'point') {
      const rect = clampRectToImage(pointToDefaultRect(point.x, point.y))
      const id = studioId('touch')
      const base: StudioTouchEditRegion = {
        id,
        source: 'point',
        kind: 'modify',
        rect,
        instruction: '',
        detectLabel: '识别中…'
      }
      finishDraft(base)
      try {
        if (!runtime?.project) throw new Error('当前画布未连接')
        const source = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
        if (!source || source.data.mediaUrl !== imageUrl) return
        const resource = await runtime.resolveResourceReference(studioNodeToComposerRef(source))
        const candidates = await detectCanvasVideoSubject(
          String(runtime.project.workspace.workspaceId), String(resource.resourceId)
        )
        const matches = candidates.filter((candidate) =>
          point.x >= candidate.rect.x && point.x <= candidate.rect.x + candidate.rect.w
          && point.y >= candidate.rect.y && point.y <= candidate.rect.y + candidate.rect.h
        )
        const match = matches.sort((a, b) => a.rect.w * a.rect.h - b.rect.w * b.rect.h)[0]
        const latest = useStudioUiStore.getState().touchEditSession
        const currentSource = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
        if (latest?.nodeId === nodeId && latest.draft?.id === id && currentSource?.data.mediaUrl === imageUrl) {
          patchTouchEditSession({
            draft: { ...latest.draft,
              ...(match ? { rect: clampRectToImage(match.rect) } : {}),
              detectLabel: match?.label || '未识别到对象，请框选'
            }
          })
        }
      } catch {
        const latest = useStudioUiStore.getState().touchEditSession
        if (latest?.draft?.id === id) {
          patchTouchEditSession({
            draft: { ...latest.draft, detectLabel: '识别不可用，请框选' }
          })
        }
      }
      return
    }

    if (session.tool === 'marquee') {
      dragRef.current = { mode: 'marquee', start: point }
      return
    }

    if (session.tool === 'brush') {
      dragRef.current = { mode: 'brush', start: point }
      setStrokePath([point])
    }
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (!drag || !session) return
    const point = toNorm(event.clientX, event.clientY)
    if (drag.mode === 'marquee') {
      const rect = clampRectToImage(normalizeRect(drag.start, point))
      patchTouchEditSession({
        draft: {
          id: session.draft?.id || studioId('touch'),
          source: 'marquee',
          kind: 'modify',
          rect,
          instruction: ''
        },
        menuPhase: 'action'
      })
      return
    }
    if (drag.mode === 'brush') {
      setStrokePath((prev) => (prev ? [...prev, point] : [point]))
      return
    }
    if (drag.mode === 'move' && drag.originRect && session.draft) {
      const dx = point.x - drag.start.x
      const dy = point.y - drag.start.y
      const nextRect = clampRectToImage({
        ...drag.originRect,
        x: drag.originRect.x + dx,
        y: drag.originRect.y + dy
      })
      patchTouchEditSession({
        draft: { ...session.draft, rect: nextRect, kind: 'move' }
      })
    }
  }

  const onPointerUp = async (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    dragRef.current = null
    if (!drag || !session) return
    event.currentTarget.releasePointerCapture(event.pointerId)

    if (drag.mode === 'marquee') {
      const draft = useStudioUiStore.getState().touchEditSession?.draft
      if (!draft || draft.rect.w < 0.01 || draft.rect.h < 0.01) {
        patchTouchEditSession({ draft: null })
        return
      }
      finishDraft(draft)
      return
    }

    if (drag.mode === 'brush') {
      const path = strokePath ?? []
      setStrokePath(null)
      if (!isBrushPathMeaningful(path)) {
        message.warning('请绘制更明显的区域')
        return
      }
      const rect = brushPathToRect(path, session.brushSize)
      if (!rect) {
        message.warning('请绘制更明显的区域')
        return
      }
      finishDraft({
        id: studioId('touch'),
        source: 'brush',
        kind: 'modify',
        rect,
        brushPath: path,
        brushSize: session.brushSize,
        instruction: ''
      })
      return
    }

    if (drag.mode === 'move') {
      patchTouchEditSession({ menuPhase: 'moveConfirm', draftInstruction: '' })
    }
  }

  if (!session || session.nodeId !== nodeId) return null

  const showHint = !session.draft && session.edits.length === 0 && !strokePath

  return (
    <div
      ref={rootRef}
      className="studio-touch-edit-overlay nodrag nopan nowheel"
      onPointerDown={(event) => void onPointerDown(event)}
      onPointerMove={onPointerMove}
      onPointerUp={(event) => void onPointerUp(event)}
    >
      {showHint ? <div className="studio-touch-edit-overlay__hint">标记你想要修改的对象</div> : null}
      {session.edits.map((item) => (
        <RegionBox key={item.id} region={item} />
      ))}
      {session.draft ? (
        <RegionBox
          region={session.draft}
          isDraft={Boolean(session.draft.fromRect) && session.menuPhase === 'action'}
          onPointerDownMove={
            session.draft.fromRect && session.menuPhase === 'action'
              ? (event) => {
                  event.stopPropagation()
                  const point = toNorm(event.clientX, event.clientY)
                  event.currentTarget.setPointerCapture(event.pointerId)
                  dragRef.current = {
                    mode: 'move',
                    start: point,
                    originRect: { ...session.draft!.rect }
                  }
                }
              : undefined
          }
        />
      ) : null}
      {strokePath && strokePath.length > 1 ? (
        <svg className="studio-touch-edit-overlay__link" viewBox="0 0 1 1" preserveAspectRatio="none">
          <polyline
            className="studio-touch-edit-overlay__brush"
            points={strokePath.map((p) => `${p.x},${p.y}`).join(' ')}
            strokeWidth={Math.max(0.008, session.brushSize)}
          />
        </svg>
      ) : null}
    </div>
  )
}
