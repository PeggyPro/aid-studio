'use client'

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  BorderOutlined,
  CloseOutlined,
  ColumnWidthOutlined,
  HighlightOutlined,
  ClearOutlined,
  RedoOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { message, Slider } from 'antd'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { StudioCreditSendButton } from '@/components/studio/composer/StudioCreditSendButton'
import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { requestCanvasImageQuote, useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageEditSession, StudioImageMaskTool } from '@/types/studio'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import {
  STUDIO_CROP_ASPECT_RATIOS,
  STUDIO_EXPAND_COUNTS,
  STUDIO_EXPAND_QUALITIES,
  STUDIO_MASK_BRUSH_DEFAULT,
  STUDIO_MASK_BRUSH_MAX,
  STUDIO_MASK_BRUSH_MIN
} from '@/utils/studio/studioImageAiTools'
import { buildCanvasImageEditMaskFile, buildMaskedEditReferenceFile } from '@/utils/studio/studioImageMask'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import {
  buildCountSelectOptions,
  buildQualitySelectOptions,
  modelForCapability,
  parseModelCapability,
  pickMatchedOption,
  resolveModelSizeOptions
} from '@/utils/modelCapability'
import { applyRepaintResultToLayerSeparation } from '@/utils/studio/studioLayerSeparation'
import { buildCanvasImageEditOutputSettings } from '@/utils/studio/canvasImageEditSettings'
import { loadStudioCanvasImage } from '@/utils/studio/studioCanvasImageLoad'
import {
  getStudioMaskOverlay,
  subscribeStudioMaskOverlay
} from '@/utils/studio/studioMaskOverlayRegistry'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import '@/components/studio/composer/StudioImageLightingPanel.css'
import '@/components/studio/composer/studio-image-ai-tools.css'

function useMaskOverlay(nodeId: string) {
  const [version, setTick] = useState(0)
  useEffect(() => subscribeStudioMaskOverlay(() => setTick((value) => value + 1)), [])
  return { mask: getStudioMaskOverlay(nodeId), version }
}

function MaskToolButton({
  label,
  active,
  disabled,
  onClick,
  children
}: {
  label: string
  active?: boolean
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <StudioCanvasTooltip title={label} placement="bottom">
      <button
        type="button"
        className={`studio-image-tool-bar__tool${active ? ' is-active' : ''}`}
        aria-label={label}
        disabled={disabled}
        onClick={onClick}
      >
        {children}
      </button>
    </StudioCanvasTooltip>
  )
}

