'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import {
  CloseOutlined,
  ColumnWidthOutlined
} from '@ant-design/icons'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { StudioCreditSendButton } from '@/components/studio/composer/StudioCreditSendButton'
import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { requestCanvasImageQuote, useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { loadStudioCanvasImage } from '@/utils/studio/studioCanvasImageLoad'
import { buildCanvasImageEditOutputSettings } from '@/utils/studio/canvasImageEditSettings'
import {
  buildCountSelectOptions,
  modelForCapability,
  parseModelCapability
} from '@/utils/modelCapability'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageEditSession, StudioImageFineness } from '@/types/studio'
import {
  STUDIO_CROP_ASPECT_RATIOS,
  STUDIO_EXPAND_COUNTS,
  STUDIO_EXPAND_FINENESS,
  STUDIO_EXPAND_QUALITIES,
  STUDIO_HD_PROVIDERS,
  applyExpandHandleDelta,
  centeredExpandRect,
  parseAspectRatio,
  type StudioCropHandle
} from '@/utils/studio/studioImageAiTools'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioImageExpandParamSelect } from './StudioImageExpandParamSelect'
import { StudioImageToolParamPopover } from './StudioImageToolParamPopover'
import '@/components/studio/composer/studio-image-ai-tools.css'

const HANDLES: StudioCropHandle[] = ['nw', 'n', 'ne', 'w', 'e', 'sw', 's', 'se']

