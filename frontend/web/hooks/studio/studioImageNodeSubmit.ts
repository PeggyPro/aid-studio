import { applyStudioImageResults } from '@/utils/studio/studioImageResults'
import { message } from 'antd'
import { runStoryboardEditImageTask } from '@/hooks/useStoryboardEditImageTask'
import { runStoryboardImageGenerateTask } from '@/hooks/useStoryboardImageGenerateTask'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import type { UserModelListItem } from '@/types/business-api'
import type { StudioCanvasNode, StudioImagePurpose } from '@/types/studio'
import { openRechargeModalFromInsufficientBalance } from '@/utils/api'
import { AI_MODEL_FUNC_CODE, STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS } from '@/utils/aiModelFuncCodes'
import { userModelListByFuncCodes } from '@/utils/businessApi'
import {
  modelsFromListByFuncGroups,
  pickFirstNonEmptyModelPool,
  uniqueTrimmedCodes
} from '@/utils/modelListByFuncBatch'
import { ensureStudioFreeStoryboardBinding } from '@/utils/studio/ensureStudioFreeStoryboardBinding'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import {
  buildStudioStoryboardEditImageSubmission,
  buildStudioStoryboardImageSubmission
} from '@/utils/studio/studioComposerSubmission'
import { getStudioStoryboardComposition } from '@/utils/studio/studioStoryboardComposition'
import { planStudioStoryboardCompositionSubmit } from '@/utils/studio/studioStoryboardCompositionSubmit'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'
import type { TaskSseProgressInput } from '@/utils/taskSseProgressText'
import {
  beginStudioOperationRun,
  createStudioOperationInputHash,
  isStudioOperationInputCurrent,
  isStudioOperationRunCurrent,
  settleStudioOperationRun
} from '@/utils/studio/studioOperationRun'
import { studioRemoteMediaUrlProblem } from '@/utils/mediaUrlPolicy'

export type StudioImageSubmitPools = {
  edit: UserModelListItem[]
  storyboard: UserModelListItem[]
}

export async function loadStudioImageSubmitPools(): Promise<StudioImageSubmitPools> {
  const projectId = Number(useCreationStore.getState().currentProjectId)
  const episodeId = Number(useCreationStore.getState().currentEpisodeId)
  const codes = uniqueTrimmedCodes([
    ...STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS,
    AI_MODEL_FUNC_CODE.IMAGE_EDIT
  ])
  const groups = await userModelListByFuncCodes(
    codes,
    Number.isFinite(projectId) && projectId > 0
      ? {
          projectId,
          ...(Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {})
        }
      : undefined
  )
  return {
    edit: modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_EDIT),
    storyboard: pickFirstNonEmptyModelPool(groups, STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS)
  }
}

/** 失败只走 toast / 任务中心；节点本体不挂错误文案。 */
export function clearStudioImageNodeSurfaceError(
  store: ReturnType<typeof useStudioUiStore.getState>,
  nodeId: string
) {
  const node = store.nodes.find((item) => item.id === nodeId)
  const hasMedia = Boolean(node?.data.mediaUrl?.trim())
  store.updateNodeData(nodeId, {
    status: hasMedia ? 'success' : 'empty',
    errorMessage: undefined
  })
}

export function safeStudioImageTaskError(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : ''
  const normalized = raw.replace(/\s+/g, ' ').trim()
  if (!normalized) return fallback
  if (/https?:\/\/|authorization|bearer|api[-_ ]?key|access[-_ ]?key|secret|token|signature/i.test(normalized)) {
    return fallback
  }
  return normalized.slice(0, 160)
}

type ExecuteResult = {
  ok: boolean
  errorMessage?: string
  deferred?: boolean
  imageUrl?: string | null
  recordId?: number | null
  items?: Array<{ imageUrl?: string; recordId?: number; imageId?: number }>
}

/**
 * 与 useStudioImageActions.runTask 同源的任务落盘：派生/写回目标 → SSE → 成功写 mediaUrl。
 * 整组执行与单节点 Composer 共用，避免平行复制一套结果处理。
 */
