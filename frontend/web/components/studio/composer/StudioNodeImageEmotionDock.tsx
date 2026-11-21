'use client'

import { useEffect, useRef, useState } from 'react'
import { CloseOutlined, PlusOutlined, SmileOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useBillingQuote } from '@/hooks/useBillingQuote'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioEmotionSession } from '@/types/studio'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { resolveStudioParamsPanelOptions } from '@/utils/studio/studioGenerationOptions'
import { buildStudioStoryboardEditImageSubmission } from '@/utils/studio/studioComposerSubmission'
import { pickMatchedOption } from '@/utils/modelCapability'
import { billingQuoteCreditsForDisplay } from '@/utils/billingQuoteDisplay'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { estimateStudioCredits } from '@/utils/studio/studioCreditEstimate'
import { buildStudioEmotionPrompt, emotionGridPoint } from '@/utils/studio/studioEmotionGrid'
import { StudioCreditSendButton } from './StudioCreditSendButton'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioEmotionControls } from './emotion/StudioEmotionControls'
import './StudioImageEmotionPanel.css'

export function StudioNodeImageEmotionDock({
  node,
  session
}: {
  node: StudioCanvasNode
  session: StudioEmotionSession
}) {
  const actions = useStudioImageActions(node)
  const runtime = useCanvasProjectRuntime()
  const canvasEdit = useCanvasImageEdit(node)
  const ensureSourceResource = canvasEdit.ensureSourceResource
  const [sourceResource, setSourceResource] = useState<{ url: string; id: string } | null>(null)
  const [canvasModelCode, setCanvasModelCode] = useState('')
  const [modelOpen, setModelOpen] = useState(false)
  const [qualityOpen, setQualityOpen] = useState(false)
  const [countOpen, setCountOpen] = useState(false)
  const patch = useStudioUiStore((s) => s.patchEmotionSession)
  const close = useStudioUiStore((s) => s.closeEmotionSession)
  const qualityDraft = session.quality || node.data.generationOptions?.quality || ''
  const countDraft = session.count
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)
  const canvasSelection = canvasEdit.select('canvas.image.emotion', 'image_edit', canvasModelCode)
  const model = canvasEdit.active ? canvasSelection.model : resolveStudioComposerModelFromNode(actions.pools.edit, node) ?? actions.pools.edit[0]
  const sourceResourceId = sourceResource && sourceResource.url === node.data.mediaUrl ? sourceResource.id : ''
  const sourceVersion = runtime?.getNodeVersion(node.id) ?? undefined
  useEffect(() => {
    if (!canvasEdit.active || !node.data.mediaUrl) return
    let active = true
    void ensureSourceResource().then((resource) => {
      if (active) setSourceResource({ url: node.data.mediaUrl!, id: String(resource.resourceId) })
    }).catch(() => { if (active) setSourceResource(null) })
    return () => { active = false }
  }, [canvasEdit.active, ensureSourceResource, node.data.mediaUrl])
  const params = resolveStudioParamsPanelOptions('image', model)
  const quality = pickMatchedOption(
    qualityDraft,
    params.qualityOptions.map((o) => o.value),
    params.snapshot.defaultSize
  )
  const countOptions = params.countOptions.filter((o) => o.value <= 4)
  const count = countOptions.some((o) => o.value === countDraft) ? countDraft : (countOptions[0]?.value ?? 1)
  const aspectRatio = pickMatchedOption(
    node.data.generationOptions?.aspectRatio,
    params.ratioOptions.map((o) => o.value),
    params.snapshot.defaultAspectRatio
  )
  const face = session.faces.find((item) => item.id === session.selectedFaceId)
  const prompt = face ? buildStudioEmotionPrompt(face) : ''
  const reason = canvasEdit.active
    ? canvasEdit.disabledReason || (canvasEdit.loading ? '正在读取情绪调节模型' : !model ? canvasEdit.error || '暂无可用的情绪调节模型' : !sourceResourceId || !sourceVersion ? '正在保存原图素材' : !face ? '请选择需要调节的人物' : null)
    : (actions.modelsLoading ? '正在读取图片编辑模型' : actions.reasonFor('edit')) || (!face ? '请选择需要调节的人物' : null)
  const generationOptions = { ...node.data.generationOptions, quality, count, aspectRatio }
  const quoteRequest =
    !canvasEdit.active && !reason && model && actions.binding.storyboardId
      ? (buildStudioStoryboardEditImageSubmission({
          node: { ...node, data: { ...node.data, generationOptions } },
          model,
          storyboardId: actions.binding.storyboardId,
          prompt
        })?.billingRequest ?? null)
      : null
  const quote = useBillingQuote(quoteRequest)
  const faceInput = face ? { region: { x: face.rect.x, y: face.rect.y, width: face.rect.w, height: face.rect.h }, expression: emotionGridPoint(face.affinity, face.arousal).label } : null
  const canvasQuoteRequest = canvasEdit.active && canvasEdit.project && model && sourceResourceId && sourceVersion && faceInput ? {
    modelName: model.modelCode, funcCode: 'canvas.image.emotion', capabilityCode: 'image_edit',
    projectId: String(canvasEdit.project.projectId), workspaceId: String(canvasEdit.project.workspace.workspaceId),
    clientRequestId: 'emotion-quote', prompt, size: quality, expectedImageCount: count,
    imageEdit: { operation: 'EMOTION' as const, sourceResourceId, sourceObjectId: node.id,
      sourceExpectedVersion: sourceVersion, faces: [faceInput] }
  } : null
  const canvasQuote = useCanvasImageQuote(canvasQuoteRequest)
  const activeQuote = canvasEdit.active ? canvasQuote : quote
  const quotedCredits = canvasEdit.active ? studioCanvasQuoteCredits(activeQuote.quote) : billingQuoteCreditsForDisplay(activeQuote.quote)
  const credits = canvasEdit.active ? quotedCredits ?? 0
    : quotedCredits ?? estimateStudioCredits({ costCredits: model?.costCredits, count, mediaKind: 'image' })
  const activeReason = reason || (canvasEdit.active ? canvasQuote.error
    || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : !canvasQuote.quote ? '正在获取报价' : '') : '')
  const submit = async () => {
    if (submitting.current || activeReason || !model) return
    submitting.current = true
    setBusy(true)
    try {
      const ok = canvasEdit.active && faceInput
        ? await canvasEdit.submit({ modelCode: model.modelCode, funcCode: 'canvas.image.emotion', capabilityCode: 'image_edit',
          imageEdit: { operation: 'EMOTION', sourceObjectId: node.id, sourceExpectedVersion: sourceVersion, faces: [faceInput] },
          expectedImageCount: count, size: quality, label: '情绪调节', purpose: node.data.imagePurpose ?? 'generic', prompt })
        : await actions.edit(prompt, '情绪调节', node.data.imagePurpose, {
        generationOptions,
        modelCode: model.modelCode,
        expectedSourceUrl: session.sourceUrl,
        isCurrent: () => useStudioUiStore.getState().emotionSession?.id === session.id
        })
      if (ok) close(session.id)
    } catch {
      message.error('情绪调节提交失败，请重试')
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }
  if (!face) return null
  const change = (affinity: number, arousal: number) =>
    patch(session.id, {
      faces: session.faces.map((f) => (f.id === face.id ? { ...f, affinity, arousal } : f))
    })
  return (
    <section
      className="studio-emotion-panel"
      aria-label="情绪调节"
      onKeyDown={(e) => {
        if (e.key === 'Escape') close(session.id)
      }}
    >
      <header>
        <span>情绪定位</span>
        <strong aria-live="polite">{emotionGridPoint(face.affinity, face.arousal).label}</strong>
        <span className="studio-emotion-panel__hint">拖拽点位调整表情</span>
      </header>
      <StudioEmotionControls key={face.id} affinity={face.affinity} arousal={face.arousal} onChange={change} />
      <div className="studio-emotion-roles" aria-label="目标人物">
        {session.faces.map((f) => (
          <button
            type="button"
            key={f.id}
            aria-pressed={face.id === f.id}
            onClick={() => patch(session.id, { selectedFaceId: f.id })}
          >
            <span
              className="studio-emotion-avatar"
              style={{
                backgroundImage: `url(${JSON.stringify(session.sourceUrl)})`,
                backgroundSize: `${100 / f.rect.w}% ${100 / f.rect.h}%`,
                backgroundPosition: `${(f.rect.x / (1 - f.rect.w || 1)) * 100}% ${(f.rect.y / (1 - f.rect.h || 1)) * 100}%`
              }}
            />
            {f.label}
          </button>
        ))}
        <button type="button" onClick={() => patch(session.id, { phase: 'picking', manualMode: true })}>
          <PlusOutlined aria-hidden />
          手动添加
        </button>
      </div>
      <footer>
        <button
          type="button"
          className="studio-emotion-close"
          aria-label="关闭情绪调节"
          onClick={() => close(session.id)}
        >
          <CloseOutlined />
        </button>
        <span className="studio-emotion-title">
          <SmileOutlined aria-hidden />
          情绪调节
        </span>
        {canvasEdit.active && <StudioCanvasDropdownPopover open={modelOpen} onOpenChange={setModelOpen} placement="top"
          ariaLabel={'\u60c5\u7eea\u8c03\u8282\u6a21\u578b'} activeKey={model?.modelCode || ''}
          items={canvasSelection.models.map((item) => ({ key: item.modelCode, label: item.modelName }))}
          onSelect={setCanvasModelCode}
          trigger={<StudioComposerChip label={model?.modelName || '\u9009\u62e9\u6a21\u578b'} active={modelOpen} />} />}
        <StudioCanvasDropdownPopover open={qualityOpen} onOpenChange={setQualityOpen} placement="top"
          ariaLabel={'\u6e05\u6670\u5ea6'} activeKey={quality}
          items={params.qualityOptions.map((item) => ({ key: item.value, label: item.label }))}
          onSelect={(value) => patch(session.id, { quality: value })}
          trigger={<StudioComposerChip label={quality} active={qualityOpen} />} />
        <StudioCanvasDropdownPopover open={countOpen} onOpenChange={setCountOpen} placement="top"
          ariaLabel={'\u751f\u6210\u5f20\u6570'} activeKey={String(count)}
          items={countOptions.map((item) => ({ key: String(item.value), label: `${item.value}\u5f20` }))}
          onSelect={(value) => patch(session.id, { count: Number(value) })}
          trigger={<StudioComposerChip label={`${count}\u5f20`} active={countOpen} />} />
        <StudioCreditSendButton
          credits={credits}
          isFree={canvasEdit.active ? canvasQuote.quote?.isFree === true : quotedCredits === 0}
          hasContent={Boolean(prompt)}
          mode="agent"
          loading={busy}
          authoritative={quotedCredits != null}
          pendingQuote={Boolean(canvasEdit.active && quotedCredits == null)}
          estimateLoading={Boolean(activeQuote.loading)}
          estimateError={activeQuote.error}
          quoteStatus={canvasEdit.active ? canvasQuote.status : undefined}

          disabled={Boolean(activeReason) || busy}
          disabledReason={activeReason ?? ''}
          onSend={() => {
            void submit()
          }}
        />
      </footer>

    </section>
  )
}
