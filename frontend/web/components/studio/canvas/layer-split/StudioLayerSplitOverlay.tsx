'use client'
import { createRandomUuid } from '@/utils/clientId'

import { useCallback, useRef, useState } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioMaskSurfaceLayout } from '@/utils/studio/studioImageMaskOverlayLayout'
import {
  addLayerSplitRegion,
  isLayerSplitRectMeaningful,
  normalizeLayerSplitRect,
  pushLayerSplitHistory,
  resizeLayerSplitRectByHandle,
  STUDIO_LAYER_SPLIT_HANDLES,
  type StudioLayerSplitHandle
} from '@/utils/studio/studioLayerSplit'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioLayerSplitOverlay({ layout }: { layout: StudioMaskSurfaceLayout }) {
  const surfaceRef = useRef<HTMLDivElement>(null)
  const session = useStudioUiStore((state) => state.layerSplitSession)
  const patchLayerSplitSession = useStudioUiStore((state) => state.patchLayerSplitSession)
  const dragStart = useRef<{ x: number; y: number } | null>(null)
  const resizeRef = useRef<{
    regionId: string
    handle: StudioLayerSplitHandle
    origin: { x: number; y: number; w: number; h: number }
  } | null>(null)
  const [draft, setDraft] = useState<{ x: number; y: number; w: number; h: number } | null>(null)
  const [resizeDraft, setResizeDraft] = useState<{
    regionId: string
    rect: { x: number; y: number; w: number; h: number }
  } | null>(null)

  const toNorm = useCallback((clientX: number, clientY: number) => {
    const bounds = surfaceRef.current?.getBoundingClientRect() ?? layout
    const x = (clientX - bounds.left) / Math.max(bounds.width, 1)
    const y = (clientY - bounds.top) / Math.max(bounds.height, 1)
    return {
      x: Math.max(0, Math.min(1, x)),
      y: Math.max(0, Math.min(1, y))
    }
  }, [layout])

  if (!session) return null

  return (
    <div
      ref={surfaceRef}
      className="studio-layer-split-surface pointer-events-auto absolute inset-0 cursor-crosshair nodrag nopan nowheel"
      onPointerDown={(event) => {
        if (event.button !== 0 || resizeRef.current) return
        if ((event.target as HTMLElement).closest('.studio-layer-split-rect__handle')) return
        event.preventDefault()
        event.stopPropagation()
        const start = toNorm(event.clientX, event.clientY)
        dragStart.current = start
        setDraft({ x: start.x, y: start.y, w: 0, h: 0 })
        ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
      }}
      onPointerMove={(event) => {
        if (resizeRef.current) {
          const pointer = toNorm(event.clientX, event.clientY)
          const { regionId, handle, origin } = resizeRef.current
          setResizeDraft({
            regionId,
            rect: resizeLayerSplitRectByHandle(origin, handle, pointer)
          })
          return
        }
        if (!dragStart.current) return
        const end = toNorm(event.clientX, event.clientY)
        setDraft(normalizeLayerSplitRect(dragStart.current, end))
      }}
      onPointerUp={() => {
        if (resizeRef.current) {
          const current = resizeRef.current
          const rect = resizeDraft?.regionId === current.regionId
            ? resizeDraft.rect
            : current.origin
          resizeRef.current = null
          setResizeDraft(null)
          if (!isLayerSplitRectMeaningful(rect)) return
          const latest = useStudioUiStore.getState().layerSplitSession
          if (!latest) return
          patchLayerSplitSession({
            regions: latest.regions.map((region) => (
              region.id === current.regionId ? { ...region, rect } : region
            )),
            activeRegionId: current.regionId
          })
          return
        }
        if (!dragStart.current) return
        const rect = draft
        dragStart.current = null
        setDraft(null)
        const latest = useStudioUiStore.getState().layerSplitSession
        if (!rect || !latest || !isLayerSplitRectMeaningful(rect)) return
        const region = {
          id: createRandomUuid(),
          rect,
          prompt: latest.draftPrompt
        }
        let next = pushLayerSplitHistory(latest)
        next = addLayerSplitRegion(next, region)
        patchLayerSplitSession(next)
      }}
      onPointerCancel={() => {
        resizeRef.current = null
        dragStart.current = null
        setDraft(null)
        setResizeDraft(null)
      }}
    >
      {session.regions.length === 0 && !draft ? (
        <div className="studio-layer-split-tip">框选你想要拆分的物体</div>
      ) : null}
      {session.regions.map((region) => {
        const active = region.id === session.activeRegionId
        const live = resizeDraft?.regionId === region.id ? resizeDraft.rect : region.rect
        return (
          <div
            key={region.id}
            className={`studio-layer-split-rect${active ? ' is-active' : ''}`}
            style={{
              left: `${live.x * 100}%`,
              top: `${live.y * 100}%`,
              width: `${live.w * 100}%`,
              height: `${live.h * 100}%`
            }}
            onPointerDown={(event) => {
              if (event.button !== 0) return
              event.stopPropagation()
              patchLayerSplitSession({ activeRegionId: region.id })
            }}
          >
            {active
              ? STUDIO_LAYER_SPLIT_HANDLES.map((handle) => (
                <button
                  key={handle}
                  type="button"
                  aria-label={`缩放选区-${handle}`}
                  className={`studio-layer-split-rect__handle is-${handle}`}
                  onPointerDown={(event) => {
                    event.preventDefault()
                    event.stopPropagation()
                    const latest = useStudioUiStore.getState().layerSplitSession
                    if (!latest) return
                    patchLayerSplitSession(pushLayerSplitHistory({
                      ...latest,
                      activeRegionId: region.id
                    }))
                    resizeRef.current = {
                      regionId: region.id,
                      handle,
                      origin: { ...region.rect }
                    }
                    setResizeDraft({ regionId: region.id, rect: { ...region.rect } })
                    const root = (event.currentTarget as HTMLElement).closest('.studio-layer-split-surface') as HTMLElement | null
                    root?.setPointerCapture(event.pointerId)
                  }}
                />
              ))
              : null}
          </div>
        )
      })}
      {draft ? (
        <div
          className="studio-layer-split-rect"
          style={{
            left: `${draft.x * 100}%`,
            top: `${draft.y * 100}%`,
            width: `${draft.w * 100}%`,
            height: `${draft.h * 100}%`,
            borderStyle: 'dashed',
            borderColor: 'rgba(125, 211, 252, 0.95)'
          }}
        />
      ) : null}
    </div>
  )
}
