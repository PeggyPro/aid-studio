'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  CheckOutlined,
  CloseOutlined,
  ColumnWidthOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import {
  STUDIO_CROP_ASPECT_RATIOS,
  applyCropHandleDelta,
  centeredCropRect,
  cropImageUrlToFile,
  moveCropRect,
  normalizedImageRectAspect,
  parseAspectRatio,
  type StudioCropHandle
} from '@/utils/studio/studioImageAiTools'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import '@/components/studio/composer/studio-image-ai-tools.css'
import { readMediaFileAspectRatio } from '@/utils/studio/studioMediaFileAspect'

const HANDLES: StudioCropHandle[] = ['nw', 'n', 'ne', 'w', 'e', 'sw', 's', 'se']

export function StudioImageCropOverlay({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const dragRef = useRef<{
    mode: 'move' | 'resize'
    handle?: StudioCropHandle
    startX: number
    startY: number
    rect: NonNullable<typeof session>['rect']
    hostWidth: number
    hostHeight: number
    aspect: number | null
  } | null>(null)
  const nextRectRef = useRef<NonNullable<typeof session>['rect'] | null>(null)
  const frameRef = useRef<number | null>(null)

  const active = session?.mode === 'crop' && session.nodeId === nodeId
  const rect = session?.rect

  useEffect(() => {
    if (!active) return
    const onMove = (event: PointerEvent) => {
      const drag = dragRef.current
      if (!drag) return
      const dx = (event.clientX - drag.startX) / drag.hostWidth
      const dy = (event.clientY - drag.startY) / drag.hostHeight
      nextRectRef.current = drag.mode === 'move'
        ? moveCropRect(drag.rect, dx, dy)
        : applyCropHandleDelta(drag.rect, drag.handle!, dx, dy, drag.aspect)
      if (frameRef.current !== null) return
      frameRef.current = window.requestAnimationFrame(() => {
        frameRef.current = null
        if (nextRectRef.current) patchImageEditSession({ rect: nextRectRef.current })
      })
    }
    const onUp = () => {
      if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current)
      frameRef.current = null
      if (nextRectRef.current) patchImageEditSession({ rect: nextRectRef.current })
      nextRectRef.current = null
      dragRef.current = null
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current)
    }
  }, [active, patchImageEditSession])

  const beginDragSession = useCallback((
    mode: 'move' | 'resize',
    event: React.PointerEvent,
    handle?: StudioCropHandle
  ) => {
    if (!session) return
    const host = event.currentTarget.closest('.studio-image-crop-overlay') as HTMLElement | null
    if (!host) return
    const box = host.getBoundingClientRect()
    event.preventDefault()
    event.stopPropagation()
    dragRef.current = {
      mode,
      handle,
      startX: event.clientX,
      startY: event.clientY,
      rect: { ...session.rect },
      hostWidth: Math.max(1, box.width),
      hostHeight: Math.max(1, box.height),
      aspect: normalizedImageRectAspect(parseAspectRatio(session.aspectRatio), box.width / box.height)
    }
  }, [session])

  const beginDrag = useCallback((handle: StudioCropHandle, event: React.PointerEvent) => {
    beginDragSession('resize', event, handle)
  }, [beginDragSession])

  if (!active || !rect) return null

  return (
    <div className="studio-image-crop-overlay nodrag nopan" aria-label="裁剪框">
      <div
        className="studio-image-crop-overlay__frame"
        style={{
          left: `${rect.x * 100}%`,
          top: `${rect.y * 100}%`,
          width: `${rect.w * 100}%`,
          height: `${rect.h * 100}%`
        }}
        onPointerDown={(event) => beginDragSession('move', event)}
      >
        <i className="studio-image-crop-overlay__grid" aria-hidden />
        {HANDLES.map((handle) => (
          <button
            key={handle}
            type="button"
            className={`studio-image-crop-overlay__handle is-${handle}`}
            aria-label={`拖拽${handle}`}
            onPointerDown={(event) => beginDrag(handle, event)}
          />
        ))}
      </div>
    </div>
  )
}

