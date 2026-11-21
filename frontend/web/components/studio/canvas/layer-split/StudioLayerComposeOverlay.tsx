'use client'

import { useCallback, useRef, useState } from 'react'
import { useStudioLayerSplitActions } from '@/hooks/studio/useStudioLayerSplitActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import type { StudioMaskSurfaceLayout } from '@/utils/studio/studioImageMaskOverlayLayout'
import {
  clampLayerSplitRect,
  resizeLayerSplitRectByHandle,
  STUDIO_LAYER_SPLIT_HANDLES,
  type StudioLayerSplitHandle
} from '@/utils/studio/studioLayerSplit'
import { patchLayerInSeparation } from '@/utils/studio/studioLayerSeparation'
import '@/components/studio/composer/studio-image-ai-tools.css'

/** 拖出判定：出画布边缘外扩区（像素） */
const EXTRACT_OUT_PX = 56
/** 回收入区需更靠近边缘，避免边界抖动反复切换 */
const EXTRACT_IN_PX = 28
/** 移动阈值（px），避免误触拖出 */
const MOVE_ACTIVATE_PX = 4

export function StudioLayerComposeOverlay({
  node,
  sourceNode,
  layout
}: {
  node: StudioCanvasNode
  sourceNode: StudioCanvasNode
  layout: StudioMaskSurfaceLayout
}) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const { extractLayerToNode } = useStudioLayerSplitActions(sourceNode)
  const state = node.data.layerSeparation
  const drag = useRef<{
    layerId: string
    mode: 'move' | 'extract' | 'resize'
    handle?: StudioLayerSplitHandle
    startX: number
    startY: number
    origin: { x: number; y: number; w: number; h: number }
    activated: boolean
  } | null>(null)
  const [preview, setPreview] = useState<{
    id: string
    rect: { x: number; y: number; w: number; h: number }
    extracting: boolean
  } | null>(null)

  const toNorm = useCallback((clientX: number, clientY: number) => ({
    x: Math.max(0, Math.min(1, (clientX - layout.left) / Math.max(layout.width, 1))),
    y: Math.max(0, Math.min(1, (clientY - layout.top) / Math.max(layout.height, 1)))
  }), [layout.height, layout.left, layout.top, layout.width])

  const isOutside = useCallback((clientX: number, clientY: number, extracting: boolean) => {
    const pad = extracting ? EXTRACT_IN_PX : EXTRACT_OUT_PX
    return (
      clientX < layout.left - pad
      || clientY < layout.top - pad
      || clientX > layout.left + layout.width + pad
      || clientY > layout.top + layout.height + pad
    )
  }, [layout.height, layout.left, layout.top, layout.width])

  if (!state) return null

  return (
    <div
      className="studio-layer-compose-surface pointer-events-auto absolute inset-0 nodrag nopan nowheel"
      onPointerMove={(event) => {
        const current = drag.current
        if (!current) return
        if (current.mode === 'resize' && current.handle) {
          const pointer = toNorm(event.clientX, event.clientY)
          setPreview({
            id: current.layerId,
            rect: resizeLayerSplitRectByHandle(current.origin, current.handle, pointer),
            extracting: false
          })
          return
        }
        const dist = Math.hypot(event.clientX - current.startX, event.clientY - current.startY)
        if (!current.activated && dist < MOVE_ACTIVATE_PX) return
        current.activated = true
        const dx = (event.clientX - current.startX) / Math.max(layout.width, 1)
        const dy = (event.clientY - current.startY) / Math.max(layout.height, 1)
        const extracting = isOutside(event.clientX, event.clientY, current.mode === 'extract')
        current.mode = extracting ? 'extract' : 'move'
        setPreview({
          id: current.layerId,
          rect: clampLayerSplitRect({
            x: current.origin.x + dx,
            y: current.origin.y + dy,
            w: current.origin.w,
            h: current.origin.h
          }),
          extracting
        })
      }}
      onPointerUp={() => {
        const current = drag.current
        const snap = preview
        drag.current = null
        setPreview(null)
        if (!current || !state) return
        if (current.mode === 'extract') {
          void extractLayerToNode(node, current.layerId)
          return
        }
        if (!snap || snap.id !== current.layerId) return
        const layer = state.layers.find((item) => item.id === current.layerId)
        if (!layer) return
        updateNodeData(node.id, {
          layerSeparation: patchLayerInSeparation(state, current.layerId, {
            transform: { ...layer.transform, rect: snap.rect }
          })
        })
      }}
      onPointerCancel={() => {
        drag.current = null
        setPreview(null)
      }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={state.backgroundUrl || node.data.mediaUrl || ''}
        alt=""
        className="pointer-events-none absolute inset-0 h-full w-full object-contain"
        draggable={false}
      />
      {state.layers.map((layer) => {
        const active = layer.id === state.activeLayerId
        const live = preview?.id === layer.id ? preview.rect : layer.transform.rect
        const extracting = preview?.id === layer.id && preview.extracting
        return (
          <div
            key={layer.id}
            className={`studio-layer-compose-layer${active ? ' is-active' : ''}${extracting ? ' is-extracting' : ''}`}
            style={{
              left: `${live.x * 100}%`,
              top: `${live.y * 100}%`,
              width: `${live.w * 100}%`,
              height: `${live.h * 100}%`,
              transform: `rotate(${layer.transform.rotationDeg}deg) scaleX(${layer.transform.flipX ? -1 : 1}) scaleY(${layer.transform.flipY ? -1 : 1})`,
              opacity: layer.transform.opacity ?? 1,
              display: layer.transform.visible === false ? 'none' : undefined
            }}
            onPointerDown={(event) => {
              if (event.button !== 0) return
              if ((event.target as HTMLElement).closest('.studio-layer-split-rect__handle')) return
              event.preventDefault()
              event.stopPropagation()
              updateNodeData(node.id, {
                layerSeparation: { ...state, activeLayerId: layer.id }
              })
              drag.current = {
                layerId: layer.id,
                mode: 'move',
                startX: event.clientX,
                startY: event.clientY,
                origin: { ...layer.transform.rect },
                activated: false
              }
              const root = (event.currentTarget as HTMLElement).closest('.studio-layer-compose-surface') as HTMLElement | null
              root?.setPointerCapture(event.pointerId)
            }}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={layer.mediaUrl}
              alt={layer.label || layer.id}
              className="pointer-events-none h-full w-full object-contain"
              draggable={false}
            />
            {active
              ? STUDIO_LAYER_SPLIT_HANDLES.map((handle) => (
                <button
                  key={handle}
                  type="button"
                  aria-label={`缩放图层-${handle}`}
                  className={`studio-layer-split-rect__handle is-${handle}`}
                  onPointerDown={(event) => {
                    event.preventDefault()
                    event.stopPropagation()
                    updateNodeData(node.id, {
                      layerSeparation: { ...state, activeLayerId: layer.id }
                    })
                    drag.current = {
                      layerId: layer.id,
                      mode: 'resize',
                      handle,
                      startX: event.clientX,
                      startY: event.clientY,
                      origin: { ...layer.transform.rect },
                      activated: true
                    }
                    setPreview({ id: layer.id, rect: { ...layer.transform.rect }, extracting: false })
                    const root = (event.currentTarget as HTMLElement).closest('.studio-layer-compose-surface') as HTMLElement | null
                    root?.setPointerCapture(event.pointerId)
                  }}
                />
              ))
              : null}
          </div>
        )
      })}
    </div>
  )
}