export async function runStudioImageActionTask(input: {
  sourceNodeId: string
  sourceTitle: string
  targetId: string
  label: string
  execute: (callbacks: {
    onSubmitted: (info: { taskId: number }) => void
    onProgress: (info: TaskSseProgressInput & { percent?: number }) => void
  }) => Promise<ExecuteResult>
}): Promise<boolean> {
  const scopeKey = useStudioUiStore.getState().scopeKey
  const sourceMediaUrl = useStudioUiStore.getState().nodes.find((node) => node.id === input.sourceNodeId)?.data.mediaUrl
  if (sourceMediaUrl) {
    const mediaProblem = studioRemoteMediaUrlProblem(sourceMediaUrl)
    if (mediaProblem) {
      message.warning(mediaProblem)
      return false
    }
  }
  return runStudioRequestSingleFlight(`${scopeKey}:${input.sourceNodeId}:image-action`, async () => {
    const operationRunId = beginStudioOperationRun(scopeKey, [input.targetId])
    if (!operationRunId) return false
    const inputNodeIds = [input.sourceNodeId, input.targetId]
    const inputSnapshotHash = createStudioOperationInputHash(scopeKey, inputNodeIds)
    const live = () => isStudioOperationRunCurrent(scopeKey, operationRunId, [input.targetId]) &&
      isStudioOperationInputCurrent(scopeKey, inputSnapshotHash, inputNodeIds)
    let studioTaskId = ''
    const discardStaleResult = () => {
      if (studioTaskId && useStudioUiStore.getState().scopeKey === scopeKey) {
        useStudioUiStore.getState().patchTask(studioTaskId, {
          status: 'succeeded',
          stage: '结果基于旧输入，未覆盖当前节点',
          progress: 100
        })
      }
      settleStudioOperationRun(scopeKey, operationRunId, [input.targetId])
      return false
    }
    let result: ExecuteResult
    try {
      result = await input.execute({
        onSubmitted: ({ taskId }) => {
          studioTaskId = `studio-image-${taskId}`
          const task = createStudioTask(`${input.sourceTitle} · ${input.label}`, {
            nodeId: input.targetId,
            targetNodeIds: [input.targetId],
            runId: operationRunId,
            remoteTaskId: taskId,
            resultKind: 'image',
            inputSnapshotHash,
            inputNodeIds,
            stage: '任务已提交',
            progress: 0,
            status: 'running'
          })
          if (!live()) return
          useStudioUiStore.getState().upsertTask({ ...task, id: studioTaskId })
          useStudioUiStore.getState().updateNodeData(input.targetId, { status: 'generating' })
        },
        onProgress: (progress) => {
          if (!studioTaskId || !live()) return
          useStudioUiStore.getState().patchTask(studioTaskId, studioSseTaskProgressPatch(progress))
        }
      })
    } catch (error: unknown) {
      const store = useStudioUiStore.getState()
      if (!live()) return discardStaleResult()
      const errorMessage = safeStudioImageTaskError(error, `${input.label}任务异常，请重试或在任务中心查看`)
      clearStudioImageNodeSurfaceError(store, input.targetId)
      if (studioTaskId) {
        store.patchTask(studioTaskId, { status: 'failed', stage: '任务异常终止', errorMessage })
      }
      message.error(errorMessage)
      openRechargeModalFromInsufficientBalance(errorMessage)
      settleStudioOperationRun(scopeKey, operationRunId, [input.targetId])
      return false
    }

    const store = useStudioUiStore.getState()
    if (!live()) return discardStaleResult()
    if (!result.ok) {
      if (result.deferred) {
        store.updateNodeData(input.targetId, {
          status: 'generating',
          resultSummary: '任务仍在后台执行，请在任务中心查看'
        })
        if (studioTaskId) store.patchTask(studioTaskId, { status: 'waiting', stage: '后台执行中' })
        return false
      }
      const errorMessage = result.errorMessage || `${input.label}失败`
      clearStudioImageNodeSurfaceError(store, input.targetId)
      if (studioTaskId) store.patchTask(studioTaskId, { status: 'failed', stage: '执行失败', errorMessage })
      message.error(errorMessage)
      openRechargeModalFromInsufficientBalance(errorMessage)
      settleStudioOperationRun(scopeKey, operationRunId, [input.targetId])
      return false
    }

    const item = result.items?.findLast?.((entry) => Boolean(entry.imageUrl))
      ?? [...(result.items ?? [])].reverse().find((entry) => Boolean(entry.imageUrl))
    const imageUrl = String(result.imageUrl || item?.imageUrl || '').trim()
    if (!imageUrl) {
      const errorMessage = '任务已完成，但未恢复到图片地址，请从任务中心查看结果'
      clearStudioImageNodeSurfaceError(store, input.targetId)
      if (studioTaskId) store.patchTask(studioTaskId, { status: 'failed', stage: '结果地址缺失', errorMessage })
      message.warning(errorMessage)
      settleStudioOperationRun(scopeKey, operationRunId, [input.targetId])
      return false
    }
    const resultMediaProblem = studioRemoteMediaUrlProblem(imageUrl)
    if (resultMediaProblem) {
      clearStudioImageNodeSurfaceError(store, input.targetId)
      if (studioTaskId) store.patchTask(studioTaskId, {
        status: 'failed', stage: '结果地址被安全策略拒绝', errorMessage: resultMediaProblem
      })
      message.warning(resultMediaProblem)
      settleStudioOperationRun(scopeKey, operationRunId, [input.targetId])
      return false
    }
    applyStudioImageResults(input.targetId,
      result.items?.length ? result.items : [{ imageUrl, recordId: result.recordId ?? undefined }], `${input.label}完成`)
    if (studioTaskId) store.patchTask(studioTaskId, { status: 'succeeded', stage: '已完成', progress: 100 })
    return true
  })
}

async function resolveStoryboardIdForNode(node: StudioCanvasNode): Promise<number | null> {
  const existing = Number(node.data.storyboardId)
  if (Number.isFinite(existing) && existing > 0) return existing
  const studioMode = useStudioUiStore.getState().studioMode
  if (studioMode !== 'free') return null
  const ensured = await ensureStudioFreeStoryboardBinding({
    nodeId: node.id,
    projectId: useCreationStore.getState().currentProjectId,
    episodeId: useCreationStore.getState().currentEpisodeId,
    title: node.data.title
  })
  if ('error' in ensured) return null
  return ensured.storyboardId
}