export function StudioImageCropToolbar({ nodeId }: { nodeId: string }) {
  const canvasRuntime = useCanvasProjectRuntime()
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const closeImageEditSession = useStudioUiStore((state) => state.closeImageEditSession)
  const addNode = useStudioUiStore((state) => state.addNode)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [ratioOpen, setRatioOpen] = useState(false)

  if (!session || session.mode !== 'crop' || session.nodeId !== nodeId) return null

  const setAspect = (value: string) => {
    patchImageEditSession({
      aspectRatio: value,
      rect: centeredCropRect(parseAspectRatio(value), node?.data.mediaAspectRatio
        || parseAspectRatio(node?.data.generationOptions?.aspectRatio || '') || 1)
    })
    setRatioOpen(false)
  }

  const confirm = async () => {
    if (!node?.data.mediaUrl || busyRef.current) return
    if (canvasRuntime && !canvasRuntime.canMutate) {
      message.error('当前画布不可编辑，请检查成员权限或协作连接')
      return
    }
    busyRef.current = true
    setBusy(true)
    const scopeKey = useStudioUiStore.getState().scopeKey
    const sourceUrl = node.data.mediaUrl
    const occupied = useStudioUiStore.getState().nodes.map(toStudioNodeRect)
    const position = placeStudioNodeWithoutOverlap(
      { x: node.position.x + 310, y: node.position.y },
      { width: 240, height: 180 }, occupied
    )
    // Reserve the visible result before decoding or uploading the image. The transient
    // node stays out of the shared workspace until a registered resource is available.
    const targetId = addNode('image', position, {
      title: `${node.data.title} · 裁剪`,
      subtitle: `由 ${node.data.title} 裁剪`,
      zoneId: node.data.zoneId,
      status: 'generating',
      mediaKind: 'image',
      imagePurpose: 'crop',
      prompt: node.data.prompt,
      composerHidden: true,
      canvasTransient: true,
      resultSummary: '正在裁剪并上传图片'
    }, { nodeId: node.id, role: 'source', kind: 'reference' })
    closeImageEditSession()
    selectNodes([targetId])
    let resultReady = false
    try {
      const file = await cropImageUrlToFile(sourceUrl, session.rect, `${node.data.title}-crop`)
      const aspectRatio = await readMediaFileAspectRatio(file)
      const beforeUpload = useStudioUiStore.getState()
      if (beforeUpload.scopeKey !== scopeKey || !beforeUpload.nodes.some((item) => item.id === targetId)) return
      if (beforeUpload.nodes.find((item) => item.id === node.id)?.data.mediaUrl !== sourceUrl) {
        throw new Error('原图片已变化，请重新裁剪')
      }
      const resource = canvasRuntime ? await canvasRuntime.uploadResource(file, 'IMAGE') : null
      const mediaUrl = canvasRuntime ? resource?.resourceUrl : await uploadImageToOssWithToast(file, () => undefined)
      if (!mediaUrl) throw new Error('裁剪图片上传失败')
      const store = useStudioUiStore.getState()
      if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
      const source = store.nodes.find((item) => item.id === node.id)
      if (!source) throw new Error('原图片节点已删除，请重新选择图片')
      if (source.data.mediaUrl !== sourceUrl) throw new Error('原图片已变化，请重新裁剪')
      store.updateNodeData(targetId, {
        status: 'success',
        mediaUrl,
        mediaAspectRatio: aspectRatio ?? undefined,
        canvasTransient: false,
        errorMessage: undefined,
        ...(resource ? {
          canvasResourceIds: [String(resource.resourceId)],
          canvasPrimaryResourceId: String(resource.resourceId)
        } : {}),
        resultSummary: '裁剪结果'
      })
      resultReady = true
      if (canvasRuntime) await canvasRuntime.saveNow()
      message.success('已生成裁剪图片节点')
    } catch (error: unknown) {
      const errorMessage = (error as Error)?.message || '裁剪失败，请从原图重试'
      const store = useStudioUiStore.getState()
      if (store.scopeKey !== scopeKey) return
      if (!resultReady && store.nodes.some((item) => item.id === targetId)) {
        store.updateNodeData(targetId, {
          status: 'failed', errorMessage, resultSummary: errorMessage
        })
      }
      message.error(resultReady ? `裁剪已完成，但画布保存失败：${errorMessage}` : errorMessage)
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <div className="studio-image-tool-bar studio-image-tool-bar--crop studio-image-tool-bar--chrome">
      <button type="button" aria-label="取消裁剪" disabled={busy} onClick={() => closeImageEditSession()}>
        <CloseOutlined />
      </button>
      <span className="studio-image-tool-bar__divider" />
      <StudioCanvasDropdownPopover
        open={ratioOpen}
        onOpenChange={setRatioOpen}
        placement="top"
        ariaLabel="裁剪宽高比"
        activeKey={session.aspectRatio}
        items={STUDIO_CROP_ASPECT_RATIOS
          .filter((item) => item.value !== 'custom')
          .map((item) => ({ key: item.value, label: item.label }))}
        onSelect={setAspect}
        trigger={(
          <button type="button" disabled={busy} className={`studio-image-tool-bar__chip${ratioOpen ? ' is-active' : ''}`}>
            <ColumnWidthOutlined />
            <span>宽高比</span>
          </button>
        )}
      />
      <button type="button" className="studio-image-tool-bar__chip is-primary" disabled={busy} onClick={() => { void confirm() }}>
        <CheckOutlined />
        <span>确认裁剪</span>
      </button>
    </div>
  )
}