export function StudioImageMaskTopToolbar({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const closeImageEditSession = useStudioUiStore((state) => state.closeImageEditSession)
  const { mask } = useMaskOverlay(nodeId)

  if (!session || (session.mode !== 'erase' && session.mode !== 'repaint') || session.nodeId !== nodeId) {
    return null
  }

  const tool = session.maskTool ?? 'brush'
  const brushSize = session.brushSize ?? STUDIO_MASK_BRUSH_DEFAULT
  const title = session.mode === 'erase' ? '擦除' : '重绘'

  const setTool = (next: StudioImageMaskTool) => {
    patchImageEditSession({ maskTool: next })
  }

  return (
    <div className="studio-image-tool-bar studio-image-tool-bar--mask-top studio-image-tool-bar--chrome">
      <button type="button" aria-label={`关闭${title}`} onClick={() => closeImageEditSession()}>
        <CloseOutlined />
        <span>{title}</span>
      </button>
      <span className="studio-image-tool-bar__divider" />
      <MaskToolButton label="画笔" active={tool === 'brush'} onClick={() => setTool('brush')}>
        <HighlightOutlined />
      </MaskToolButton>
      <MaskToolButton label="矩形选区" active={tool === 'rect'} onClick={() => setTool('rect')}>
        <BorderOutlined />
      </MaskToolButton>
      <MaskToolButton label="橡皮擦" active={tool === 'eraser'} onClick={() => setTool('eraser')}>
        <ClearOutlined />
      </MaskToolButton>
      <span className="studio-image-tool-bar__divider" />
      <StudioCanvasTooltip title="笔刷粗细" placement="bottom">
        <div className="studio-image-tool-bar__brush studio-image-lighting-slider">
          <ColumnWidthOutlined aria-hidden />
          <div className="studio-image-lighting-slider__row">
            <Slider
              min={STUDIO_MASK_BRUSH_MIN}
              max={STUDIO_MASK_BRUSH_MAX}
              step={0.01}
              value={brushSize}
              tooltip={{ formatter: null }}
              onChange={(value) => patchImageEditSession({ brushSize: Number(value) })}
            />
          </div>
        </div>
      </StudioCanvasTooltip>
      <span className="studio-image-tool-bar__divider" />
      <MaskToolButton
        label="撤销"
        disabled={!mask?.canUndo()}
        onClick={() => { mask?.undo() }}
      >
        <UndoOutlined />
      </MaskToolButton>
      <MaskToolButton
        label="重做"
        disabled={!mask?.canRedo()}
        onClick={() => { mask?.redo() }}
      >
        <RedoOutlined />
      </MaskToolButton>
    </div>
  )
}

export function StudioImageMaskBottomToolbar({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  if (!session || (session.mode !== 'erase' && session.mode !== 'repaint') || session.nodeId !== nodeId || !node) {
    return null
  }
  return <StudioImageMaskBottomToolbarInner key={`${node.id}:${session.mode}`} node={node} session={session} />
}

function StudioImageMaskBottomToolbarInner({
  node,
  session
}: {
  node: StudioCanvasNode
  session: StudioImageEditSession
}) {
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const closeImageEditSession = useStudioUiStore((state) => state.closeImageEditSession)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const layerSeparation = node.data.layerSeparation
  const actions = useStudioImageActions(node, {
    resultNodeId: layerSeparation ? node.id : null
  })
  const canvasEdit = useCanvasImageEdit(node)
  const { mask, version: maskVersion } = useMaskOverlay(node.id)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [sourceResource, setSourceResource] = useState<{ key: string; resourceId: string } | null>(null)
  const [quoteMaskResource, setQuoteMaskResource] = useState<{ key: string; resourceId: string } | null>(null)
  const [sourceSize, setSourceSize] = useState<{ width: number; height: number } | null>(null)
  const [wholeImage, setWholeImage] = useState(false)
  const [modelOpen, setModelOpen] = useState(false)
  const [ratioOpen, setRatioOpen] = useState(false)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [countOpen, setCountOpen] = useState(false)

  type ImageToolOptions = NonNullable<StudioCanvasNode['data']['imageToolOptions']>
  const [options, setOptions] = useState<ImageToolOptions>(() => ({
    provider: 'PRO',
    aspectRatio: session.aspectRatio ?? node.data.generationOptions?.aspectRatio ?? '1:1',
    quality: node.data.generationOptions?.quality ?? '2K',
    count: 1
  }))
  const model = options.provider ?? 'PRO'
  const aspectRatio = options.aspectRatio ?? session.aspectRatio ?? '1:1'
  const quality = options.quality ?? '2K'
  const count = options.count ?? 1
  const prompt = session.repaintPrompt ?? ''
  const credits = 10 + count * 4
  const isRepaint = session.mode === 'repaint'
  const funcCode = isRepaint ? 'canvas.image.redraw' : 'canvas.image.erase'
  const capabilityCode = isRepaint && wholeImage ? 'image_edit' : 'image_inpainting'
  const canvasSelection = canvasEdit.select(funcCode, capabilityCode, options.modelCode)
  const {
    active: canvasEditActive,
    project: canvasEditProject,
    ensureSourceResource,
    uploadResource
  } = canvasEdit
  const compatibleModels = canvasSelection.models
  const canvasModel = compatibleModels.find((item) => item.modelCode === canvasSelection.model?.modelCode)
    ?? compatibleModels[0]
  const canvasCapabilityModel = modelForCapability(canvasModel, capabilityCode)
  const canvasCapability = parseModelCapability(canvasCapabilityModel)
  const canvasQualityOptions = resolveModelSizeOptions(canvasCapabilityModel).length
    ? buildQualitySelectOptions(canvasCapability)
    : []
  const canvasCountOptions = buildCountSelectOptions(canvasCapability)
  const canvasQuality = pickMatchedOption(quality, canvasQualityOptions.map((item) => item.value), canvasCapability.defaultSize)
  const canvasCount = canvasCountOptions.some((item) => item.value === count)
    ? count
    : canvasCountOptions.find((item) => item.value === canvasCapability.defaultOutputCount)?.value ?? canvasCountOptions[0]?.value ?? 1
  const canvasRequestSettings = buildCanvasImageEditOutputSettings({
    operation: isRepaint ? 'REDRAW' : 'ERASE',
    prompt,
    quality: canvasQuality,
    count: canvasCount,
    protectedCanvas: wholeImage && isRepaint ? false : true
  })
  const quoteMaskKey = `${node.id}:${session.mode}:${node.data.mediaUrl ?? ''}:${maskVersion}`
  useEffect(() => {
    if (!canvasEdit.active || !node.data.mediaUrl) return
    let active = true
    setSourceSize(null)
    void loadStudioCanvasImage(node.data.mediaUrl).then((image) => {
      if (active) setSourceSize({ width: image.width, height: image.height })
      image.dispose()
    }).catch(() => { if (active) setSourceSize(null) })
    return () => { active = false }
  }, [canvasEdit.active, node.data.mediaUrl])
  const sourceResourceKey = canvasEditActive && canvasEditProject && node.data.mediaUrl
    ? `${canvasEditProject.workspace.workspaceId}:${node.id}:${node.data.mediaUrl}`
    : ''
  const sourceResourceId = sourceResource?.key === sourceResourceKey ? sourceResource.resourceId : ''

  useEffect(() => {
    if (!sourceResourceKey) return
    let active = true
    void ensureSourceResource().then((resource) => {
      if (active) setSourceResource({ key: sourceResourceKey, resourceId: String(resource.resourceId) })
    }).catch(() => {
      if (active) setSourceResource(null)
    })
    return () => { active = false }
  }, [ensureSourceResource, sourceResourceKey])

  useEffect(() => {
    if (!canvasEditActive || !uploadResource || wholeImage || !mask?.hasPaint()
      || quoteMaskResource?.key === quoteMaskKey) return
    const maskCanvas = mask.getMaskCanvas()
    if (!maskCanvas) return
    let active = true
    const timer = window.setTimeout(() => {
      void buildCanvasImageEditMaskFile(node.data.mediaUrl!, maskCanvas)
        .then((file) => uploadResource(file, 'IMAGE'))
        .then((resource) => {
          if (active) setQuoteMaskResource({ key: quoteMaskKey, resourceId: String(resource.resourceId) })
        })
        .catch(() => undefined)
    }, 360)
    return () => { active = false; window.clearTimeout(timer) }
  }, [canvasEditActive, mask, maskVersion, node.data.mediaUrl, quoteMaskKey, quoteMaskResource?.key, uploadResource, wholeImage])

  const quoteMaskResourceId = wholeImage && isRepaint
    ? ''
    : quoteMaskResource?.key === quoteMaskKey ? quoteMaskResource.resourceId : ''
  const quoteRequest = canvasEdit.active && canvasEdit.project && canvasModel && sourceResourceId
    && (!isRepaint || Boolean(prompt.trim()))
    && (wholeImage && isRepaint || quoteMaskResourceId) ? {
      modelName: canvasModel.modelCode,
      funcCode,
      capabilityCode,
      projectId: String(canvasEdit.project.projectId),
      workspaceId: String(canvasEdit.project.workspace.workspaceId),
      clientRequestId: 'image-edit-quote',
      ...canvasRequestSettings,
      imageEdit: {
        operation: isRepaint ? 'REDRAW' as const : 'ERASE' as const,
        sourceResourceId,
        ...(quoteMaskResourceId ? { maskResourceId: quoteMaskResourceId, maskEncoding: 'BLACK_WHITE' as const } : {})
      }
    } : null
  const canvasQuote = useCanvasImageQuote(quoteRequest)
  const quotedCredits = studioCanvasQuoteCredits(canvasQuote.quote)
  const displayedCredits = quotedCredits ?? 0

  const patchOptions = useCallback((patch: Partial<ImageToolOptions>) => {
    setOptions((current) => ({ ...current, ...patch }))
  }, [])

  const modelItems = useMemo(() => [
    { key: 'PRO', label: 'PRO' },
    { key: 'Topazlabs', label: 'Topazlabs' }
  ], [])

  const ratioItems = useMemo(() => STUDIO_CROP_ASPECT_RATIOS
    .filter((item) => item.value !== 'custom' && item.value !== 'original')
    .map((item) => ({ key: item.value, label: item.label })), [])

  const qualityItems = useMemo(() => STUDIO_EXPAND_QUALITIES.map((item) => ({
    key: item.value,
    label: item.label
  })), [])

  const countItems = useMemo(() => STUDIO_EXPAND_COUNTS.map((item) => ({
    key: String(item),
    label: `${item}张`
  })), [])

  const ratioLabel = STUDIO_CROP_ASPECT_RATIOS.find((item) => item.value === aspectRatio)?.label
    ?? aspectRatio

  const submit = async () => {
    if (busyRef.current) return
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源图片')
      return
    }
    if (!(canvasEdit.active && wholeImage && isRepaint) && !mask?.hasPaint()) {
      message.warning(isRepaint ? '请先涂抹需要重绘的区域' : '请先涂抹需要擦除的区域')
      return
    }
    if (isRepaint && !prompt.trim()) {
      message.warning('请填写重绘描述')
      return
    }
    const maskCanvas = mask?.getMaskCanvas()
    if (!(canvasEdit.active && wholeImage && isRepaint) && !maskCanvas) {
      message.warning('蒙版尚未就绪，请稍后再试')
      return
    }
    busyRef.current = true
    setBusy(true)
    let pendingNodeId = ''
    try {
      if (canvasEdit.active) {
        if (canvasEdit.disabledReason || !canvasModel || !canvasEdit.project || !canvasEdit.uploadResource) throw new Error(canvasEdit.disabledReason || canvasEdit.error || '当前工具没有可用的图片编辑模型')
        pendingNodeId = canvasEdit.reserve({
          modelCode: canvasModel.modelCode, funcCode, capabilityCode,
          expectedImageCount: canvasRequestSettings.expectedImageCount,
          label: isRepaint ? '重绘' : '擦除', purpose: isRepaint ? 'repaint' : 'erase',
          prompt: canvasRequestSettings.prompt
        })
        closeImageEditSession(session)
        const source = sourceResourceId || String((await canvasEdit.ensureSourceResource()).resourceId)
        const maskResource = wholeImage && isRepaint ? null : await canvasEdit.uploadResource(await buildCanvasImageEditMaskFile(node.data.mediaUrl, maskCanvas!), 'IMAGE')
        const request = {
          modelName: canvasModel.modelCode,
          funcCode,
          capabilityCode,
          projectId: String(canvasEdit.project.projectId),
          workspaceId: String(canvasEdit.project.workspace.workspaceId),
          clientRequestId: 'image-edit-quote',
          ...canvasRequestSettings,
          imageEdit: {
            operation: isRepaint ? 'REDRAW' as const : 'ERASE' as const,
            sourceResourceId: source,
            ...(maskResource ? { maskResourceId: String(maskResource.resourceId), maskEncoding: 'BLACK_WHITE' as const } : {})
          }
        }
        if (!canvasQuote.quote) await requestCanvasImageQuote(request)
        const ok = await canvasEdit.submit({
          modelCode: canvasModel.modelCode,
          funcCode,
          capabilityCode,
          imageEdit: {
            operation: isRepaint ? 'REDRAW' : 'ERASE',
            ...(maskResource ? { maskResourceId: String(maskResource.resourceId), maskEncoding: 'BLACK_WHITE' as const } : {})
          },
          expectedImageCount: canvasRequestSettings.expectedImageCount,
          ...(canvasRequestSettings.size ? { size: canvasRequestSettings.size } : {}),
          label: isRepaint ? '重绘' : '擦除',
          purpose: isRepaint ? 'repaint' : 'erase',
          prompt: canvasRequestSettings.prompt,
          targetId: pendingNodeId
        })
        if (!ok) useStudioUiStore.getState().updateNodeData(pendingNodeId, { status: 'failed', errorMessage: '图片编辑提交失败' })
        return
      }
      const file = await buildMaskedEditReferenceFile(
        node.data.mediaUrl,
        maskCanvas!,
        `${node.data.title}-${session.mode}`
      )
      const markedUrl = await uploadImageToOssWithToast(file, () => undefined)
      if (!markedUrl) return

      updateNodeData(node.id, {
        generationOptions: {
          ...(node.data.generationOptions ?? {}),
          aspectRatio,
          quality,
          count
        },
        imageToolOptions: { ...options, aspectRatio, quality, count, sourceNodeId: node.id }
      })

      const editPrompt = isRepaint
        ? `请仅重绘图中品红色半透明标记区域：${prompt.trim()}。保持未标记区域完全不变，风格与光影连贯。清晰度 ${quality}，比例 ${ratioLabel}。`
        : `请擦除图中品红色半透明标记区域的内容，自然修补背景与周围纹理，不要改变未标记区域。清晰度 ${quality}，比例 ${ratioLabel}。`
      const ok = await actions.edit(
        editPrompt,
        isRepaint ? '重绘' : '擦除',
        isRepaint ? 'repaint' : 'erase',
        { referenceImage: markedUrl }
      )
      if (ok) {
        if (isRepaint && layerSeparation) {
          const latest = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
          const resultUrl = latest?.data.mediaUrl
          if (latest?.data.layerSeparation && resultUrl) {
            const nextSeparation = applyRepaintResultToLayerSeparation(latest.data.layerSeparation, resultUrl)
            if (nextSeparation) {
              updateNodeData(node.id, {
                mediaUrl: nextSeparation.backgroundUrl,
                layerSeparation: nextSeparation
              })
            }
          }
        }
        closeImageEditSession(session)
      }
    } catch (error: unknown) {
      if (pendingNodeId) {
        canvasEdit.release(pendingNodeId)
        useStudioUiStore.getState().updateNodeData(pendingNodeId, { status: 'failed', errorMessage: (error as Error)?.message || '图片编辑提交失败' })
      }
      message.error((error as Error)?.message || (isRepaint ? '重绘失败' : '擦除失败'))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  const paramsRow = (
    <>
      {canvasEdit.active ? <>
        <StudioCanvasDropdownPopover
          open={modelOpen}
          onOpenChange={setModelOpen}
          placement="top"
          ariaLabel="图片编辑模型"
          activeKey={canvasModel?.modelCode}
          items={compatibleModels.map((item) => ({ key: item.modelCode, label: `${item.modelName}${item.providerName ? ` · ${item.providerName}` : ''}` }))}
          onSelect={(key) => patchOptions({ modelCode: key })}
          trigger={<StudioComposerChip label={canvasModel?.modelName ?? (canvasEdit.loading || !sourceSize ? '读取模型中' : '当前原图尺寸没有兼容模型')} active={modelOpen} />}
        />
        {wholeImage && isRepaint && canvasQualityOptions.length ? <StudioCanvasDropdownPopover
          open={qualityOpen}
          onOpenChange={setQualityOpen}
          placement="top"
          ariaLabel="清晰度"
          activeKey={canvasQuality}
          items={canvasQualityOptions.map((item) => ({ key: item.value, label: item.label }))}
          onSelect={(key) => patchOptions({ quality: key })}
          trigger={<StudioComposerChip label={canvasQualityOptions.find((item) => item.value === canvasQuality)?.label ?? canvasQuality} active={qualityOpen} />}
        /> : null}
        <StudioCanvasDropdownPopover
          open={countOpen}
          onOpenChange={setCountOpen}
          placement="top"
          ariaLabel="生成数量"
          activeKey={String(canvasCount)}
          items={canvasCountOptions.map((item) => ({ key: String(item.value), label: item.label }))}
          onSelect={(key) => patchOptions({ count: Number(key) })}
          trigger={<StudioComposerChip label={`${canvasCount}张`} active={countOpen} />}
        />
        <StudioCreditSendButton
          credits={displayedCredits}
          isFree={canvasQuote.quote?.isFree === true}
          authoritative={quotedCredits != null}
          pendingQuote={quotedCredits == null}
          estimateLoading={canvasQuote.loading}
          showCredits={Boolean(canvasModel)}
          estimateError={canvasQuote.error || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : '')}
          quoteStatus={canvasQuote.status}

          hasContent={isRepaint ? Boolean(prompt.trim()) : true}
          mode="agent"
          loading={busy}
          disabled={Boolean(canvasEdit.disabledReason) || !canvasModel || canvasQuote.quote?.matched === false || (!(wholeImage && isRepaint) && !mask?.hasPaint())}
          disabledReason={canvasEdit.disabledReason || (!canvasModel ? (canvasEdit.error || (!sourceSize ? '正在读取原图尺寸' : '当前原图尺寸没有兼容模型')) : canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : !(wholeImage && isRepaint) && !mask?.hasPaint() ? '请先涂抹编辑区域' : undefined)}
          className="studio-composer__send"
          onSend={() => { void submit() }}
        />
      </> : <>
      <StudioCanvasDropdownPopover
        open={modelOpen}
        onOpenChange={setModelOpen}
        placement="top"
        ariaLabel="模型"
        activeKey={model}
        items={modelItems}
        onSelect={(key) => patchOptions({ provider: key })}
        trigger={<StudioComposerChip label={model} active={modelOpen} />}
      />
      <StudioCanvasDropdownPopover
        open={ratioOpen}
        onOpenChange={setRatioOpen}
        placement="top"
        ariaLabel="宽高比"
        activeKey={aspectRatio}
        items={ratioItems}
        onSelect={(key) => {
          patchOptions({ aspectRatio: key })
          patchImageEditSession({ aspectRatio: key })
        }}
        trigger={(
          <StudioComposerChip
            icon={<ColumnWidthOutlined />}
            label={ratioLabel}
            active={ratioOpen}
          />
        )}
      />
      <StudioCanvasDropdownPopover
        open={qualityOpen}
        onOpenChange={setQualityOpen}
        placement="top"
        ariaLabel="清晰度"
        activeKey={quality}
        items={qualityItems}
        onSelect={(key) => patchOptions({ quality: key })}
        trigger={<StudioComposerChip label={quality} active={qualityOpen} />}
      />
      <StudioCanvasDropdownPopover
        open={countOpen}
        onOpenChange={setCountOpen}
        placement="top"
        ariaLabel="生成数量"
        activeKey={String(count)}
        items={countItems}
        onSelect={(key) => patchOptions({ count: Number(key) })}
        trigger={<StudioComposerChip label={`${count}张`} active={countOpen} />}
      />
      <StudioCreditSendButton
        credits={credits}
        isFree={false}
        showCredits
        hasContent={isRepaint ? Boolean(prompt.trim()) : true}
        mode="agent"
        loading={busy}
        className="studio-composer__send"
        onSend={() => { void submit() }}
      />
      </>}
    </>
  )

  if (!isRepaint) {
    return (
      <div className="studio-image-tool-bar studio-image-tool-bar--mask-bottom studio-image-tool-bar--expand studio-image-tool-bar--chrome">
        <div className="studio-image-mask-bottom__params">
          {paramsRow}
        </div>
      </div>
    )
  }

  return (
    <div className="studio-image-mask-bottom studio-image-mask-bottom--repaint studio-image-tool-bar--chrome nodrag nowheel">
      {canvasEdit.active ? <button type="button" aria-pressed={wholeImage} onClick={() => setWholeImage((current) => !current)}>{wholeImage ? '整图重绘' : '区域重绘'}</button> : null}
      <textarea
        className="studio-image-mask-bottom__prompt"
        rows={2}
        maxLength={1200}
        placeholder="开始你的设计"
        value={prompt}
        onChange={(event) => patchImageEditSession({ repaintPrompt: event.target.value })}
      />
      <div className="studio-image-mask-bottom__params">
        {paramsRow}
      </div>
    </div>
  )
}
