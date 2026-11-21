'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useCanvasQuoteReferencePreparation } from '@/hooks/studio/useCanvasQuoteReferencePreparation'
import { canvasResourceMatchesNodeMedia } from '@/utils/canvas/canvasCurrentResultResource'
import { StudioCreditSendButton } from '@/components/studio/composer/StudioCreditSendButton'
import { StudioModelPicker } from '@/components/studio/composer/StudioModelPicker'
import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioImageHdScale } from '@/types/studio'
import { billingQuoteCreditsForDisplay } from '@/utils/billingQuoteDisplay'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { STUDIO_HD_SCALES } from '@/utils/studio/studioImageAiTools'
import { loadStudioCanvasImage } from '@/utils/studio/studioCanvasImageLoad'
import { estimateStudioCredits } from '@/utils/studio/studioCreditEstimate'
import { modelSupportsCapability } from '@/utils/modelCapability'
import { canvasImageUpscaleScales, canvasImageUpscaleSettings } from '@/utils/studio/canvasImageUpscale'
import './studio-image-ai-tools.css'

export function StudioImageHdPanel({ node }: { node: StudioCanvasNode }) {
  const canvasRuntime = useCanvasProjectRuntime()
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const sourceId = node.data.imageToolOptions?.sourceNodeId
  const source = useStudioUiStore((state) => state.nodes.find((item) => item.id === sourceId))
  const actions = useStudioImageActions(source ?? node, { resultNodeId: node.id })
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [sourceSize, setSourceSize] = useState<{ url: string; width: number; height: number } | null>(null)
  const [sizeFailure, setSizeFailure] = useState({ url: '', error: '' })
  const options = useMemo(() => node.data.imageToolOptions ?? {}, [node.data.imageToolOptions])
  const scale = options.scale ?? 2
  const models = useMemo(() => canvasRuntime
    ? actions.canvasUpscaleModels.data.filter((model) => modelSupportsCapability(model, 'image_upscale'))
    : actions.pools.upscale, [actions.canvasUpscaleModels.data, actions.pools.upscale, canvasRuntime])
  const selectedModel = models.find((model) => model.modelCode === options.modelCode)
    ?? models[0]
  const sourceUrl = source?.data.mediaUrl ?? ''
  const sourceResource = source && canvasRuntime?.nodeResources(source.id).find(resource => resource.mediaType === 'IMAGE' && canvasResourceMatchesNodeMedia(resource, source.data))
  const preparation = useCanvasQuoteReferencePreparation(source ?? node, canvasRuntime, {
    onlySelf: true, ownerNodeId: node.id, auto: Boolean(sourceUrl && !sourceResource)
  })
  const modelsLoading = canvasRuntime ? actions.canvasUpscaleModels.loading : actions.modelsLoading
  const sizeError = sizeFailure.url === sourceUrl ? sizeFailure.error : ''

  useEffect(() => {
    if (!sourceUrl) return
    let active = true
    void loadStudioCanvasImage(sourceUrl).then((image) => {
      if (active) setSourceSize({ url: sourceUrl, width: image.width, height: image.height })
      if (active) setSizeFailure({ url: sourceUrl, error: '' })
      image.dispose()
    }).catch(() => {
      if (!active) return
      setSourceSize(null)
      setSizeFailure({ url: sourceUrl, error: '无法读取原图像素尺寸，请检查原图是否可访问' })
    })
    return () => { active = false }
  }, [sourceUrl])

  const width = sourceSize?.url === sourceUrl ? sourceSize.width * scale : 0
  const height = sourceSize?.url === sourceUrl ? sourceSize.height * scale : 0
  const maxPixels = selectedModel?.capability?.maxOutputPixels
  const scaleOptions = selectedModel ? canvasImageUpscaleScales(selectedModel) : STUDIO_HD_SCALES
  const dimensionError = sizeError || (!scaleOptions.includes(scale) ? '所选模型不支持当前放大倍数' : !width || !height
    ? '正在读取原图尺寸'
    : width > 32000 || height > 32000 || (typeof maxPixels === 'number' && maxPixels > 0 && width * height > maxPixels)
      ? '目标尺寸超过所选高清型号的能力范围'
      : '')
  const referenceError = canvasRuntime && sourceUrl && !sourceResource ? '原图尚未准备好，请先补齐引用后报价' : ''
  const disabledReason = modelsLoading ? '正在读取图片高清模型' : actions.reasonFor('upscale') || (!selectedModel ? '暂无可用的图片高清型号' : dimensionError) || referenceError
  const quoteRequest = canvasRuntime?.project && !disabledReason && selectedModel && sourceResource ? {
    modelName: selectedModel.modelCode,
    capabilityCode: 'image_upscale',
    projectId: String(canvasRuntime.project.projectId),
    workspaceId: String(canvasRuntime.project.workspace.workspaceId),
    clientRequestId: 'image-upscale-quote',
    ...canvasImageUpscaleSettings(selectedModel, sourceSize!.width, sourceSize!.height, scale),
    expectedImageCount: 1,
    references: [{ resourceId: String(sourceResource.resourceId), mention: '原图', role: 'REFERENCE' as const }]
  } : null
  const quote = useCanvasImageQuote(quoteRequest)
  const credits = canvasRuntime ? studioCanvasQuoteCredits(quote.quote) : billingQuoteCreditsForDisplay(quote.quote)
  const displayedCredits = canvasRuntime ? credits ?? 0 : credits ?? estimateStudioCredits({
    costCredits: selectedModel?.costCredits,
    count: 1,
    mediaKind: 'image'
  })
  const sendDisabledReason = preparation.busy ? '正在准备原图引用' : preparation.error || disabledReason || (canvasRuntime && quote.quote?.matched === false
    ? '当前参数没有可用计费规则' : '')

  const chooseModel = (modelCode: string) => {
    const model = models.find((item) => item.modelCode === modelCode)
    if (!model) return
    updateNodeData(node.id, {
      model: model.modelCode,
      modelName: model.modelName,
      modelCostCredits: model.costCredits,
      modelIsFree: model.isFree,
      imageToolOptions: { ...options, modelCode: model.modelCode }
    })
  }

  const submit = async () => {
    if (busyRef.current) return
    if (sendDisabledReason || !selectedModel || !sourceSize || !sourceId) {
      message.warning(sendDisabledReason || '请先确认原图和高清型号')
      return
    }
    busyRef.current = true
    setBusy(true)
    try {
      if (canvasRuntime) {
        if (!source || !canvasRuntime.project) throw new Error('高清原图或画布项目已失效')
        if (!sourceResource || !quoteRequest || !quote.quote) throw new Error('请先获取当前参数的有效报价')
      }
      // 结果节点的型号与高清参数先保存，运行时按该节点取得版本与租约。
      chooseModel(selectedModel.modelCode)
      await actions.upscale({
        modelCode: selectedModel.modelCode,
        sourceWidth: sourceSize.width,
        sourceHeight: sourceSize.height,
        scale
      })
    } catch (error: unknown) {
      message.error(error instanceof Error ? error.message : '图片高清提交失败')
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <div className="studio-image-ai-panel studio-image-ai-panel--hd nodrag nowheel">
      <header className="studio-image-ai-panel__tabs">
        <button type="button" className="is-active">高清放大</button>
        <button type="button" disabled>皮肤编辑器</button>
      </header>
      <div className="studio-image-ai-panel__rows">
        <label className="studio-image-ai-panel__row">
          <span>模型选择</span>
          <StudioModelPicker
            value={selectedModel?.modelCode}
            mediaKind="image"
            options={models}
            optionsLoading={canvasRuntime ? actions.canvasUpscaleModels.loading : actions.modelsLoading}
            optionsError={canvasRuntime ? actions.canvasUpscaleModels.error : ''}
            selectedModelMeta={selectedModel ? {
              modelName: selectedModel.modelName,
              isFree: selectedModel.isFree,
              costCredits: selectedModel.costCredits
            } : undefined}
            variant="panel"
            ariaLabel="选择图片高清模型"
            onChange={(modelCode) => chooseModel(modelCode)}
          />
        </label>
        <div className="studio-image-ai-panel__row">
          <span>放大倍数</span>
          <div className="studio-image-ai-panel__segment" role="group" aria-label="放大倍数">
            {scaleOptions.map((item) => (
              <button key={item} type="button" className={scale === item ? 'is-active' : ''}
                onClick={() => updateNodeData(node.id, { imageToolOptions: { ...options, scale: item as StudioImageHdScale } })}>
                {item}x
              </button>
            ))}
          </div>
        </div>
        <div className="studio-image-ai-panel__row">
          <span>输出尺寸</span>
          <span>{width && height ? `${width} × ${height}` : '读取中'}</span>
        </div>
        {dimensionError && dimensionError !== '正在读取原图尺寸' ? <p className="studio-image-ai-panel__hint" role="alert">{dimensionError}</p> : null}
        {!models.length && !(canvasRuntime ? actions.canvasUpscaleModels.loading : actions.modelsLoading) ? <p className="studio-image-ai-panel__hint">当前没有可用的图片高清型号</p> : null}
        {!canvasRuntime && quote.error ? <p className="studio-image-ai-panel__hint" role="status">报价暂不可用：{quote.error}</p> : null}
      </div>
      <footer className="studio-image-ai-panel__footer">
        {sourceUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img className="studio-image-ai-panel__thumb" src={sourceUrl} alt="原图缩略图" />
        ) : <span />}
        <div className="studio-image-ai-panel__actions">
          <StudioCreditSendButton
            credits={displayedCredits}
            isFree={canvasRuntime ? quote.quote?.isFree === true : quote.quote?.isFree === true || selectedModel?.isFree === true}
            authoritative={credits != null}
            pendingQuote={Boolean(canvasRuntime && credits == null)}
            estimateLoading={Boolean(canvasRuntime && (modelsLoading || quote.loading))}
            estimateLoadingLabel={modelsLoading ? '读取模型' : undefined}
            showCredits={Boolean(selectedModel)}
            estimateError={modelsLoading ? '' : preparation.error || referenceError || quote.error || (canvasRuntime && quote.quote?.matched === false ? '当前参数没有可用计费规则' : '')}
            quoteStatus={Boolean(canvasRuntime) ? modelsLoading ? 'LOADING' : referenceError ? 'BLOCKED' : quote.status : undefined}

            disabled={Boolean(sendDisabledReason)}
            disabledReason={sendDisabledReason}
            hasContent
            mode="agent"
            loading={busy}
            className="studio-composer__send"
            onSend={() => { void submit() }}
          />
        </div>
      </footer>
    </div>
  )
}
