'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AppstoreOutlined, CloseOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageEditSession } from '@/types/studio'
import {
  buildGridStoryboardPayload,
  resolveGridStoryboardNodePosition,
  uniqueGridCells,
  uploadSelectedGridCells
} from '@/utils/studio/studioImageGridStoryboard'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { loadStudioCanvasImage } from '@/utils/studio/studioCanvasImageLoad'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioImageGridBottomToolbar({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  if (!session || session.mode !== 'grid' || session.nodeId !== nodeId || !node) return null
  return <StudioImageGridBottomToolbarInner node={node} session={session} />
}

function StudioImageGridBottomToolbarInner({
  node,
  session
}: {
  node: StudioCanvasNode
  session: StudioImageEditSession
}) {
  const closeImageEditSession = useStudioUiStore((state) => state.closeImageEditSession)
  const addNode = useStudioUiStore((state) => state.addNode)
  const canvasRuntime = useCanvasProjectRuntime()
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [sourceSize, setSourceSize] = useState<{ width: number; height: number } | null>(null)
  const regionMode = canvasRuntime && session.splitMode === 'REGIONS'

  useEffect(() => {
    if (!regionMode || !node.data.mediaUrl) return
    let active = true
    void loadStudioCanvasImage(node.data.mediaUrl).then((image) => {
      if (active) setSourceSize({ width: image.width, height: image.height })
      image.dispose()
    }).catch(() => { if (active) setSourceSize(null) })
    return () => { active = false }
  }, [regionMode, node.data.mediaUrl])

  const rows = Math.max(1, session.gridRows ?? 1)
  const columns = Math.max(1, session.gridColumns ?? 1)
  const selected = useMemo(() => uniqueGridCells(session.selectedCells ?? []), [session.selectedCells])
  const gridLabel = selected.length ? `${rows}×${columns}` : '请选择宫格'

  const createStoryboard = useCallback(async () => {
    if (busyRef.current) return
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    if (!regionMode && !selected.length) {
      message.warning('请至少选择一个宫格')
      return
    }
    if (canvasRuntime && !canvasRuntime.canMutate) {
      message.warning('画布协作连接尚未就绪，暂时不能切分图片')
      return
    }
    busyRef.current = true
    setBusy(true)
    const scopeKey = useStudioUiStore.getState().scopeKey
    let targetId: string | null = null
    let resultReady = false
    try {
      if (regionMode) {
        if (!session.regionSelected || !sourceSize || !canvasRuntime) throw new Error('请在原图上拖动选择有效区域')
        const left = Math.round(session.rect.x * sourceSize.width)
        const top = Math.round(session.rect.y * sourceSize.height)
        const right = Math.round((session.rect.x + session.rect.w) * sourceSize.width)
        const bottom = Math.round((session.rect.y + session.rect.h) * sourceSize.height)
        const region = { x: left, y: top, width: right - left, height: bottom - top }
        if (region.width < 1 || region.height < 1 || left < 0 || top < 0 || right > sourceSize.width || bottom > sourceSize.height) {
          throw new Error('自定义区域必须在原图像素范围内')
        }
        await runStudioRequestSingleFlight(`image-region:${useStudioUiStore.getState().scopeKey}:${node.id}:${JSON.stringify(region)}`, async () => {
          const position = placeStudioNodeWithoutOverlap({ x: node.position.x + 310, y: node.position.y },
            { width: 240, height: 180 }, useStudioUiStore.getState().nodes.map(toStudioNodeRect))
          targetId = addNode('image', position, {
            title: `${node.data.title} · 区域裁切`, subtitle: `由 ${node.data.title} 裁切`,
            zoneId: node.data.zoneId, status: 'generating', mediaKind: 'image', imagePurpose: 'crop',
            prompt: node.data.prompt, composerHidden: true, canvasTransient: true,
            resultSummary: '正在切分原图区域'
          }, { nodeId: node.id, role: 'source', kind: 'reference' })
          closeImageEditSession(session)
          const result = await canvasRuntime.splitImageRegion(node.id, region)
          const output = result.outputs[0]
          if (!output?.resourceId || result.outputs.length !== 1) throw new Error('区域切分结果不完整')
          const store = useStudioUiStore.getState()
          if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
          const source = store.nodes.find((item) => item.id === node.id)
          if (!source || source.data.mediaUrl !== node.data.mediaUrl) throw new Error('原图已变化，切分结果未加入画布')
          const sourceResourceId = canvasRuntime.nodeResources(node.id).find((item) => item.mediaType === 'IMAGE' && item.resourceUrl === source.data.mediaUrl)?.resourceId
          if (!sourceResourceId) throw new Error('原图资源登记尚未完成')
          store.updateNodeData(targetId!, {
            status: 'success', canvasTransient: false, errorMessage: undefined,
            mediaUrl: output.resourceUrl, mediaAspectRatio: output.width / output.height,
            canvasResourceIds: [String(output.resourceId)], canvasPrimaryResourceId: String(output.resourceId),
            imageSplitOrigin: { sourceNodeId: source.id, sourceResourceId: String(sourceResourceId), x: output.x, y: output.y, width: output.width, height: output.height },
            resultSummary: '原像素区域裁切'
          })
          resultReady = true
          await canvasRuntime.saveNow()
        })
        if (resultReady) message.success('已创建原像素区域节点（无模型扣费）')
        closeImageEditSession(session)
        return
      }
      await runStudioRequestSingleFlight(`image-grid-group:${useStudioUiStore.getState().scopeKey}:${node.id}:${rows}x${columns}:${JSON.stringify(selected)}`, async () => {
        const position = resolveGridStoryboardNodePosition(node)
        targetId = addNode('image', position, {
          title: `${node.data.title} · 宫格分镜`, subtitle: `由 ${node.data.title} 切分`,
          zoneId: node.data.zoneId, status: 'generating', mediaKind: 'image',
          imagePurpose: 'grid_storyboard', prompt: node.data.prompt,
          storyboardId: node.data.storyboardId, generationOptions: node.data.generationOptions,
          composerHidden: true, canvasTransient: true, resultSummary: '正在切分宫格'
        }, { nodeId: node.id, role: 'source', kind: 'reference' })
        closeImageEditSession(session)
        const uploads = canvasRuntime
          ? (await canvasRuntime.splitImage(node.id, rows, columns, selected)).outputs.map((item) => ({
              row: item.row!,
              col: item.col!,
              mediaUrl: item.resourceUrl,
              resourceId: String(item.resourceId),
              mediaAspectRatio: item.width / item.height,
              title: `${item.row! + 1}-${item.col! + 1}`
            }))
          : await uploadSelectedGridCells(node.data.mediaUrl!, rows, columns, selected, node.data.title)
        if (uploads.length !== selected.length) throw new Error('切分结果数量不完整，请刷新后重试')
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
        const source = store.nodes.find((item) => item.id === node.id)
        if (!source) throw new Error('源图片节点已删除，切分结果未加入画布')
        if (source.data.mediaUrl !== node.data.mediaUrl) throw new Error('原图片已变化，请重新切分')
        const payload = buildGridStoryboardPayload({
          rows,
          columns,
          sourceNode: source,
          filled: uploads
        })
        store.updateNodeData(targetId!, {
          title: payload.title,
          subtitle: payload.subtitle,
          status: 'success',
          canvasTransient: false,
          errorMessage: undefined,
          mediaAspectRatio: source.data.mediaAspectRatio,
          canvasResourceIds: uploads.flatMap((item) => item.resourceId ? [item.resourceId] : []),
          gridStoryboard: payload.gridStoryboard,
          resultSummary: payload.subtitle
        })
        resultReady = true
        if (canvasRuntime) await canvasRuntime.saveNow()
      })
      if (resultReady) message.success('已创建宫格分镜组（无模型扣费）')
      closeImageEditSession(session)
    } catch (error: unknown) {
      const errorMessage = (error as Error)?.message || '切分失败，请从原图重试'
      const store = useStudioUiStore.getState()
      if (store.scopeKey !== scopeKey) return
      if (!resultReady && targetId && store.nodes.some((item) => item.id === targetId)) {
        store.updateNodeData(targetId, { status: 'failed', errorMessage, resultSummary: errorMessage })
      }
      message.error(resultReady ? `切分已完成，但画布保存失败：${errorMessage}` : errorMessage)
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }, [
    addNode,
    canvasRuntime,
    closeImageEditSession,
    columns,
    node,
    rows,
    selected,
    regionMode,
    session.regionSelected,
    session.rect,
    sourceSize
  ])

  return (
    <div className="studio-image-tool-bar studio-image-tool-bar--grid studio-image-tool-bar--chrome">
      <button type="button" aria-label="关闭宫格切分" onClick={() => closeImageEditSession()}>
        <CloseOutlined />
      </button>
      <span className="studio-image-tool-bar__divider" />
      <span className="studio-image-tool-bar__ghost-label">
        <AppstoreOutlined aria-hidden />
        {regionMode ? '自定义区域' : gridLabel}
      </span>
      <button
        type="button"
        className="studio-image-tool-bar__chip is-primary"
        disabled={busy || (regionMode ? !session.regionSelected || !sourceSize : !selected.length) || Boolean(canvasRuntime && !canvasRuntime.canMutate)}
        onClick={() => { void createStoryboard() }}
      >
        {regionMode ? '创建裁切节点' : '创建分镜组'}
      </button>
    </div>
  )
}
