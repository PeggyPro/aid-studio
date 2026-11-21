'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { StudioCreditAmount } from '@/components/studio/composer/StudioCreditSendButton'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { requestCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useCanvasQuote } from '@/hooks/studio/useCanvasQuote'
import { useCanvasGatewayIdentity } from '@/hooks/studio/useCanvasGatewayIdentity'
import { canvasQuoteIdentity } from '@/utils/canvas/canvasQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { useStudioUiStore } from '@/stores/studioUi'
import type { BillingQuoteVO, UserModelListByFuncGroupVO } from '@/types/business-api'
import type { UserImageGenerationRequest } from '@/types/generation-api'
import type { StudioCanvasNode } from '@/types/studio'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasUserModelListByFuncCodes } from '@/utils/business/model'
import { findModelCapability, modelForCapability, modelSupportsCapability, resolveModelSizeOptions } from '@/utils/modelCapability'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { studioRegionThumbStyle } from '@/utils/studio/studioRegionThumbStyle'

const FUNC_CODE = 'canvas.image.layer_split'
const CAPABILITY_CODE = 'image_layer_decomposition'
const DEFAULT_PROMPT = '将主体分离成可编辑的透明图层并补全底图'

type PreparedQuote = {
  fingerprint: string
  identity: string
  source: { resourceId: string; expectedVersion: number }
  quote: BillingQuoteVO
}

