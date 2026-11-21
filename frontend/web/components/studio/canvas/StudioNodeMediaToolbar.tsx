'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioChromeIcon } from '@/components/studio/theme/StudioChromeIcon'
import { studioDeleteReason } from '@/utils/studio/studioDeletePolicy'

import { forwardRef, memo, useCallback, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  CameraOutlined,
  CloseOutlined,
  ScanOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EllipsisOutlined,
  ExpandAltOutlined,
  AppstoreOutlined,
  FolderOutlined,
  LinkOutlined,
  SmileOutlined
} from '@ant-design/icons'
import { Dropdown, Modal, message, type MenuProps } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { downloadMediaFileWithToast } from '@/utils/mediaFileDownload'
import { openImagePreviewModal } from '@/utils/openImagePreviewModal'
import { openVideoPreviewModal } from '@/utils/openVideoPreviewModal'
import { isImagePreviewUrl } from '@/components/studio/canvas/StudioMediaPreview'
import {
  placeStudioNodeMediaToolbar,
  shouldShowStudioNodeMediaToolbar,
  type StudioMediaToolbarRect
} from '@/utils/studio/studioNodeMediaToolbar'
import {
  resolveStudioNodeMediaKind,
  studioNodeMediaDownloadType
} from '@/utils/studio/studioNodeMedia'
import {
  dispatchStudioCaptureVideoFrame,
  type StudioVideoCaptureFrame
} from '@/utils/studio/studioNodeMediaEvents'
import { isStudioStoryboardImageNode } from '@/utils/studio/studioImageNode'
import { fullCropRect } from '@/utils/studio/studioImageAiTools'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioGridSplitMenu } from './StudioGridSplitMenu'
import { StudioNodeMediaImageTools } from './StudioNodeMediaImageTools'
import { StudioNodeMediaVideoTools } from './StudioNodeMediaVideoTools'
import { StudioNodeMediaAudioTools } from './StudioNodeMediaAudioTools'
import { useStudioAudioEditor } from '@/stores/studioAudioEditor'
import { StudioNodeLabelPicker } from './StudioNodeLabels'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { apiErrorMessage } from '@/utils/apiError'

const dropdownClassNames = { root: 'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas' }

export interface StudioNodeMediaToolbarHandle {
  reposition: (
    nodeId: string,
    selectionRect: StudioMediaToolbarRect,
    viewportRect: StudioMediaToolbarRect
  ) => void
}

interface StudioNodeMediaToolbarProps {
  onBoundsChange?: (nodeId: string, rect: StudioMediaToolbarRect | null) => void
}

