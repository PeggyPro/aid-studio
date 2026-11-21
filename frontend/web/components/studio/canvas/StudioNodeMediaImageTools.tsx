'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioChromeIcon } from '@/components/studio/theme/StudioChromeIcon'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import {
  AimOutlined,
  BgColorsOutlined,
  BlockOutlined,
  BorderOuterOutlined,
  ClearOutlined,
  EditOutlined,
  FileTextOutlined,
  HighlightOutlined,
  RotateRightOutlined,
  ScissorOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageAiToolKind } from '@/types/studio'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { STUDIO_IMAGE_AI_TOOLS, STUDIO_MASK_BRUSH_DEFAULT, fullCropRect, initialExpandRect } from '@/utils/studio/studioImageAiTools'
import { removeImageBackgroundToFile } from '@/utils/studio/studioImageMatting'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { StudioCanvasDropdownMenu } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'
import { StudioStoryboardCompositionMenu } from './StudioStoryboardCompositionMenu'
import '@/components/studio/composer/studio-image-ai-tools.css'

type ToolsAnchor = 'crop' | 'ai'

const IMAGE_TOOLS_POPOVER_CLASS = 'studio-composer-popover studio-composer-popover--canvas studio-image-tools-popover'

const AI_TOOL_ICONS: Record<StudioImageAiToolKind, ReactNode> = {
  hd: <StudioChromeIcon name="imageUpscale" fallback={<ThunderboltOutlined />} />,
  expand: <StudioChromeIcon name="imageExpand" fallback={<BorderOuterOutlined />} />,
  repaint: <StudioChromeIcon name="repaint" fallback={<EditOutlined />} />,
  erase: <StudioChromeIcon name="eraseRegion" fallback={<ClearOutlined />} />,
  matting: <StudioChromeIcon name="matting" fallback={<ScissorOutlined />} />,
  crop: <StudioChromeIcon name="crop" fallback={<ScissorOutlined />} />
}

