'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import {
  useCallback,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type PointerEvent as ReactPointerEvent
} from 'react'
import { createPortal } from 'react-dom'
import { PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useReactFlow } from '@xyflow/react'
import { StudioAssetLibraryModal } from '@/components/studio/composer/StudioAssetLibraryModal'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useCreationStore } from '@/stores/creation'
import { useStudioGridStoryboardDragStore } from '@/stores/studioGridStoryboardDrag'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioComposerRef, StudioGridStoryboard, StudioGridStoryboardCell } from '@/types/studio'
import { uploadImageToOssWithToast, uploadVideoToOssWithToast } from '@/utils/ossUpload'
import {
  patchGridStoryboardCell,
  resolveGridStoryboardCellCanvasSize,
  resolveGridStoryboardCellMediaKind
} from '@/utils/studio/studioImageGridStoryboard'
import { gridStoryboardContainsCanvasPoint } from '@/utils/studio/studioGridStoryboardTransfer'
import { readMediaFileAspectRatio } from '@/utils/studio/studioMediaFileAspect'
import '@/components/studio/composer/studio-image-ai-tools.css'

type GridCellPosition = { row: number; col: number }

export function StudioGridStoryboardNodePreview({
  nodeId,
  storyboard
}: {
  nodeId: string
  storyboard: StudioGridStoryboard
}) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const canvasRuntime = useCanvasProjectRuntime()
  const extractGridStoryboardCell = useStudioUiStore((state) => state.extractGridStoryboardCell)
  const nodes = useStudioUiStore((state) => state.nodes)
  const assets = useStudioUiStore((state) => state.savedAssets)
  const returningNodeId = useStudioGridStoryboardDragStore((state) => state.returningNodeId)
  const dropTarget = useStudioGridStoryboardDragStore((state) => state.dropTarget)
  const { screenToFlowPosition, getViewport } = useReactFlow()
  const node = useMemo(() => nodes.find((item) => item.id === nodeId), [nodeId, nodes])
  const projectId = useCreationStore((state) => Number(state.currentProjectId) || 0)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const fileRef = useRef<HTMLInputElement>(null)
  const [assetOpen, setAssetOpen] = useState(false)
  const [activeCell, setActiveCell] = useState<GridCellPosition | null>(null)
  const [menuCell, setMenuCell] = useState<GridCellPosition | null>(null)
  const [dragPreview, setDragPreview] = useState<{
    cell: StudioGridStoryboardCell
    clientX: number
    clientY: number
  } | null>(null)
  const dragRef = useRef<{
    cell: StudioGridStoryboardCell
    pointerId: number
    startX: number
    startY: number
    moved: boolean
  } | null>(null)

  const patchStoryboard = useCallback((next: StudioGridStoryboard) => {
    updateNodeData(nodeId, {
      gridStoryboard: next,
      canvasResourceIds: next.cells.flatMap((cell) => cell.resourceId ? [cell.resourceId] : [])
    })
  }, [nodeId, updateNodeData])

  const applyCellMedia = useCallback((
    row: number,
    col: number,
    mediaUrl: string,
    mediaKind: 'image' | 'video',
    mediaAspectRatio?: number,
    title?: string,
    resourceId?: string
  ) => {
    const latest = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
    const latestStoryboard = latest?.data.gridStoryboard
    if (!latest || !latestStoryboard) throw new Error('宫格分镜组已被删除')
    const currentCell = latestStoryboard.cells.find((cell) => cell.row === row && cell.col === col)
    if (!currentCell || currentCell.mediaUrl) throw new Error('目标格槽已被填充，请刷新后重试')
    const canvasSize = currentCell
      ? resolveGridStoryboardCellCanvasSize(latest, currentCell, nodes)
      : undefined
    patchStoryboard(patchGridStoryboardCell(latestStoryboard, row, col, {
      mediaUrl,
      resourceId,
      mediaKind,
      mediaAspectRatio,
      canvasWidth: canvasSize?.width,
      canvasHeight: canvasSize?.height,
      title: title ?? `${row + 1}-${col + 1}`,
      status: 'success'
    }))
  }, [nodeId, nodes, patchStoryboard])

  const onUploadLocal = useCallback(async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    const cell = activeCell
    if (!file || !cell) return
    if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
    const mediaKind = file.type.startsWith('video/') ? 'video' : 'image'
    try {
      const [mediaAspectRatio, resource] = await Promise.all([
        readMediaFileAspectRatio(file),
        canvasRuntime ? canvasRuntime.uploadResource(file, mediaKind === 'video' ? 'VIDEO' : 'IMAGE') : Promise.resolve(null)
      ])
      const mediaUrl = resource?.resourceUrl || (canvasRuntime ? null : mediaKind === 'video'
        ? await uploadVideoToOssWithToast(file, () => undefined)
        : await uploadImageToOssWithToast(file, () => undefined))
      if (!mediaUrl) return
      applyCellMedia(cell.row, cell.col, mediaUrl, mediaKind, mediaAspectRatio ?? undefined, file.name, resource ? String(resource.resourceId) : undefined)
      if (canvasRuntime) await canvasRuntime.saveNow()
      setActiveCell(null)
      setMenuCell(null)
    } catch (error: unknown) {
      message.error((error as Error)?.message || '宫格素材上传失败')
    }
  }, [activeCell, applyCellMedia, canvasRuntime])

  const onPickAsset = useCallback(async (ref: StudioComposerRef) => {
    const cell = activeCell
    if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
    const url = ref.previewUrl?.trim()
    if (!cell || !url || (ref.mediaKind !== 'image' && ref.mediaKind !== 'video')) return
    const sourceRatio = ref.nodeId
      ? nodes.find((item) => item.id === ref.nodeId)?.data.mediaAspectRatio
      : undefined
    try {
      const resource = canvasRuntime ? await canvasRuntime.resolveResourceReference(ref) : null
      applyCellMedia(cell.row, cell.col, resource?.resourceUrl || url, ref.mediaKind, sourceRatio, ref.title, resource ? String(resource.resourceId) : undefined)
      if (canvasRuntime) await canvasRuntime.saveNow()
      setActiveCell(null)
      setMenuCell(null)
      setAssetOpen(false)
    } catch (error: unknown) {
      message.error((error as Error)?.message || '所选素材无法登记到画布')
    }
  }, [activeCell, applyCellMedia, canvasRuntime, nodes])

  const onCellPointerDown = (cell: StudioGridStoryboardCell, event: ReactPointerEvent) => {
    if (!cell.mediaUrl) return
    event.stopPropagation()
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    setMenuCell(null)
    dragRef.current = {
      cell,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      moved: false
    }
  }

  const onCellPointerMove = (event: ReactPointerEvent) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    event.stopPropagation()
    const dx = Math.abs(event.clientX - drag.startX)
    const dy = Math.abs(event.clientY - drag.startY)
    if (dx > 6 || dy > 6) {
      drag.moved = true
      setDragPreview({ cell: drag.cell, clientX: event.clientX, clientY: event.clientY })
    }
  }

  const onCellPointerUp = (cell: StudioGridStoryboardCell, event: ReactPointerEvent) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragPreview(null)
    event.stopPropagation()
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
    if (!drag.moved || drag.cell.id !== cell.id || !node) return
    if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
    const dropPoint = screenToFlowPosition({ x: event.clientX, y: event.clientY })
    if (gridStoryboardContainsCanvasPoint(node, dropPoint)) return
    const createdId = extractGridStoryboardCell(nodeId, cell.id, dropPoint)
    if (createdId) {
      const kind = resolveGridStoryboardCellMediaKind(cell)
      message.success(`已拖出独立${kind === 'video' ? '视频' : '图片'}节点`)
    }
  }

  const onCellPointerCancel = (event: ReactPointerEvent) => {
    if (dragRef.current?.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragPreview(null)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  const cellMenu = (
    <div className="studio-grid-cell-menu nodrag" role="menu" aria-label="格槽操作">
      <button type="button" role="menuitem" onClick={() => { setMenuCell(null); fileRef.current?.click() }}>
        <UploadOutlined /> 从本地上传素材
      </button>
      <button type="button" role="menuitem" onClick={() => { setMenuCell(null); setAssetOpen(true) }}>
        <PlusOutlined /> 从历史记录中选择
      </button>
    </div>
  )

  const dragOverlay = dragPreview && node && typeof document !== 'undefined' ? (() => {
    const size = resolveGridStoryboardCellCanvasSize(node, dragPreview.cell, nodes)
    const zoom = getViewport().zoom
    const width = Math.max(48, size.width * zoom)
    const height = Math.max(36, size.height * zoom)
    const kind = resolveGridStoryboardCellMediaKind(dragPreview.cell)
    return createPortal(
      <div
        className="pointer-events-none fixed z-[1200] overflow-hidden rounded-2xl opacity-90 shadow-2xl"
        style={{
          left: dragPreview.clientX,
          top: dragPreview.clientY,
          width,
          height,
          transform: 'translate(-50%, -50%)'
        }}
        aria-hidden="true"
      >
        {kind === 'video' ? (
          <video className="h-full w-full object-cover" src={dragPreview.cell.mediaUrl} muted playsInline preload="metadata" />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img className="h-full w-full object-cover" src={dragPreview.cell.mediaUrl} alt="" />
        )}
      </div>,
      document.body
    )
  })() : null

  return (
    <>
      <div
        className="studio-grid-storyboard-preview"
        style={{
          gridTemplateColumns: `repeat(${storyboard.columns}, 1fr)`,
          gridTemplateRows: `repeat(${storyboard.rows}, 1fr)`
        }}
      >
        {storyboard.cells.map((cell) => {
          const mediaKind = resolveGridStoryboardCellMediaKind(cell)
          const isDropTarget = Boolean(returningNodeId) && dropTarget?.gridNodeId === nodeId
            && dropTarget.row === cell.row && dropTarget.col === cell.col
          const isDragging = dragPreview?.cell.id === cell.id
          return (
            <StudioCanvasPopover
              key={cell.id}
              open={menuCell?.row === cell.row && menuCell.col === cell.col && !cell.mediaUrl}
              onOpenChange={(open) => {
                if (open && !cell.mediaUrl) {
                  const position = { row: cell.row, col: cell.col }
                  setActiveCell(position)
                  setMenuCell(position)
                  return
                }
                setMenuCell(null)
              }}
              trigger="click"
              placement="bottom"
              arrow={false}
              classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
              content={cellMenu}
            >
              <button
                type="button"
                className={`studio-grid-storyboard-preview__cell${cell.mediaUrl ? ' is-filled' : ' is-empty'}${cell.status === 'generating' ? ' is-generating' : ''}${isDragging ? ' opacity-35' : ''}${isDropTarget ? dropTarget.occupied ? ' ring-2 ring-red-400/85' : ' ring-2 ring-[var(--studio-accent)]' : ''}`}
                aria-label={`格 ${cell.row + 1}-${cell.col + 1}`}
                onPointerDown={(event) => onCellPointerDown(cell, event)}
                onPointerMove={onCellPointerMove}
                onPointerUp={(event) => onCellPointerUp(cell, event)}
                onPointerCancel={onCellPointerCancel}
                onClick={(event) => event.stopPropagation()}
              >
                {cell.mediaUrl ? (
                  mediaKind === 'video' ? (
                    <video src={cell.mediaUrl} muted playsInline preload="metadata" draggable={false} />
                  ) : (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={cell.mediaUrl} alt={cell.title ?? ''} draggable={false} />
                  )
                ) : (
                  <PlusOutlined aria-hidden />
                )}
                {storyboard.showSerial ? (
                  <span className="studio-grid-storyboard-preview__serial">{cell.row + 1}-{cell.col + 1}</span>
                ) : null}
                {cell.mediaUrl && cell.status === 'success' ? (
                  <span className="studio-grid-storyboard-preview__badge">素材</span>
                ) : null}
              </button>
            </StudioCanvasPopover>
          )
        })}
      </div>
      <input
        ref={fileRef}
        hidden
        type="file"
        accept="image/jpeg,image/png,image/webp,video/mp4,video/webm,video/quicktime"
        onChange={(event) => { void onUploadLocal(event) }}
      />
      <StudioAssetLibraryModal
        open={assetOpen}
        onOpenChange={(open) => {
          setAssetOpen(open)
          if (!open) setActiveCell(null)
        }}
        nodes={nodes}
        assets={assets}
        projectId={projectId}
        episodeId={episodeId}
        allowedTabs={['image', 'video']}
        onSelect={onPickAsset}
      />
      {dragOverlay}
    </>
  )
}