export const StudioNodeMediaToolbar = memo(forwardRef<StudioNodeMediaToolbarHandle, StudioNodeMediaToolbarProps>(
  function StudioNodeMediaToolbar({ onBoundsChange }, ref) {
    const canvasRuntime = useCanvasProjectRuntime()
    const elementRef = useRef<HTMLDivElement>(null)
    const lastRectsRef = useRef<{
      nodeId: string
      selection: StudioMediaToolbarRect
      viewport: StudioMediaToolbarRect
    } | null>(null)
    const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
    const studioMode = useStudioUiStore((state) => state.studioMode)
    const emotionSession = useStudioUiStore((state) => state.emotionSession)
    const patchEmotionSession = useStudioUiStore((state) => state.patchEmotionSession)
    const closeEmotionSession = useStudioUiStore((state) => state.closeEmotionSession)
    const openEmotionSession = useStudioUiStore((state) => state.openEmotionSession)
    const referencePicker = useStudioUiStore((state) => state.referencePicker)
    const videoEditSession = useStudioUiStore((state) => state.videoEditSession)
    const videoClipSession = useStudioUiStore((state) => state.videoClipSession)
    const audioSession = useStudioAudioEditor((state) => state.session)
    const scopeKey = useStudioUiStore((state) => state.scopeKey)
    const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === selectedNodeIds[0]))
    const selectedNodeIdRef = useRef(node?.id ?? null)
    selectedNodeIdRef.current = node?.id ?? null
    const selectNodes = useStudioUiStore((state) => state.selectNodes)
    const beginReferencePick = useStudioUiStore((state) => state.beginReferencePick)
    const cancelReferencePick = useStudioUiStore((state) => state.cancelReferencePick)
    const duplicateNodes = useStudioUiStore((state) => state.duplicateNodes)
    const deleteNodes = useStudioUiStore((state) => state.deleteNodes)
    const saveNodesToAssets = useStudioUiStore((state) => state.saveNodesToAssets)
    const openImageEditSession = useStudioUiStore((state) => state.openImageEditSession)
    const [gridMenuOpen, setGridMenuOpen] = useState(false)


    const visible = shouldShowStudioNodeMediaToolbar(node, studioMode, selectedNodeIds.length)
      && !(emotionSession?.nodeId === node?.id && emotionSession?.phase === 'editing')
      && videoEditSession?.nodeId !== node?.id
      && videoClipSession?.nodeId !== node?.id
      && !(audioSession && audioSession.sourceNodeId === node?.id && audioSession.scopeKey === scopeKey && audioSession.sourceAudioUrl === node?.data.mediaUrl)

    const applyPosition = useCallback((
      nodeId: string,
      selectionRect: StudioMediaToolbarRect,
      viewportRect: StudioMediaToolbarRect
    ) => {
      // Drop stale geometry from a previous selection before the toolbar remounts/rebinds.
      if (selectedNodeIdRef.current !== nodeId) return
      lastRectsRef.current = { nodeId, selection: selectionRect, viewport: viewportRect }
      const element = elementRef.current
      if (!element) return
      // Size while still unanchored/hidden: offset box is stable; getBoundingClientRect on
      // left:-9999 can under-report and recenter the bar into a visible flash.
      const size = {
        width: Math.max(element.offsetWidth, element.scrollWidth, 120),
        height: Math.max(element.offsetHeight, 36)
      }
      const position = placeStudioNodeMediaToolbar(selectionRect, viewportRect, size)
      // Write geometry before revealing. data-anchor-node-id lifts the CSS hide rule.
      element.style.left = `${position.left}px`
      element.style.top = `${position.top}px`
      element.dataset.anchorNodeId = nodeId
      element.dataset.placement = position.placement
      element.style.visibility = 'visible'
      element.style.pointerEvents = 'auto'
      onBoundsChange?.(nodeId, { ...position, width: size.width, height: size.height,
        right: position.left + size.width, bottom: position.top + size.height })
    }, [onBoundsChange])

    useImperativeHandle(ref, () => ({
      reposition(nodeId, selectionRect, viewportRect) {
        applyPosition(nodeId, selectionRect, viewportRect)
      }
    }), [applyPosition])

    // Switching nodes (group / composer) must clear the anchor before paint.
    // Unanchored chrome stays hidden via :not([data-anchor-node-id]).
    useLayoutEffect(() => {
      const element = elementRef.current
      if (!element) return
      if (element.dataset.anchorNodeId === node?.id) return
      lastRectsRef.current = null
      element.style.visibility = 'hidden'
      element.style.pointerEvents = 'none'
      delete element.dataset.anchorNodeId
    }, [node?.id])

    useLayoutEffect(() => {
      const element = elementRef.current
      if (!element || typeof ResizeObserver === 'undefined') return
      const observer = new ResizeObserver(() => {
        const last = lastRectsRef.current
        // Only refine an already-anchored bar when its content width changes.
        if (!last || element.dataset.anchorNodeId !== last.nodeId) return
        applyPosition(last.nodeId, last.selection, last.viewport)
      })
      observer.observe(element)
      return () => observer.disconnect()
    }, [applyPosition, node?.id, visible])

    useLayoutEffect(() => {
      const nodeId = node?.id
      if (!nodeId || visible) return
      onBoundsChange?.(nodeId, null)
    }, [node?.id, onBoundsChange, visible])

    const mediaKind = node ? resolveStudioNodeMediaKind(node.data.kind, node.data.mediaKind) : undefined
    const isImageToolbar = Boolean(
      node && (node.data.kind === 'image' || isStudioStoryboardImageNode(node.data))
    )
    const pickingRef = referencePicker?.targetNodeId === node?.id
    const videoPlayable = Boolean(
      node?.data.mediaUrl && mediaKind === 'video' && !isImagePreviewUrl(node.data.mediaUrl)
    )

    const toggleReferencePick = useCallback(() => {
      if (!node) return
      if (pickingRef) cancelReferencePick()
      else beginReferencePick(node.id)
    }, [beginReferencePick, cancelReferencePick, node, pickingRef])

    const openPreview = useCallback(() => {
      if (!node?.data.mediaUrl) return
      if (mediaKind === 'video' && !isImagePreviewUrl(node.data.mediaUrl)) {
        openVideoPreviewModal({ url: node.data.mediaUrl, title: node.data.title })
        return
      }
      if (mediaKind === 'image' || isImagePreviewUrl(node.data.mediaUrl)) {
        openImagePreviewModal({ url: node.data.mediaUrl, title: node.data.title })
      }
    }, [mediaKind, node])

    const downloadMedia = useCallback(() => {
      if (!node?.data.mediaUrl) return
      void downloadMediaFileWithToast(
        node.data.mediaUrl,
        node.data.title,
        studioNodeMediaDownloadType(node.data.kind, node.data.mediaKind)
      )
    }, [node])

    const saveToAssets = useCallback(() => {
      if (!node) return
      const count = saveNodesToAssets([node.id])
      if (count) message.success(`已保存 ${count} 项到资产`)
      else message.info('没有可保存的媒体')
    }, [node, saveNodesToAssets])

    const duplicateNode = useCallback(() => {
      if (!node) return
      duplicateNodes([node.id])
    }, [duplicateNodes, node])

    const removeNode = useCallback(() => {
      if (!node) return
      const state = useStudioUiStore.getState()
      const currentNode = state.nodes.find((item) => item.id === node.id)
      if (!currentNode || currentNode.data.flowBinding) return
      const reason = studioDeleteReason([currentNode], state.tasks)
      const remove = async () => {
        try {
          if (canvasRuntime) await canvasRuntime.deleteNodes([node.id])
          else deleteNodes([node.id])
        } catch (error: unknown) {
          message.error(apiErrorMessage(error, '删除节点失败'))
          throw error
        }
      }
      if (state.studioMode === 'free' && !reason) {
        void remove().catch(() => undefined)
        return
      }
      Modal.confirm({
        title: `删除「${node.data.title}」？`,
        content: `${reason ?? ''}关联连线会一并移除，可通过撤销恢复节点结构。`,
        okText: '删除',
        cancelText: '取消',
        okButtonProps: { danger: true },
        className: 'create-flow-modal',
        onOk: remove
      })
    }, [canvasRuntime, deleteNodes, node])

    const captureFrame = useCallback((frame: StudioVideoCaptureFrame) => {
      if (!node || !videoPlayable) return
      dispatchStudioCaptureVideoFrame(node.id, frame)
    }, [node, videoPlayable])

    const captureMenu: MenuProps = useMemo(() => ({
      className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
      selectable: false,
      items: [
        { key: 'first', label: '截取首帧', disabled: !videoPlayable },
        { key: 'current', label: '截取当前帧', disabled: !videoPlayable },
        { key: 'last', label: '截取尾帧', disabled: !videoPlayable }
      ],
      onClick: ({ key }) => {
        if (key === 'first') captureFrame('首帧')
        if (key === 'current') captureFrame('当前帧')
        if (key === 'last') captureFrame('尾帧')
      }
    }), [captureFrame, videoPlayable])

    const moreMenu: MenuProps = useMemo(() => ({
      className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
      selectable: false,
      items: [
        { key: 'duplicate', label: '创建副本', icon: <CopyOutlined /> },
        { key: 'assets', label: '保存到资产', icon: <FolderOutlined /> },
        { type: 'divider' },
        { key: 'delete', label: '删除节点', icon: <DeleteOutlined />, danger: true }
      ],
      onClick: ({ key }) => {
        if (key === 'duplicate') duplicateNode()
        if (key === 'assets') saveToAssets()
        if (key === 'delete') removeNode()
      }
    }), [duplicateNode, removeNode, saveToAssets])

    const startGridSplit = useCallback((rows: number, columns: number, region = false) => {
      if (!node?.data.mediaUrl) {
        message.warning('请先上传或生成源图片')
        return
      }
      setGridMenuOpen(false)
      selectNodes([node.id])
      openImageEditSession({
        mode: 'grid',
        nodeId: node.id,
        aspectRatio: node.data.generationOptions?.aspectRatio || 'original',
        rect: fullCropRect(),
        splitMode: region ? 'REGIONS' : 'GRID',
        regionSelected: false,
        gridRows: rows,
        gridColumns: columns,
        selectedCells: [],
        hdScale: 2
      })
    }, [node, openImageEditSession, selectNodes])

    if (!visible || !node) return null
    if (emotionSession?.nodeId === node.id && emotionSession.phase === 'picking') {
      const status = emotionSession.detectionStatus
      const label = emotionSession.manualMode
        ? status === 'empty' ? '未检测到人脸，请框选面部' : status === 'failed' ? '识别不可用，请框选面部' : '拖拽框选面部区域'
        : status === 'pending' && !emotionSession.faces.length ? '正在识别人脸…' : '请选择人物进行操作'
      return (
        <div
          ref={elementRef}
          className="studio-node-media-toolbar studio-canvas-glass-toolbar nodrag nowheel"
          role="toolbar"
          aria-label="情绪调节选人"
          onPointerDown={(event) => event.stopPropagation()}
        >
          <StudioCanvasToolbarButton label="关闭情绪调节" icon={<CloseOutlined />} onClick={() => closeEmotionSession(emotionSession.id)} />
          <span className="studio-canvas-glass-toolbar__divider" aria-hidden />
          <span className="studio-canvas-glass-toolbar__label" role="status">{label}</span>
          <StudioCanvasToolbarButton showLabel label="手动框选" icon={<ScanOutlined />}
            active={emotionSession.manualMode}
            onClick={() => patchEmotionSession(emotionSession.id, { manualMode: !emotionSession.manualMode || !emotionSession.faces.length })} />
        </div>
      )
    }


    return (
      <div
        ref={elementRef}
        className="studio-node-media-toolbar studio-canvas-glass-toolbar nodrag nowheel"
        role="toolbar"
        aria-label={`${node.data.title}操作栏`}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <StudioNodeLabelPicker key={node.id} node={node} />
        {mediaKind === 'audio' ? <StudioNodeMediaAudioTools node={node} /> : isImageToolbar ? (
          <StudioNodeMediaImageTools node={node} />
        ) : mediaKind === 'video' || node.data.kind === 'video' ? (
          <StudioNodeMediaVideoTools node={node} />
        ) : null}
        {isImageToolbar && <StudioCanvasToolbarButton
          showLabel
          label={studioMode === 'free' && node.data.kind === 'image' ? '情绪调节' : pickingRef ? '取消参考' : '添加参考'}
          icon={studioMode === 'free' && node.data.kind === 'image' ? <SmileOutlined aria-hidden /> : <LinkOutlined aria-hidden />}
          iconName={studioMode === 'free' && node.data.kind === 'image' ? undefined : 'link'}
          active={pickingRef}
          onClick={studioMode === 'free' && node.data.kind === 'image' ? () => openEmotionSession(node.id) : toggleReferencePick}
        />}
        <span className="studio-canvas-glass-toolbar__divider" aria-hidden />
        {isImageToolbar ? (
          <StudioCanvasPopover
            open={gridMenuOpen}
            onOpenChange={setGridMenuOpen}
            trigger="click"
            placement="bottom"
            arrow={false}
            classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
            getPopupContainer={(trigger) => trigger.closest('.studio-canvas') ?? document.body}
            content={<StudioGridSplitMenu onSelect={(rows, columns) => startGridSplit(rows, columns)} onSelectRegion={() => startGridSplit(1, 1, true)} />}
          >
            <span className="studio-node-media-toolbar__popover-anchor">
              <StudioCanvasToolbarButton
                showLabel
                label="宫格切分"
                icon={<StudioChromeIcon name="gridSplit" fallback={<AppstoreOutlined aria-hidden />} />}
                disabled={!node.data.mediaUrl || node.data.imagePurpose === 'grid_storyboard'}
                onClick={() => setGridMenuOpen(true)}
              />
            </span>
          </StudioCanvasPopover>
        ) : null}
        {mediaKind === 'video' && node.data.mediaUrl ? (
          <Dropdown menu={captureMenu} trigger={['click']} placement="bottom" classNames={dropdownClassNames}>
            <button
              type="button"
              className="studio-canvas-glass-toolbar__btn studio-canvas-glass-toolbar__btn--labeled"
              aria-label="截取画面"
              disabled={!videoPlayable}
            >
              <CameraOutlined aria-hidden />
              <span className="studio-canvas-glass-toolbar__label">截帧</span>
            </button>
          </Dropdown>
        ) : null}
        {node.data.mediaUrl ? (
          <StudioCanvasToolbarButton
            label="下载"
            icon={<DownloadOutlined aria-hidden />}
            iconName="download"
            onClick={downloadMedia}
          />
        ) : null}
        {node.data.mediaUrl && mediaKind !== 'audio' ? (
          <StudioCanvasToolbarButton
            label="全屏预览"
            icon={<ExpandAltOutlined aria-hidden />}
            iconName="expand"
            onClick={openPreview}
          />
        ) : null}
        <StudioCanvasTooltip title="更多">
          <span className="studio-canvas-glass-toolbar__tooltip-trigger">
            <Dropdown menu={moreMenu} trigger={['click']} placement="bottom" classNames={dropdownClassNames}>
              <button type="button" className="studio-canvas-glass-toolbar__btn" aria-label="更多">
                <EllipsisOutlined aria-hidden />
              </button>
            </Dropdown>
          </span>
        </StudioCanvasTooltip>
      </div>
    )
  }
))

export default StudioNodeMediaToolbar