function resolveSubmitTarget(
  source: StudioCanvasNode,
  label: string,
  prompt: string,
  purpose?: StudioImagePurpose
): string | null {
  const store = useStudioUiStore.getState()
  // 分镜构图空下游：写回自身（与 useStudioImageActions.createDerived 一致）
  if (source.data.storyboardCompositionId && !String(source.data.mediaUrl || '').trim()) {
    store.updateNodeData(source.id, {
      status: 'queued',
      prompt: prompt.trim() || source.data.prompt,
      imagePurpose: purpose ?? source.data.imagePurpose ?? 'generic',
      resultSummary: undefined,
      errorMessage: undefined,
      mediaUrl: undefined
    })
    return source.id
  }
  return store.deriveImageNode(
    source.id,
    structuredClone(source.data.generationOptions ?? {}),
    purpose ?? source.data.imagePurpose ?? 'generic',
    label,
    prompt
  )
}

/**
 * 对齐 StudioImageComposer.onSubmit 分支：构图 / 有图编辑 / 无图 regenerate。
 * 供整组执行调用，与单节点发送同一决策树与同一任务落盘。
 */
export async function submitStudioImageNode(
  nodeId: string,
  pools: StudioImageSubmitPools
): Promise<boolean> {
  const store = useStudioUiStore.getState()
  const node = store.nodes.find((item) => item.id === nodeId)
  if (!node || node.data.kind !== 'image') return false

  const storyboardId = await resolveStoryboardIdForNode(node)
  if (!storyboardId) return false
  const latest = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId) ?? node
  const prompt = latest.data.prompt?.trim() || ''
  const projectId = Number(useCreationStore.getState().currentProjectId)
  const episodeId = Number(useCreationStore.getState().currentEpisodeId)

  if (latest.data.storyboardCompositionId) {
    const plan = planStudioStoryboardCompositionSubmit({ node: latest, userPrompt: prompt })
    if ('error' in plan) return false
    const composition = getStudioStoryboardComposition(latest.data.storyboardCompositionId)
    const model = resolveStudioComposerModelFromNode(pools.edit, latest) ?? pools.edit[0]
    if (!model) return false
    const label = plan.label || composition?.label || '分镜构图'
    const targetId = resolveSubmitTarget(latest, label, plan.finalPrompt, plan.purpose)
    if (!targetId) return false
    const targetNode = useStudioUiStore.getState().nodes.find((item) => item.id === targetId) ?? latest
    const submission = buildStudioStoryboardEditImageSubmission({
      node: targetNode,
      model,
      storyboardId,
      prompt: plan.finalPrompt,
      referenceImage: plan.referenceImage
    })
    if (!submission) return false
    return runStudioImageActionTask({
      sourceNodeId: latest.id,
      sourceTitle: latest.data.title,
      targetId,
      label,
      execute: (callbacks) => runStoryboardEditImageTask({ ...submission.body, ...callbacks })
    })
  }

  if (latest.data.mediaUrl) {
    const model = resolveStudioComposerModelFromNode(pools.edit, latest) ?? pools.edit[0]
    if (!model) return false
    const label = '图片编辑'
    const targetId = resolveSubmitTarget(latest, label, prompt || latest.data.prompt)
    if (!targetId) return false
    const targetNode = useStudioUiStore.getState().nodes.find((item) => item.id === targetId) ?? latest
    const submission = buildStudioStoryboardEditImageSubmission({
      node: { ...targetNode, data: { ...targetNode.data, mediaUrl: latest.data.mediaUrl } },
      model,
      storyboardId,
      prompt: prompt || latest.data.prompt
    })
    if (!submission) return false
    return runStudioImageActionTask({
      sourceNodeId: latest.id,
      sourceTitle: latest.data.title,
      targetId,
      label,
      execute: (callbacks) => runStoryboardEditImageTask({ ...submission.body, ...callbacks })
    })
  }

  if (!prompt) return false
  const model = resolveStudioComposerModelFromNode(pools.storyboard, latest) ?? pools.storyboard[0]
  if (!model) return false
  const label = '重新生成'
  const targetId = resolveSubmitTarget(latest, label, prompt)
  if (!targetId) return false
  const targetNode = useStudioUiStore.getState().nodes.find((item) => item.id === targetId) ?? latest
  const submission = buildStudioStoryboardImageSubmission({
    node: targetNode,
    model,
    storyboardId,
    prompt
  })
  return runStudioImageActionTask({
    sourceNodeId: latest.id,
    sourceTitle: latest.data.title,
    targetId,
    label,
    execute: (callbacks) => runStoryboardImageGenerateTask({
      body: submission.body,
      projectEpisode: Number.isFinite(projectId) && projectId > 0 && Number.isFinite(episodeId) && episodeId >= 0
        ? { projectId, episodeId }
        : null,
      ...callbacks
    })
  })
}