export function StudioCanvasLayerSplitDock({ node }: { node: StudioCanvasNode }) {
  const runtime = useCanvasProjectRuntime()
  const { identity, connected } = useCanvasGatewayIdentity()
  const session = useStudioUiStore((state) => state.layerSplitSession)
  const closeSession = useStudioUiStore((state) => state.closeLayerSplitSession)
  const addNode = useStudioUiStore((state) => state.addNode)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const projectId = String(runtime?.project?.projectId || '')
  const [catalog, setCatalog] = useState<{ projectId: string; identity: string; group: UserModelListByFuncGroupVO | null; error: string } | null>(null)
  const [modelCode, setModelCode] = useState('')
  const [size, setSize] = useState('')
  const [prompt, setPrompt] = useState(session?.draftPrompt?.trim() || DEFAULT_PROMPT)
  const [prepared, setPrepared] = useState<PreparedQuote | null>(null)
  const [modelOpen, setModelOpen] = useState(false)
  const [sizeOpen, setSizeOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const regions = session?.regions ?? []
  const activeRegion = regions.find((region) => region.id === session?.activeRegionId) ?? regions.at(-1)
  const selectionInstruction = regions.map((region, index) => {
    const { x, y, w, h } = region.rect
    return `选区${index + 1}：左上角(${Math.round(x * 100)}%,${Math.round(y * 100)}%)，宽高(${Math.round(w * 100)}%,${Math.round(h * 100)}%)`
  }).join('；')
  const requestPrompt = selectionInstruction ? `${prompt.trim()}。重点分离框选对象：${selectionInstruction}` : prompt.trim()

  useEffect(() => {
    if (!projectId || !connected) return
    let active = true
    void canvasUserModelListByFuncCodes([FUNC_CODE], projectId).then((groups) => {
      if (active) setCatalog({ projectId, identity, group: groups.find((group) => group.funcCode === FUNC_CODE) || null, error: '' })
    }).catch((error: unknown) => {
      if (active) setCatalog({ projectId, identity, group: null, error: apiErrorMessage(error, '图层分离模型加载失败') })
    })
    return () => { active = false }
  }, [projectId, identity, connected])

  const currentCatalog = connected && catalog?.projectId === projectId && catalog.identity === identity ? catalog : null
  const group = currentCatalog?.group
  const models = useMemo(() => (group?.models || []).filter((item) => modelSupportsCapability(item, CAPABILITY_CODE)), [group])
  const selectedCode = modelCode || models.find((item) => item.modelCode === group?.defaultModelCode)?.modelCode || models[0]?.modelCode || ''
  const model = models.find((item) => item.modelCode === selectedCode)
  const capability = findModelCapability(model, CAPABILITY_CODE)
  const sizeParameter = capability?.parameterSchema?.find((parameter) => parameter.name === 'size')
  const capabilityModel = modelForCapability(model, CAPABILITY_CODE)
  const schemaSizes = (sizeParameter?.choices || []).filter((choice): choice is string => typeof choice === 'string')
  const sizes = schemaSizes.length ? schemaSizes : resolveModelSizeOptions(capabilityModel)
  const defaultSize = String(sizeParameter?.defaultValue || model?.defaultSizeCode || sizes[0] || '').trim()
  const selectedSize = sizes.find((item) => item.toLowerCase() === size.toLowerCase())
    || sizes.find((item) => item.toLowerCase() === defaultSize.toLowerCase()) || sizes[0] || ''
  const fingerprint = JSON.stringify({ projectId, nodeId: node.id, mediaUrl: node.data.mediaUrl, modelCode: selectedCode, size: selectedSize, prompt: requestPrompt })
  const prepareSource = runtime?.prepareLayerSplitSource
  const unavailable = !connected ? '画布连接尚未就绪，连接后将重新读取模型'
    : !runtime?.canEdit ? '当前账号只有查看权限'
    : !runtime.canUseFeature('canvas.generate') ? '当前账号没有画布生成权限'
      : runtime.isNodeBusy(node.id) ? '原图节点正在生成中'
        : !model ? currentCatalog ? currentCatalog.error || '暂无可用的图层分离模型，请检查业务模型配置' : '正在读取可用模型'
          : !selectedSize ? '所选模型未配置可用清晰度' : ''

  const requestFor = (source: PreparedQuote['source']): UserImageGenerationRequest => ({
    modelName: selectedCode,
    funcCode: FUNC_CODE,
    capabilityCode: CAPABILITY_CODE,
    projectId,
    workspaceId: String(runtime?.project?.workspace.workspaceId || ''),
    clientRequestId: 'layer-split-quote',
    prompt: requestPrompt,
    size: selectedSize,
    expectedImageCount: 17,
    imageEdit: {
      operation: 'LAYER_SPLIT',
      sourceResourceId: source.resourceId,
      sourceObjectId: node.id,
      sourceExpectedVersion: source.expectedVersion
    }
  })

  const pricing = useCanvasQuote('layer-split', session?.nodeId === node.id && prepareSource && !unavailable && prompt.trim() && regions.length
    ? JSON.parse(fingerprint) as Record<string, unknown> : null, async (_request, signal) => {
    if (!prepareSource) throw new Error('原图资源尚未就绪')
    const identity = canvasQuoteIdentity()
    const source = await prepareSource(node.id)
    signal.throwIfAborted()
    const nextQuote = await requestCanvasImageQuote(requestFor(source))
    signal.throwIfAborted()
    if (canvasQuoteIdentity() !== identity) throw new Error('画布账号或连接已变化，请重新报价')
    setPrepared({ fingerprint, identity, source, quote: nextQuote })
    return nextQuote
  })
  const quote = pricing.quote
  const readySource = prepared?.fingerprint === fingerprint && prepared.identity === canvasQuoteIdentity() ? prepared.source : null
  const quotedCredits = studioCanvasQuoteCredits(quote)

  if (!session || session.nodeId !== node.id || !runtime) return null

  const submitDisabledReason = !regions.length ? '请先在图片上框选想要拆分的对象'
    : unavailable || (!prompt.trim() ? '请填写图层分离提示词' : '')
      || (pricing.error ? '当前功能暂不可用' : !quote && !pricing.loading ? '请完善当前参数' : '')
  const actionHint = !regions.length ? '请先在图片上框选想要拆分的对象'
    : !prompt.trim() ? '请填写图层分离提示词' : ''

  const run = () => {
    if (busyRef.current || unavailable || !quote || !readySource || !regions.length) return
    busyRef.current = true
    setBusy(true)
    let resultId = ''
    try {
      if (!prompt.trim()) throw new Error('请填写图层分离提示词')
      const source = readySource
      const state = useStudioUiStore.getState()
      const sourceNode = state.nodes.find((item) => item.id === node.id)
      if (!sourceNode?.data.mediaUrl) throw new Error('原图节点已失效')
      const occupied = state.nodes.map(toStudioNodeRect)
      const position = placeStudioNodeWithoutOverlap(
        { x: sourceNode.position.x + 310, y: sourceNode.position.y },
        { width: 240, height: 180 }, occupied
      )
      resultId = addNode('image', position, {
        title: `${sourceNode.data.title} · 图层分离`,
        subtitle: `由 ${sourceNode.data.title} 分离`,
        zoneId: sourceNode.data.zoneId,
        storyboardId: sourceNode.data.storyboardId,
        status: 'queued',
        mediaKind: 'image',
        imagePurpose: 'layer_separation',
        composerHidden: true,
        prompt: requestPrompt,
        model: model!.modelCode,
        modelName: model!.modelName,
        modelCostCredits: model!.costCredits,
        modelIsFree: model!.isFree,
        generationOptions: { ...sourceNode.data.generationOptions, count: 17 },
        composerRefs: [studioNodeToComposerRef(sourceNode)],
        imageToolOptions: { sourceNodeId: sourceNode.id, modelCode: model!.modelCode },
        resultSummary: '等待图层分离任务'
      })
      addConnection({ source: sourceNode.id, target: resultId, sourceHandle: null, targetHandle: null }, 'reference')
      selectNodes([resultId])
      closeSession()
      window.requestAnimationFrame(() => window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: resultId } })))
      void runtime.generateNode('image', resultId, requestPrompt, {
        imageEdit: {
          sourceNodeId: sourceNode.id,
          sourceResourceId: source.resourceId,
          modelCode: model!.modelCode,
          funcCode: FUNC_CODE,
          capabilityCode: CAPABILITY_CODE,
          expectedImageCount: 17,
          size: selectedSize,
          imageEdit: { operation: 'LAYER_SPLIT', sourceObjectId: sourceNode.id, sourceExpectedVersion: source.expectedVersion }
        }
      }).then((accepted) => {
        if (!accepted && useStudioUiStore.getState().nodes.find((item) => item.id === resultId)?.data.status === 'queued') {
          useStudioUiStore.getState().updateNodeData(resultId, { status: 'failed', errorMessage: '图层分离提交失败' })
        }
      }).catch((error: unknown) => {
        useStudioUiStore.getState().updateNodeData(resultId, { status: 'failed', errorMessage: apiErrorMessage(error, '图层分离提交失败') })
        message.error(apiErrorMessage(error, '图层分离提交失败'))
      })
    } catch (error: unknown) {
      if (resultId) useStudioUiStore.getState().updateNodeData(resultId, { status: 'failed', errorMessage: apiErrorMessage(error, '图层分离提交失败') })
      message.error(apiErrorMessage(error, '图层分离提交失败'))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <div className="studio-layer-split-panel" role="dialog" aria-label="图层分离面板">
      <h3 className="studio-layer-split-panel__title">图层分离</h3>
      <div className="studio-layer-split-panel__fields">
        <label>模型
          <StudioCanvasDropdownPopover ariaLabel="选择图层分离模型" open={modelOpen} onOpenChange={setModelOpen}
            items={models.map((item) => ({ key: item.modelCode, label: item.modelName }))}
            activeKey={selectedCode} onSelect={(value) => { if (value === selectedCode) return; setModelCode(value); setSize(''); setPrepared(null) }}
            trigger={<button type="button" className="studio-layer-split-panel__select" disabled={busy || !models.length}>{model?.modelName || '暂无可用模型'}<span aria-hidden>⌄</span></button>} />
        </label>
        <label>清晰度
          <StudioCanvasDropdownPopover ariaLabel="选择图层分离清晰度" open={sizeOpen} onOpenChange={setSizeOpen}
            items={sizes.map((item) => ({ key: item, label: item }))}
            activeKey={selectedSize} onSelect={(value) => { if (value === selectedSize) return; setSize(value); setPrepared(null) }}
            trigger={<button type="button" className="studio-layer-split-panel__select" disabled={busy || !sizes.length}>{selectedSize || '暂无可用规格'}<span aria-hidden>⌄</span></button>} />
        </label>
      </div>
      <div className="studio-layer-split-panel__prompt-wrap">
        {activeRegion && node.data.mediaUrl ? <span className="studio-layer-split-panel__thumb"
          role="img" aria-label="框选区域预览" style={studioRegionThumbStyle(node.data.mediaUrl, activeRegion.rect, 48)} /> : null}
        <textarea className="studio-layer-split-panel__prompt" value={prompt} rows={3}
          placeholder="先在图片上框选想要拆分的对象，再描述图层"
          onChange={(event) => { setPrompt(event.target.value); setPrepared(null) }} />
      </div>
      {unavailable ? <p className="studio-layer-split-panel__status" role="status">{unavailable}</p> : null}

      <div className="studio-layer-split-panel__actions">
        {actionHint && !busy ? <p className="studio-layer-split-panel__reason" role="status">{actionHint}</p> : null}
        <button type="button" className="studio-layer-split-panel__btn studio-layer-split-panel__btn--ghost" onClick={() => closeSession()}>取消</button>
        <StudioCanvasTooltip title={submitDisabledReason || undefined}>
          <span><button type="button" className="studio-layer-split-panel__btn studio-layer-split-panel__btn--primary"
            disabled={busy || Boolean(unavailable) || !prompt.trim() || !quote || !readySource || !regions.length}
            onClick={run}>
            {busy ? '处理中…' : <><StudioCreditAmount credits={quotedCredits ?? 0} isFree={quote?.isFree === true} authoritative={quotedCredits != null}
              pendingQuote={quotedCredits == null} loading={pricing.loading} error={pricing.error} quoteStatus={pricing.status} />确认拆分</>}
          </button></span>
        </StudioCanvasTooltip>
      </div>
    </div>
  )
}
