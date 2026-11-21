'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { forwardRef, memo, useCallback, useImperativeHandle, useMemo, useRef, useState } from 'react'
import {
  AppstoreOutlined,
  ColumnWidthOutlined,
  DeleteOutlined,
  GroupOutlined,
  NumberOutlined,
  ScissorOutlined,
  UngroupOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import {
  clearGridStoryboardMedia,
  filledGridStoryboardCells,
  resolveGridStoryboardReferenceSource,
  spawnGridStoryboardChildNodes,
  stitchGridStoryboardToFile
} from '@/utils/studio/studioImageGridStoryboard'
import { resizeGridStoryboard } from '@/utils/studio/studioImageGridSession'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { STUDIO_CROP_ASPECT_RATIOS } from '@/utils/studio/studioImageAiTools'
import {
  placeStudioNodeMediaToolbar,
  type StudioMediaToolbarRect
} from '@/utils/studio/studioNodeMediaToolbar'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'
import { StudioGridSplitMenu } from './StudioGridSplitMenu'
import '@/components/studio/composer/studio-image-ai-tools.css'

export interface StudioGridStoryboardToolbarHandle {
  reposition: (selectionRect: StudioMediaToolbarRect, viewportRect: StudioMediaToolbarRect) => void
}

export const StudioGridStoryboardToolbar = memo(forwardRef<StudioGridStoryboardToolbarHandle>(
  function StudioGridStoryboardToolbar(_, ref) {
    const elementRef = useRef<HTMLDivElement>(null)
    const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
    const canvasRuntime = useCanvasProjectRuntime()
    const nodes = useStudioUiStore((state) => state.nodes)
    const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
    const addNode = useStudioUiStore((state) => state.addNode)
    const deleteNodes = useStudioUiStore((state) => state.deleteNodes)
    const groupNodes = useStudioUiStore((state) => state.groupNodes)
    const [gridMenuOpen, setGridMenuOpen] = useState(false)
    const [ratioOpen, setRatioOpen] = useState(false)
    const [busy, setBusy] = useState(false)
    const stitchBusyRef = useRef(false)

    const node = useMemo(() => {
      if (selectedNodeIds.length !== 1) return undefined
      const hit = nodes.find((item) => item.id === selectedNodeIds[0])
      if (!hit || hit.data.imagePurpose !== 'grid_storyboard' || !hit.data.gridStoryboard) return undefined
      return hit
    }, [nodes, selectedNodeIds])

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect) {
        const element = elementRef.current
        if (!element) return
        const measured = element.getBoundingClientRect()
        const position = placeStudioNodeMediaToolbar(
          selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 220), height: Math.max(measured.height, 40) }
        )
        element.style.left = `${position.left}px`
        element.style.top = `${position.top}px`
      }
    }), [])

    const storyboard = node?.data.gridStoryboard
    const ratio = storyboard?.aspectRatio ?? 'original'
    const ratioLabel = STUDIO_CROP_ASPECT_RATIOS.find((item) => item.value === ratio)?.label ?? ratio

    const ratioItems = useMemo(() => STUDIO_CROP_ASPECT_RATIOS
      .filter((item) => item.value !== 'custom')
      .map((item) => ({ key: item.value, label: item.label })), [])

    const patchStoryboard = useCallback((patch: Partial<NonNullable<StudioCanvasNode['data']['gridStoryboard']>>) => {
      if (!node || !storyboard) return
      if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
      const next = { ...storyboard, ...patch }
      updateNodeData(node.id, {
        gridStoryboard: next,
        canvasResourceIds: next.cells.flatMap((cell) => cell.resourceId ? [cell.resourceId] : [])
      })
    }, [canvasRuntime, node, storyboard, updateNodeData])

    const resizeGrid = useCallback((rows: number, columns: number) => {
      if (!node || !storyboard) return
      setGridMenuOpen(false)
      patchStoryboard(resizeGridStoryboard(storyboard, rows, columns))
    }, [node, patchStoryboard, storyboard])

    const toggleSerial = useCallback(() => {
      if (!storyboard) return
      patchStoryboard({ showSerial: !storyboard.showSerial })
    }, [patchStoryboard, storyboard])

    const clearCells = useCallback(() => {
      if (!storyboard) return
      patchStoryboard(clearGridStoryboardMedia(storyboard))
      message.success('已清空格槽')
    }, [patchStoryboard, storyboard])

    const stitch = useCallback(async () => {
      if (!node || !storyboard || stitchBusyRef.current) return
      if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
      if (!filledGridStoryboardCells(storyboard).length) {
        message.warning('请先填充至少一个格槽')
        return
      }
      stitchBusyRef.current = true
      setBusy(true)
      const scopeKey = useStudioUiStore.getState().scopeKey
      const sourceGrid = JSON.stringify(storyboard)
      const size = studioNodeDimensions(node)
      const desired = { x: node.position.x + size.width + 48, y: node.position.y }
      const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, nodes.map(toStudioNodeRect))
      const targetId = addNode('image', position, {
        title: `${node.data.title} · 拼接(2K)`,
        subtitle: `由 ${node.data.title} 拼接`,
        zoneId: node.data.zoneId,
        status: 'generating',
        mediaKind: 'image',
        imagePurpose: 'reference',
        prompt: node.data.prompt,
        storyboardId: node.data.storyboardId,
        composerHidden: true,
        canvasTransient: true,
        resultSummary: '正在拼接并上传图片'
      }, { nodeId: node.id, role: 'source', kind: 'reference' })
      let resultReady = false
      try {
        const file = await stitchGridStoryboardToFile(storyboard, node.data.title)
        const beforeUpload = useStudioUiStore.getState()
        if (beforeUpload.scopeKey !== scopeKey || !beforeUpload.nodes.some((item) => item.id === targetId)) return
        if (JSON.stringify(beforeUpload.nodes.find((item) => item.id === node.id)?.data.gridStoryboard) !== sourceGrid) {
          throw new Error('宫格内容已变化，请重新拼接')
        }
        const resource = canvasRuntime ? await canvasRuntime.uploadResource(file, 'IMAGE') : null
        const mediaUrl = resource?.resourceUrl || (canvasRuntime ? null : await uploadImageToOssWithToast(file, () => undefined))
        if (!mediaUrl) throw new Error('拼接图片上传失败')
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
        const source = store.nodes.find((item) => item.id === node.id)
        if (!source || JSON.stringify(source.data.gridStoryboard) !== sourceGrid) throw new Error('宫格内容已变化，请重新拼接')
        store.updateNodeData(targetId, {
          status: 'success',
          mediaUrl,
          canvasTransient: false,
          errorMessage: undefined,
          ...(resource ? { canvasResourceIds: [String(resource.resourceId)], canvasPrimaryResourceId: String(resource.resourceId) } : {}),
          resultSummary: `${storyboard.rows}×${storyboard.columns} 宫格拼接`
        })
        resultReady = true
        if (canvasRuntime) await canvasRuntime.saveNow()
        message.success('已生成拼接图片节点')
      } catch (error: unknown) {
        const errorMessage = (error as Error)?.message || '拼接失败，请重试'
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey) return
        if (!resultReady && store.nodes.some((item) => item.id === targetId)) {
          store.updateNodeData(targetId, { status: 'failed', errorMessage, resultSummary: errorMessage })
        }
        message.error(resultReady ? `拼接已完成，但画布保存失败：${errorMessage}` : errorMessage)
      } finally {
        stitchBusyRef.current = false
        setBusy(false)
      }
    }, [addNode, canvasRuntime, node, nodes, storyboard])

    const ungroup = useCallback(() => {
      if (!node || !storyboard) return
      if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
      const spawns = spawnGridStoryboardChildNodes(node, nodes.filter((item) => item.id !== node.id))
      if (!spawns.length) {
        message.warning('没有可解组的媒体格')
        return
      }
      const childIds: string[] = []
      const source = resolveGridStoryboardReferenceSource(node, nodes)
      for (const spawn of spawns) {
        childIds.push(addNode(spawn.kind, spawn.position, spawn.data, source.id === node.id ? undefined : { nodeId: source.id, role: 'source', kind: 'reference' }))
      }
      deleteNodes([node.id])
      if (canvasRuntime) void canvasRuntime.saveNow().catch((error: unknown) => message.error((error as Error)?.message || '解组保存失败，请重试'))
      message.success(`已解组为 ${childIds.length} 个媒体节点`)
    }, [addNode, canvasRuntime, deleteNodes, node, nodes, storyboard])

    const convertToGroup = useCallback(() => {
      if (!node || !storyboard) return
      if (canvasRuntime && !canvasRuntime.canMutate) { message.warning('画布协作连接尚未就绪'); return }
      const spawns = spawnGridStoryboardChildNodes(node, nodes.filter((item) => item.id !== node.id))
      if (spawns.length < 2) {
        message.warning('至少需要 2 个有内容格槽才能转普通组')
        return
      }
      const childIds: string[] = []
      for (const spawn of spawns) {
        childIds.push(addNode(spawn.kind, spawn.position, spawn.data))
      }
      deleteNodes([node.id])
      groupNodes(childIds)
      if (canvasRuntime) void canvasRuntime.saveNow().catch((error: unknown) => message.error((error as Error)?.message || '转组保存失败，请重试'))
      // groupNodes 已选中 childIds → chrome=group
      message.success('已转为普通组合')
    }, [addNode, canvasRuntime, deleteNodes, groupNodes, node, nodes, storyboard])

    if (!node || !storyboard) return null

    return (
      <div
        ref={elementRef}
        className="studio-grid-storyboard-toolbar studio-canvas-glass-toolbar nodrag nowheel"
        role="toolbar"
        aria-label={`${node.data.title}分镜组工具栏`}
        style={{ left: -9999, top: 0 }}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <StudioCanvasDropdownPopover
          open={ratioOpen}
          onOpenChange={setRatioOpen}
          placement="bottom"
          ariaLabel="宽高比"
          activeKey={ratio}
          items={ratioItems}
          onSelect={(key) => patchStoryboard({ aspectRatio: key })}
          trigger={(
            <StudioComposerChip
              icon={<ColumnWidthOutlined />}
              label={ratioLabel}
              active={ratioOpen}
            />
          )}
        />
        <StudioCanvasPopover
          open={gridMenuOpen}
          onOpenChange={setGridMenuOpen}
          trigger="click"
          placement="bottom"
          arrow={false}
          classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
          getPopupContainer={(trigger) => trigger.closest('.studio-canvas') ?? document.body}
          content={<StudioGridSplitMenu onSelect={resizeGrid} />}
        >
          <span className="studio-node-media-toolbar__popover-anchor">
            <StudioCanvasToolbarButton
              showLabel
              caret
              expanded={gridMenuOpen}
              label={`宫格 ${storyboard.rows}x${storyboard.columns}`}
              icon={<AppstoreOutlined aria-hidden />}
              onClick={() => setGridMenuOpen(true)}
            />
          </span>
        </StudioCanvasPopover>
        <StudioCanvasToolbarButton
          showLabel
          label="拼接(2K)"
          icon={<ScissorOutlined aria-hidden />}
          disabled={busy}
          onClick={() => { void stitch() }}
        />
        <StudioCanvasToolbarButton
          showLabel
          label={storyboard.showSerial ? '隐藏序号' : '序号'}
          icon={<NumberOutlined aria-hidden />}
          active={storyboard.showSerial}
          onClick={toggleSerial}
        />
        <StudioCanvasToolbarButton
          showLabel
          label="清空"
          icon={<DeleteOutlined aria-hidden />}
          onClick={clearCells}
        />
        <StudioCanvasToolbarButton
          showLabel
          label="转普通组"
          icon={<GroupOutlined aria-hidden />}
          onClick={convertToGroup}
        />
        <span className="studio-canvas-glass-toolbar__divider" aria-hidden />
        <StudioCanvasToolbarButton
          showLabel
          label="解组"
          icon={<UngroupOutlined aria-hidden />}
          onClick={ungroup}
        />
      </div>
    )
  }
))