export function StudioImageExpandOverlay({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  if (!session || session.mode !== 'expand' || session.nodeId !== nodeId || !node) return null
  return <StudioImageExpandOverlayInner node={node} session={session} />
}

function StudioImageExpandOverlayInner({
  node,
  session
}: {
  node: StudioCanvasNode
  session: StudioImageEditSession
}) {
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const dragRef = useRef<{
    handle: StudioCropHandle
    startX: number
    startY: number
    rect: StudioImageEditSession['rect']
  } | null>(null)

  const rect = session.rect
  const showGrid = Boolean(session.showGrid)

  useEffect(() => {
    const onMove = (event: PointerEvent) => {
      const drag = dragRef.current
      if (!drag) return
      const host = document.querySelector(
        `.react-flow__node[data-id="${CSS.escape(node.id)}"] .studio-image-expand-overlay`
      ) as HTMLElement | null
      if (!host) return
      const box = host.getBoundingClientRect()
      const dx = (event.clientX - drag.startX) / Math.max(1, box.width)
      const dy = (event.clientY - drag.startY) / Math.max(1, box.height)
      const next = applyExpandHandleDelta(drag.rect, drag.handle, dx, dy, parseAspectRatio(session.aspectRatio))
      patchImageEditSession({ rect: next, showGrid: true })
    }
    const onUp = () => {
      dragRef.current = null
      patchImageEditSession({ showGrid: false })
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [node.id, patchImageEditSession, session.aspectRatio])

  const beginDrag = useCallback((handle: StudioCropHandle, event: React.PointerEvent) => {
    event.preventDefault()
    event.stopPropagation()
    dragRef.current = {
      handle,
      startX: event.clientX,
      startY: event.clientY,
      rect: { ...session.rect }
    }
    patchImageEditSession({ showGrid: true })
  }, [patchImageEditSession, session.rect])

  return (
    <div className={`studio-image-expand-overlay nodrag nopan${showGrid ? ' is-dragging' : ''}`} aria-label="扩图框">
      <div
        className="studio-image-expand-overlay__frame"
        style={{
          left: `${rect.x * 100}%`,
          top: `${rect.y * 100}%`,
          width: `${rect.w * 100}%`,
          height: `${rect.h * 100}%`
        }}
      >
        <div className="studio-image-expand-overlay__pad" aria-hidden>
          <i
            className="studio-image-expand-overlay__source"
            style={{
              left: `${(-rect.x / Math.max(rect.w, 0.001)) * 100}%`,
              top: `${(-rect.y / Math.max(rect.h, 0.001)) * 100}%`,
              width: `${(1 / Math.max(rect.w, 0.001)) * 100}%`,
              height: `${(1 / Math.max(rect.h, 0.001)) * 100}%`
            }}
          />
          {showGrid ? <i className="studio-image-expand-overlay__grid" /> : null}
        </div>
        {HANDLES.map((handle) => (
          <button
            key={handle}
            type="button"
            className={`studio-image-expand-overlay__handle is-${handle}`}
            aria-label={`拖拽扩图${handle}`}
            onPointerDown={(event) => beginDrag(handle, event)}
          />
        ))}
      </div>
    </div>
  )
}

export function StudioImageExpandToolbar({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.imageEditSession)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  if (!session || session.mode !== 'expand' || session.nodeId !== nodeId || !node) return null
  return <StudioImageExpandToolbarInner key={node.id} node={node} session={session} />
}

function StudioImageExpandToolbarInner({
  node,
  session
}: {
  node: StudioCanvasNode
  session: StudioImageEditSession
}) {
  const patchImageEditSession = useStudioUiStore((state) => state.patchImageEditSession)
  const closeImageEditSession = useStudioUiStore((state) => state.closeImageEditSession)
  const actions = useStudioImageActions(node)
  const canvasEdit = useCanvasImageEdit(node)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [sourceSize, setSourceSize] = useState<{ width: number; height: number } | null>(null)
  const [sourceResource, setSourceResource] = useState<{ key: string; resourceId: string } | null>(null)
  const [modelOpen, setModelOpen] = useState(false)
  const [ratioOpen, setRatioOpen] = useState(false)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [countOpen, setCountOpen] = useState(false)
  const [finenessOpen, setFinenessOpen] = useState(false)

  type ImageToolOptions = NonNullable<StudioCanvasNode['data']['imageToolOptions']>
  const [options, setOptions] = useState<ImageToolOptions>(() => ({
    provider: 'PRO',
    aspectRatio: 'original',
    quality: '2K',
    count: 1,
    fineness: 'medium'
  }))
  const model = options.provider ?? 'PRO'
  const aspectRatio = session.aspectRatio ?? options.aspectRatio ?? 'original'
  const quality = options.quality ?? '2K'
  const count = options.count ?? 1
  const fineness = options.fineness ?? 'medium'
  const credits = 10 + count * 4 + (fineness === 'high' ? 6 : fineness === 'medium' ? 3 : 0)
  const target = useMemo(() => sourceSize ? {
    targetWidth: Math.round(sourceSize.width * session.rect.w),
    targetHeight: Math.round(sourceSize.height * session.rect.h),
    sourceX: Math.round(-session.rect.x * sourceSize.width),
    sourceY: Math.round(-session.rect.y * sourceSize.height)
  } : null, [session.rect.h, session.rect.w, session.rect.x, session.rect.y, sourceSize])
  const canvasSelection = canvasEdit.select('canvas.image.expand', 'image_outpainting', options.modelCode)
  const {
    active: canvasEditActive,
    project: canvasEditProject,
    ensureSourceResource
  } = canvasEdit
  const compatibleModels = target ? canvasSelection.models : []
  const canvasModel = compatibleModels.find((item) => item.modelCode === canvasSelection.model?.modelCode)
    ?? compatibleModels[0]
  const canvasCapabilityModel = modelForCapability(canvasModel, 'image_outpainting')
  const canvasCapability = parseModelCapability(canvasCapabilityModel)
  const canvasCountOptions = buildCountSelectOptions(canvasCapability)
  const canvasCount = canvasCountOptions.some((item) => item.value === count)
    ? count
    : canvasCountOptions.find((item) => item.value === canvasCapability.defaultOutputCount)?.value ?? canvasCountOptions[0]?.value ?? 1
  const targetError = !target ? '正在读取原图尺寸'
    : target.targetWidth < 1 || target.targetHeight < 1 || target.sourceX < 0 || target.sourceY < 0
      || target.sourceX + sourceSize!.width > target.targetWidth || target.sourceY + sourceSize!.height > target.targetHeight
      || (target.targetWidth === sourceSize!.width && target.targetHeight === sourceSize!.height)
      || target.targetWidth > 32000 || target.targetHeight > 32000 || target.targetWidth * target.targetHeight > 20_000_000
      ? '扩图边界必须包含原图、至少扩展一边，且不超过 2000 万像素' : ''
  const canvasRequestSettings = buildCanvasImageEditOutputSettings({
    operation: 'EXPAND',
    count: canvasCount,
    target,
    finenessLabel: STUDIO_EXPAND_FINENESS.find((item) => item.value === fineness)?.label
  })
  const sourceResourceKey = canvasEditActive && canvasEditProject && node.data.mediaUrl
    ? `${canvasEditProject.workspace.workspaceId}:${node.id}:${node.data.mediaUrl}`
    : ''
  const sourceResourceId = sourceResource?.key === sourceResourceKey ? sourceResource.resourceId : ''
  useEffect(() => {
    if (!canvasEdit.active || !node.data.mediaUrl) return
    let active = true
    void loadStudioCanvasImage(node.data.mediaUrl).then((image) => {
      if (active) setSourceSize({ width: image.width, height: image.height })
      image.dispose()
    }).catch(() => { if (active) setSourceSize(null) })
    return () => { active = false }
  }, [canvasEdit.active, node.data.mediaUrl])

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

  const quoteRequest = canvasEdit.active && canvasEdit.project && canvasModel && target && !targetError && sourceResourceId ? {
    modelName: canvasModel.modelCode,
    funcCode: 'canvas.image.expand',
    capabilityCode: 'image_outpainting',
    projectId: String(canvasEdit.project.projectId),
    workspaceId: String(canvasEdit.project.workspace.workspaceId),
    clientRequestId: 'image-expand-quote',
    ...canvasRequestSettings,
    imageEdit: { operation: 'EXPAND' as const, sourceResourceId, expand: target }
  } : null
  const canvasQuote = useCanvasImageQuote(quoteRequest)
  const quotedCredits = studioCanvasQuoteCredits(canvasQuote.quote)
  const displayedCredits = quotedCredits ?? 0

  const patchOptions = useCallback((patch: Partial<ImageToolOptions>) => {
    setOptions((current) => ({ ...current, ...patch }))
  }, [])

  const ratioOptions = useMemo(
    () => STUDIO_CROP_ASPECT_RATIOS
      .filter((item) => item.value !== 'custom')
      .map((item) => ({ value: item.value, label: item.label })),
    []
  )
  const qualityOptions = useMemo(
    () => STUDIO_EXPAND_QUALITIES.map((item) => ({ value: item.value, label: item.label })),
    []
  )
  const countOptions = useMemo(
    () => STUDIO_EXPAND_COUNTS.map((item) => ({ value: String(item), label: `${item}张` })),
    []
  )

  const submit = async () => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    let pendingNodeId = ''
    try {
      if (canvasEdit.active) {
        if (canvasEdit.disabledReason || !canvasModel || !canvasEdit.project || !target || targetError) throw new Error(canvasEdit.disabledReason || targetError || canvasEdit.error || '暂无可用的扩图模型')
        pendingNodeId = canvasEdit.reserve({
          modelCode: canvasModel.modelCode, funcCode: 'canvas.image.expand', capabilityCode: 'image_outpainting',
          expectedImageCount: canvasRequestSettings.expectedImageCount,
          label: '扩图', purpose: 'outpaint', prompt: canvasRequestSettings.prompt
        })
        closeImageEditSession()
        const source = sourceResourceId || String((await canvasEdit.ensureSourceResource()).resourceId)
        const request = quoteRequest ?? {
          modelName: canvasModel.modelCode, funcCode: 'canvas.image.expand', capabilityCode: 'image_outpainting',
          projectId: String(canvasEdit.project.projectId), workspaceId: String(canvasEdit.project.workspace.workspaceId),
          clientRequestId: 'image-expand-quote', ...canvasRequestSettings,
          imageEdit: { operation: 'EXPAND' as const, sourceResourceId: source, expand: target }
        }
        if (!canvasQuote.quote) await requestCanvasImageQuote(request)
        const ok = await canvasEdit.submit({
          modelCode: canvasModel.modelCode, funcCode: 'canvas.image.expand', capabilityCode: 'image_outpainting',
          imageEdit: { operation: 'EXPAND', expand: target }, expectedImageCount: canvasRequestSettings.expectedImageCount,
          label: '扩图', purpose: 'outpaint', prompt: canvasRequestSettings.prompt, targetId: pendingNodeId
        })
        if (!ok) useStudioUiStore.getState().updateNodeData(pendingNodeId, { status: 'failed', errorMessage: '扩图提交失败' })
        return
      }
      const finenessLabel = STUDIO_EXPAND_FINENESS.find((item) => item.value === fineness)?.label ?? '中'
      const ratioLabel = STUDIO_CROP_ASPECT_RATIOS.find((item) => item.value === aspectRatio)?.label ?? '原图比例'
      const ok = await actions.edit(
        `对图片进行扩图，目标比例 ${ratioLabel}，清晰度 ${quality}，精细度${finenessLabel}，自然延展背景与边缘，保持主体一致。`,
        '扩图',
        'outpaint'
      )
      if (ok) closeImageEditSession(session)
    } catch (reason: unknown) {
      if (pendingNodeId) {
        canvasEdit.release(pendingNodeId)
        useStudioUiStore.getState().updateNodeData(pendingNodeId, { status: 'failed', errorMessage: reason instanceof Error ? reason.message : '扩图提交失败' })
      }
      message.error(reason instanceof Error ? reason.message : '扩图提交失败')
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  const modelItems = [
    ...STUDIO_HD_PROVIDERS.map((item) => ({ key: item.value, label: item.label })),
    { key: 'PRO', label: 'PRO' }
  ]

  const ratioLabel = STUDIO_CROP_ASPECT_RATIOS.find((item) => item.value === aspectRatio)?.label ?? '原图比例'
  const finenessLabel = STUDIO_EXPAND_FINENESS.find((item) => item.value === fineness)?.label ?? '中'

  const closeOthers = (keep: 'model' | 'ratio' | 'quality' | 'count' | 'fineness') => {
    if (keep !== 'model') setModelOpen(false)
    if (keep !== 'ratio') setRatioOpen(false)
    if (keep !== 'quality') setQualityOpen(false)
    if (keep !== 'count') setCountOpen(false)
    if (keep !== 'fineness') setFinenessOpen(false)
  }

  return (
    <div className="studio-image-tool-bar studio-image-tool-bar--expand studio-image-tool-bar--chrome">
      <button type="button" aria-label="取消扩图" onClick={() => closeImageEditSession()}>
        <CloseOutlined />
      </button>
      <span className="studio-image-tool-bar__divider" />
      {canvasEdit.active ? <>
        <StudioCanvasDropdownPopover
          open={modelOpen} onOpenChange={setModelOpen} placement="top" ariaLabel="扩图模型"
          activeKey={canvasModel?.modelCode ?? ''}
          items={compatibleModels.map((item) => ({ key: item.modelCode, label: `${item.modelName}${item.providerName ? ` · ${item.providerName}` : ''}` }))}
          onSelect={(key) => patchOptions({ modelCode: key })}
          trigger={<StudioComposerChip label={canvasModel?.modelName ?? (canvasEdit.loading || !target ? '读取模型中' : '当前目标尺寸没有兼容模型')} active={modelOpen} />}
        />
        <StudioCanvasDropdownPopover
          open={countOpen} onOpenChange={setCountOpen} placement="top" ariaLabel="扩图张数"
          activeKey={String(canvasCount)}
          items={canvasCountOptions.map((item) => ({ key: String(item.value), label: item.label }))}
          onSelect={(key) => patchOptions({ count: Number(key) })}
          trigger={<StudioComposerChip label={`${canvasCount}张`} active={countOpen} />}
        />
        <span role="status">{target ? `${target.targetWidth} × ${target.targetHeight}` : '读取原图中'}</span>
        {targetError && target ? <span role="alert">{targetError}</span> : null}
        <StudioCreditSendButton
          credits={displayedCredits}
          isFree={canvasQuote.quote?.isFree === true}
          authoritative={quotedCredits != null}
          pendingQuote={quotedCredits == null}
          estimateLoading={canvasQuote.loading}
          showCredits={Boolean(canvasModel)}
          estimateError={canvasQuote.error || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : '')}
          quoteStatus={canvasQuote.status}

          hasContent mode="agent" loading={busy} className="studio-composer__send"
          disabled={Boolean(canvasEdit.disabledReason) || !canvasModel || Boolean(targetError) || canvasQuote.quote?.matched === false}
          disabledReason={canvasEdit.disabledReason || (!canvasModel ? (canvasEdit.error || (!target ? '正在读取原图尺寸' : '当前目标尺寸没有兼容模型')) : targetError || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : undefined))}
          onSend={() => { void submit() }}
        />
      </> : <>
      <StudioCanvasDropdownPopover
        open={modelOpen}
        onOpenChange={(open) => {
          closeOthers('model')
          setModelOpen(open)
        }}
        placement="top"
        ariaLabel="模型"
        activeKey={model}
        items={modelItems}
        onSelect={(key) => patchOptions({ provider: key })}
        trigger={<StudioComposerChip label={model} active={modelOpen} />}
      />
      <StudioImageToolParamPopover
        label="比例"
        open={ratioOpen}
        onOpenChange={(open) => {
          closeOthers('ratio')
          setRatioOpen(open)
        }}
        trigger={(
          <StudioComposerChip
            icon={<ColumnWidthOutlined />}
            label={ratioLabel}
            active={ratioOpen}
          />
        )}
      >
        <StudioImageExpandParamSelect
          ariaLabel="扩图比例"
          value={aspectRatio}
          options={ratioOptions}
          onChange={(value) => {
            patchImageEditSession({
              aspectRatio: value,
              rect: centeredExpandRect(parseAspectRatio(value))
            })
            patchOptions({ aspectRatio: value })
          }}
        />
      </StudioImageToolParamPopover>
      <StudioImageToolParamPopover
        label="清晰度"
        open={qualityOpen}
        onOpenChange={(open) => {
          closeOthers('quality')
          setQualityOpen(open)
        }}
        trigger={<StudioComposerChip label={quality} active={qualityOpen} />}
      >
        <StudioImageExpandParamSelect
          ariaLabel="扩图清晰度"
          value={quality}
          options={qualityOptions}
          onChange={(value) => patchOptions({ quality: value })}
        />
      </StudioImageToolParamPopover>
      <StudioImageToolParamPopover
        label="张数"
        open={countOpen}
        onOpenChange={(open) => {
          closeOthers('count')
          setCountOpen(open)
        }}
        trigger={<StudioComposerChip label={`${count}张`} active={countOpen} />}
      >
        <StudioImageExpandParamSelect
          ariaLabel="扩图张数"
          value={String(count)}
          options={countOptions}
          onChange={(value) => patchOptions({ count: Number(value) })}
        />
      </StudioImageToolParamPopover>
      <StudioImageToolParamPopover
        label="精细度"
        open={finenessOpen}
        onOpenChange={(open) => {
          closeOthers('fineness')
          setFinenessOpen(open)
        }}
        trigger={<StudioComposerChip label={finenessLabel} active={finenessOpen} />}
      >
        <div className="studio-image-tool-param-panel__segmented" role="group" aria-label="扩图精细度">
          {STUDIO_EXPAND_FINENESS.map((item) => (
            <button
              key={item.value}
              type="button"
              className={fineness === item.value ? 'is-active' : ''}
              onClick={() => patchOptions({ fineness: item.value as StudioImageFineness })}
            >
              {item.label}
            </button>
          ))}
        </div>
      </StudioImageToolParamPopover>
      <StudioCreditSendButton
        credits={credits}
        isFree={false}
        showCredits
        hasContent
        mode="agent"
        loading={busy}
        className="studio-composer__send"
        onSend={() => { void submit() }}
      />
      </>}
    </div>
  )
}