export function StudioNodeMediaImageTools({ node }: { node: StudioCanvasNode }) {
  const canvasRuntime = useCanvasProjectRuntime()
  const openImageLighting = useStudioUiStore((state) => state.openImageLighting)
  const openImageMultiAngle = useStudioUiStore((state) => state.openImageMultiAngle)
  const openImageEditSession = useStudioUiStore((state) => state.openImageEditSession)
  const openTouchEditSession = useStudioUiStore((state) => state.openTouchEditSession)
  const openLayerSplitSession = useStudioUiStore((state) => state.openLayerSplitSession)
  const addNode = useStudioUiStore((state) => state.addNode)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const [panelOpen, setPanelOpen] = useState(false)
  const [toolsAnchor, setToolsAnchor] = useState<ToolsAnchor>('ai')

  const openToolsPanel = useCallback((anchor: ToolsAnchor) => {
    setToolsAnchor(anchor)
    setPanelOpen(true)
  }, [])

  useEffect(() => {
    const handleOpenTools = (event: Event) => {
      const detail = (event as CustomEvent<{ nodeId?: string; anchor?: ToolsAnchor }>).detail
      if (detail?.nodeId !== node.id) return
      openToolsPanel(detail.anchor === 'crop' ? 'crop' : 'ai')
    }
    const handleOpenLighting = (event: Event) => {
      const detail = (event as CustomEvent<{ nodeId?: string }>).detail
      if (detail?.nodeId !== node.id) return
      setPanelOpen(false)
      openImageLighting(node.id)
    }
    const handleOpenMultiAngle = (event: Event) => {
      const detail = (event as CustomEvent<{ nodeId?: string }>).detail
      if (detail?.nodeId !== node.id) return
      setPanelOpen(false)
      openImageMultiAngle(node.id)
    }
    window.addEventListener('studio:open-image-tools', handleOpenTools)
    window.addEventListener('studio:open-image-lighting', handleOpenLighting)
    window.addEventListener('studio:open-image-multi-angle', handleOpenMultiAngle)
    return () => {
      window.removeEventListener('studio:open-image-tools', handleOpenTools)
      window.removeEventListener('studio:open-image-lighting', handleOpenLighting)
      window.removeEventListener('studio:open-image-multi-angle', handleOpenMultiAngle)
    }
  }, [node.id, openImageLighting, openImageMultiAngle, openToolsPanel])

  const focusNode = useCallback((nodeId: string) => {
    window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId } }))
  }, [])

  const startHd = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    setPanelOpen(false)
    const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
    const desired = { x: node.position.x + 310, y: node.position.y }
    const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, occupied)
    const targetId = addNode('image', position, {
      title: '高清',
      subtitle: `由 ${node.data.title} 发起`,
      zoneId: node.data.zoneId,
      status: 'empty',
      mediaKind: 'image',
      imagePurpose: 'hd',
      prompt: '',
      composerHidden: true,
      storyboardId: node.data.storyboardId,
      composerRefs: [studioNodeToComposerRef(node)],
      imageToolOptions: {
        scale: 2,
        sourceNodeId: node.id
      },
      resultSummary: '配置参数生成高清图像'
    })
    addConnection({ source: node.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
    selectNodes([targetId])
    window.requestAnimationFrame(() => focusNode(targetId))
  }, [addConnection, addNode, focusNode, node, selectNodes])

  const startCrop = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    setPanelOpen(false)
    selectNodes([node.id])
    openImageEditSession({
      mode: 'crop',
      nodeId: node.id,
      aspectRatio: 'original',
      rect: fullCropRect()
    })
    window.requestAnimationFrame(() => focusNode(node.id))
  }, [focusNode, node, openImageEditSession, selectNodes])

  const describeImage = () => {
    const store = useStudioUiStore.getState()
    const source = store.nodes.find(item => item.id === node.id)
    if (!canvasRuntime?.canMutate || !canvasRuntime.canUseFeature('canvas.generate') || !source?.data.mediaUrl) return
    const position = placeStudioNodeWithoutOverlap({ x: source.position.x + 340, y: source.position.y },
      { width: 320, height: 228 }, store.nodes.map(toStudioNodeRect))
    const targetId = addNode('text', position, {
      title: `${source.data.title} · 图片描述`, mediaKind: 'text', zoneId: source.data.zoneId,
      status: 'empty', imageDescribe: { sourceNodeId: source.id },
      prompt: '客观描述图片中的主体、构图、色彩和光照；无法确认的内容不要推测。',
      composerRefs: [studioNodeToComposerRef(source)]
    }, { nodeId: source.id, role: 'source', kind: 'reference' })
    setPanelOpen(false)
    selectNodes([targetId])
    window.requestAnimationFrame(() => focusNode(targetId))
  }

  const startTouchEdit = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    setPanelOpen(false)
    selectNodes([node.id])
    openTouchEditSession({ nodeId: node.id })
    window.requestAnimationFrame(() => focusNode(node.id))
  }, [focusNode, node, openTouchEditSession, selectNodes])

  const startLayerSplit = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    if (canvasRuntime && (!canvasRuntime.canEdit || !canvasRuntime.canUseFeature('canvas.generate'))) {
      message.warning('当前账号不能使用画布图层分离')
      return
    }
    setPanelOpen(false)
    selectNodes([node.id])
    openLayerSplitSession({ nodeId: node.id })
    window.requestAnimationFrame(() => focusNode(node.id))
  }, [canvasRuntime, focusNode, node, openLayerSplitSession, selectNodes])

  const startExpand = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    setPanelOpen(false)
    selectNodes([node.id])
    openImageEditSession({
      mode: 'expand',
      nodeId: node.id,
      aspectRatio: 'original',
      rect: initialExpandRect(),
      showGrid: false
    })
    window.requestAnimationFrame(() => focusNode(node.id))
  }, [focusNode, node, openImageEditSession, selectNodes])

  const startMaskSession = useCallback((mode: 'erase' | 'repaint') => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    setPanelOpen(false)
    selectNodes([node.id])
    openImageEditSession({
      mode,
      nodeId: node.id,
      aspectRatio: node.data.generationOptions?.aspectRatio || '1:1',
      rect: fullCropRect(),
      maskTool: 'brush',
      brushSize: STUDIO_MASK_BRUSH_DEFAULT,
      repaintPrompt: mode === 'repaint' ? '' : undefined
    })
  }, [node, openImageEditSession, selectNodes])

  const startMatting = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    if (canvasRuntime && !canvasRuntime.canMutate) {
      message.error('当前画布不可编辑')
      return
    }
    const scopeKey = useStudioUiStore.getState().scopeKey
    void runStudioRequestSingleFlight(`${scopeKey}:${node.id}:image-matting`, async () => {
      setPanelOpen(false)
      const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
      const desired = { x: node.position.x + 310, y: node.position.y }
      const position = placeStudioNodeWithoutOverlap(desired, { width: 240, height: 180 }, occupied)
      const targetId = addNode('image', position, {
        title: '抠图',
        subtitle: `由 ${node.data.title} 抠图`,
        zoneId: node.data.zoneId,
        status: 'generating',
        mediaKind: 'image',
        imagePurpose: 'matting',
        prompt: node.data.prompt,
        storyboardId: node.data.storyboardId,
        composerHidden: true,
        canvasTransient: true,
        imageToolOptions: { sourceNodeId: node.id },
        resultSummary: '抠图处理中'
      })
      addConnection({ source: node.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
      selectNodes([targetId])
      window.requestAnimationFrame(() => focusNode(targetId))
      try {
        const file = await removeImageBackgroundToFile(node.data.mediaUrl!, `${node.data.title}-matting`)
        const beforeUpload = useStudioUiStore.getState()
        if (beforeUpload.scopeKey !== scopeKey || !beforeUpload.nodes.some((item) => item.id === targetId)) return
        const resource = canvasRuntime ? await canvasRuntime.uploadResource(file, 'IMAGE') : null
        const mediaUrl = resource?.resourceUrl || await uploadImageToOssWithToast(file, () => undefined)
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
        if (!mediaUrl) throw new Error('抠图结果上传失败')
        store.updateNodeData(targetId, {
          status: 'success',
          mediaUrl,
          mediaKind: 'image',
          ...(resource ? {
            canvasResourceIds: [String(resource.resourceId)],
            canvasPrimaryResourceId: String(resource.resourceId)
          } : {}),
          canvasTransient: false,
          errorMessage: undefined,
          resultSummary: '纯前端抠图（透明背景）'
        })
        if (canvasRuntime) await canvasRuntime.saveNow()
        message.success('抠图完成')
      } catch (error: unknown) {
        const store = useStudioUiStore.getState()
        if (store.scopeKey !== scopeKey || !store.nodes.some((item) => item.id === targetId)) return
        const errorMessage = (error as Error)?.message || '抠图失败，请重试'
        store.deleteNodes([targetId])
        message.error(errorMessage)
      }
    }).catch(() => undefined)
  }, [addConnection, addNode, canvasRuntime, focusNode, node, selectNodes])

  const runAiTool = useCallback((key: StudioImageAiToolKind) => {
    if (key === 'hd') return startHd()
    if (key === 'crop') return startCrop()
    if (key === 'expand') return startExpand()
    if (key === 'matting') return startMatting()
    if (key === 'erase') return startMaskSession('erase')
    if (key === 'repaint') return startMaskSession('repaint')
    message.info('该能力即将开放')
  }, [startCrop, startExpand, startHd, startMaskSession, startMatting])

  const lightingDisabled = !node.data.mediaUrl
  const multiAngleDisabled = !node.data.mediaUrl

  const aiMenuContent = (
    <StudioCanvasDropdownMenu
      ariaLabel="AI 工具"
      items={STUDIO_IMAGE_AI_TOOLS.map((tool) => ({
        key: tool.key,
        label: tool.label,
        icon: AI_TOOL_ICONS[tool.key],
        disabled: !tool.ready
      }))}
      onSelect={(key) => runAiTool(key as StudioImageAiToolKind)}
    />
  )

  return (
    <>
      <StudioCanvasToolbarButton
        showLabel
        label="裁剪"
        iconName="crop"
        icon={<ScissorOutlined aria-hidden />}
        disabled={!node.data.mediaUrl}
        onClick={startCrop}
      />
      <StudioCanvasToolbarButton
        showLabel
        label="元素编辑"
        iconName="touchEdit"
        icon={<AimOutlined aria-hidden />}
        disabled={!node.data.mediaUrl}
        onClick={startTouchEdit}
      />
      <StudioCanvasToolbarButton
        showLabel
        label="图层分离"
        iconName="layers"
        icon={<BlockOutlined aria-hidden />}
        disabled={!node.data.mediaUrl}
        onClick={startLayerSplit}
      />
      <StudioCanvasToolbarButton
        showLabel
        label="打光"
        iconName="sun"
        icon={<BgColorsOutlined aria-hidden />}
        disabled={lightingDisabled}
        onClick={() => {
          setPanelOpen(false)
          openImageLighting(node.id)
        }}
      />
      <StudioCanvasToolbarButton
        showLabel
        label="多角度"
        iconName="angle"
        icon={<RotateRightOutlined aria-hidden />}
        disabled={multiAngleDisabled}
        onClick={() => {
          setPanelOpen(false)
          openImageMultiAngle(node.id)
        }}
      />
      <StudioStoryboardCompositionMenu node={node} />
      {canvasRuntime ? <StudioCanvasToolbarButton showLabel label="图片描述" icon={<FileTextOutlined aria-hidden />}
        disabled={!node.data.mediaUrl || !canvasRuntime.canMutate || !canvasRuntime.canUseFeature('canvas.generate')}
        onClick={describeImage} /> : null}
      <ImageToolsPopover
        open={panelOpen && toolsAnchor === 'ai'}
        onOpenChange={(open) => {
          if (open) openToolsPanel('ai')
          else setPanelOpen(false)
        }}
        content={aiMenuContent}
      >
        <StudioCanvasToolbarButton
          showLabel
          label="AI 工具"
          iconName="sparkle"
          icon={<HighlightOutlined aria-hidden />}
          onClick={() => openToolsPanel('ai')}
        />
      </ImageToolsPopover>
    </>
  )
}

function ImageToolsPopover({
  open,
  onOpenChange,
  content,
  children
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  content: ReactNode
  children: ReactNode
}) {
  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={onOpenChange}
      trigger="click"
      placement="bottom"
      arrow={false}
      destroyOnHidden={false}
      getPopupContainer={(trigger) => trigger.closest('.studio-canvas') ?? document.body}
      classNames={{ root: IMAGE_TOOLS_POPOVER_CLASS }}
      content={content}
    >
      <span className="studio-node-media-toolbar__popover-anchor">{children}</span>
    </StudioCanvasPopover>
  )
}

export default StudioNodeMediaImageTools
