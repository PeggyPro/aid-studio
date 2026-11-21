'use client'

import { UserOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import type { StudioCanvasNode } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioVideoOperation } from '@/hooks/studio/useStudioVideoOperation'
import { useCanvasVideoToolQuote } from '@/hooks/studio/useCanvasVideoToolQuote'
import { SUBJECT_MODES, subjectEditProblem, subjectEditPrompt, subjectEditRequestPrompt } from '@/utils/studio/studioVideoSubject'
import { StudioComposerFrame } from './StudioComposerFrame'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioModelPicker } from './StudioModelPicker'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { useStudioCanvasModelsByFunc } from '@/hooks/studio/useStudioCatalog'
import { modelSupportsCapability } from '@/utils/modelCapability'
import { canvasResourceMatchesNodeMedia } from '@/utils/canvas/canvasCurrentResultResource'
import '../canvas/studio-video-subject.css'

export function StudioVideoSubjectComposer({ node }: { node: StudioCanvasNode }) {
  const canvasRuntime = useCanvasProjectRuntime()
  const funcCode = node.data.videoSubjectEdit?.mode === 'matting'
    ? 'video_portrait_segmentation' : 'canvas.video.subject_edit'
  const capabilityCode = node.data.videoSubjectEdit?.mode === 'matting'
    ? 'portrait_foreground' : 'video_edit'
  const catalog = useStudioCanvasModelsByFunc(funcCode, String(canvasRuntime?.project?.projectId ?? ''), Boolean(canvasRuntime))
  const pool = { ...catalog, models: catalog.data.filter(model => modelSupportsCapability(model, capabilityCode)) }
  const operation = useStudioVideoOperation()
  const [quoteDraft, setQuoteDraft] = useState(() => ({ nodeId: node.id, source: node.data.prompt, value: node.data.prompt }))
  const quotePrompt = quoteDraft.nodeId === node.id && quoteDraft.source === node.data.prompt
    ? quoteDraft.value : node.data.prompt
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const edit = node.data.videoSubjectEdit
  const originalSource = useStudioUiStore(state => edit ? state.nodes.find(item => item.id === edit.sourceNodeId) : undefined)
  useEffect(() => {
    if (edit && !node.data.prompt.trim()) {
      updateNodeData(node.id, { prompt: subjectEditPrompt(edit).replace(edit.sourceTitle, `@${edit.sourceTitle}`) })
    }
  }, [edit, node.data.prompt, node.id, updateNodeData])
  const sourceResourceId = node.data.videoToolOptions?.sourceResourceId
  // Saved subject edits omit transient URLs. Resolve the linked source from the open workspace.
  const sameCanvasSource = edit && originalSource?.data.kind === 'video' && originalSource.data.mediaUrl
    && (sourceResourceId
      ? canvasRuntime?.nodeResources(originalSource.id).some(resource => resource.mediaType === 'VIDEO'
        && String(resource.resourceId) === sourceResourceId && canvasResourceMatchesNodeMedia(resource, originalSource.data))
      : (edit.sourceRecordId == null || edit.sourceRecordId === originalSource.data.recordId)
        && (!edit.sourceVideoUrl || edit.sourceVideoUrl === originalSource.data.mediaUrl))
  const sourceSnapshot: StudioCanvasNode | null = canvasRuntime
    ? (sameCanvasSource ? originalSource : null)
    : edit ? { ...node, id: edit.sourceNodeId, data: { ...node.data, title: edit.sourceTitle,
      mediaUrl: edit.sourceVideoUrl, recordId: edit.sourceRecordId, videoSubjectEdit: undefined } } : null
  const toolQuote = useCanvasVideoToolQuote(edit && sourceSnapshot && !pool.loading && pool.models.some(model => model.modelCode === node.data.model) ? {
    source: sourceSnapshot, targetNodeId: node.id, operation: SUBJECT_MODES[edit.mode].operation,
    label: SUBJECT_MODES[edit.mode].label, modelCode: node.data.model,
    clipRange: edit.range, subjectEdits: edit.marks,
    prompt: subjectEditRequestPrompt(edit, quotePrompt)
  } : null, canvasRuntime)
  if (!edit) return null
  const definition = SUBJECT_MODES[edit.mode]
  const problem = subjectEditProblem(edit)
  const busy = operation.busy || node.data.status === 'generating' || node.data.status === 'queued'
  return <StudioComposerFrame node={node} allowEmptySubmit placeholder="在操作语句后补充画面要求（可选）"
    quoteOverride={toolQuote} onPromptDraftChange={(value) => setQuoteDraft({ nodeId: node.id, source: node.data.prompt, value })}
    pricingModel={canvasRuntime ? resolveStudioComposerModelFromNode(pool.models, node) : undefined}
    pricingLoading={Boolean(canvasRuntime && pool.loading)}
    submitDisabledReason={busy ? '正在处理视频' : pool.loading ? '正在读取视频模型'
      : !sourceSnapshot ? '源视频已变化或尚未加载，请确认原片后重新标记主体'
        : pool.error || problem || (canvasRuntime && !pool.models.some(model => model.modelCode === node.data.model) ? '请选择当前业务可用的视频模型' : '')}
    onSubmit={sourceSnapshot ? (prompt) => operation.run({ source: sourceSnapshot, targetNodeId: node.id, operation: definition.operation,
      label: definition.label, modelCode: node.data.model, clipRange: edit.range, subjectEdits: edit.marks,
      prompt: subjectEditRequestPrompt(edit, prompt) }) : undefined}
    topLeft={<StudioComposerChip icon={<UserOutlined />} label={definition.label} arrow={false} />}
    bottomLeft={<StudioModelPicker value={node.data.model} mediaKind="video"
      options={canvasRuntime ? pool.models : undefined} preferredModelCode={canvasRuntime ? pool.defaultModelCode : undefined}
      optionsLoading={Boolean(canvasRuntime && pool.loading)} optionsError={canvasRuntime ? pool.error : undefined}
      onChange={(model, item) => updateNodeData(node.id, {
      model, modelName: item.modelName, modelCostCredits: item.costCredits, modelIsFree: item.isFree
    })} />}
    />
}
