'use client'

import { useCallback } from 'react'
import { message } from 'antd'
import { STUDIO_NODE_META, STUDIO_SKILLS, STUDIO_TEMPLATES } from '@/config/studioMeta'
import {
  addStudioUnitChain,
  canGenerateStudioNode,
  createStudioTask,
  useStudioUiStore
} from '@/stores/studioUi'
import type {
  StudioAutonomyMode,
  StudioNodeData,
  StudioSkill,
  StudioTask
} from '@/types/studio'
import { mockStudioApi } from '@/utils/studio/mockStudioApi'
import { submitStudioAgentPrompt } from '@/utils/studio/studioAgentPrompt'
import { studioId } from '@/utils/studio/studioGraph'
import { useSmartEditGeneration } from './useSmartEditGeneration'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { createDefaultDirectorScene } from '@/utils/studio/directorScene'
import { layoutStudioPipelineTemplate, studioPipelineAppendOffset } from '@/utils/studio/studioPipelineLayout'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import {
  isStudioLocalDemoEnabled,
  STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE
} from '@/utils/studio/studioRuntimeCapabilities'

const taskControllers = new Map<string, AbortController>()

export function cancelAllStudioMockTasks(): void {
  taskControllers.forEach((controller) => controller.abort())
  taskControllers.clear()
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '任务执行失败，请重试'
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function useStudioCommands() {
  const smartEditGenerate = useSmartEditGeneration()
  const canvasRuntime = useCanvasProjectRuntime()
  const generateNode = useCallback(async (nodeId: string, runId?: string): Promise<boolean> => {
    const store = useStudioUiStore.getState()
    const scopeKey = store.scopeKey
    const node = store.nodes.find((item) => item.id === nodeId)
    if (node?.data.flowBinding) {
      selectStudioFlowStepNode(node.data.flowBinding.step)
      message.info('请在节点对话框中使用真实生成能力')
      return false
    }
    if (!isStudioLocalDemoEnabled()) {
      message.info(STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE)
      return false
    }
    if (!canGenerateStudioNode(node)) {
      message.info('该节点用于组织与引用，不需要生成')
      return false
    }
    if (!node?.data.prompt.trim()) {
      message.warning('请先填写生成描述')
      return false
    }
    if (node.data.status === 'generating' || node.data.status === 'queued') {
      message.info('该节点正在生成中，可在任务区查看或取消')
      return false
    }

    const task = createStudioTask(`生成 · ${node.data.title}`, {
      nodeId,
      runId,
      stage: '模型生成中',
      progress: 18,
      status: 'running'
    })
    const controller = new AbortController()
    taskControllers.set(task.id, controller)
    store.upsertTask(task)
    store.updateNodeData(nodeId, { status: 'generating', errorMessage: undefined })

    try {
      const latestNode = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
      if (!latestNode) throw new Error('目标节点已被移除')
      const result = await mockStudioApi.generateNode(latestNode, controller.signal)
      const currentStore = useStudioUiStore.getState()
      if (currentStore.scopeKey !== scopeKey) return false
      if (!currentStore.nodes.some((item) => item.id === nodeId)) {
        currentStore.patchTask(task.id, {
          status: 'cancelled',
          stage: '目标节点已移除',
          progress: 0
        })
        return false
      }
      const patch: Partial<StudioNodeData> = {
        status: 'success',
        mediaUrl: result.mediaUrl,
        resultSummary: result.summary,
        errorMessage: undefined
      }
      if (latestNode.data.kind === 'director') {
        const sceneId = latestNode.data.directorSceneId ?? studioId('director-scene')
        const currentScene = currentStore.directorScenes[sceneId] ?? createDefaultDirectorScene(sceneId)
        currentStore.setDirectorScene({
          ...currentScene,
          name: latestNode.data.title,
          previewImageUrl: result.mediaUrl,
          sourceNodeIds: [...new Set([
            ...(currentScene.sourceNodeIds ?? []),
            ...(latestNode.data.composerRefs?.flatMap((ref) => ref.nodeId ? [ref.nodeId] : []) ?? [])
          ])],
          updatedAt: new Date().toISOString()
        })
        patch.directorSceneId = sceneId
      }
      currentStore.updateNodeData(nodeId, patch)
      currentStore.patchTask(task.id, {
        status: 'succeeded',
        stage: '已完成',
        progress: 100,
        errorMessage: undefined
      })
      message.success(`${node.data.title} 生成完成`)
      return true
    } catch (error: unknown) {
      const currentStore = useStudioUiStore.getState()
      if (currentStore.scopeKey !== scopeKey) return false
      if (isAbortError(error)) {
        currentStore.updateNodeData(nodeId, { status: 'empty', errorMessage: undefined })
        currentStore.patchTask(task.id, { status: 'cancelled', stage: '已取消', progress: 0 })
        return false
      }
      const detail = errorMessage(error)
      const latest = currentStore.nodes.find((item) => item.id === nodeId)
      const hasMedia = Boolean(latest?.data.mediaUrl?.trim())
      currentStore.updateNodeData(nodeId, {
        status: hasMedia ? 'success' : 'empty',
        errorMessage: undefined
      })
      currentStore.patchTask(task.id, {
        status: 'failed',
        stage: '生成失败',
        errorMessage: detail
      })
      message.error(`${node.data.title}：${detail}`)
      return false
    } finally {
      taskControllers.delete(task.id)
    }
  }, [])

  const continuePipeline = useCallback(async (task: StudioTask) => {
    const store = useStudioUiStore.getState()
    if (store.studioMode === 'flow') {
      message.info('流程画布任务由七步流程运行时接管')
      return
    }
    const scopeKey = store.scopeKey
    store.patchTask(task.id, { status: 'running', stage: '生成段执行中', progress: 48 })
    const targets = store.nodes.filter(
      (node) => canGenerateStudioNode(node) && node.data.status !== 'success'
    )
    const batch = task.autonomyMode === 'ask' ? targets.slice(0, 1) : targets
    const completedNodeIds = [...(task.completedNodeIds ?? [])]
    const pipelineNodeCount = completedNodeIds.length + targets.length
    let lastAttemptSucceeded = false
    for (const node of batch) {
      const latestTask = useStudioUiStore.getState().tasks.find((item) => item.id === task.id)
      if (latestTask?.status === 'cancelled') return
      const succeeded = await generateNode(node.id, task.id)
      lastAttemptSucceeded = succeeded
      if (useStudioUiStore.getState().scopeKey !== scopeKey) return
      if (succeeded) completedNodeIds.push(node.id)
      useStudioUiStore.getState().patchTask(task.id, {
        completedNodeIds,
        progress: 48 + Math.round((completedNodeIds.length / Math.max(pipelineNodeCount, 1)) * 50)
      })
    }
    const currentStore = useStudioUiStore.getState()
    if (currentStore.scopeKey !== scopeKey) return
    const remaining = currentStore.nodes.filter(
      (node) => canGenerateStudioNode(node) && node.data.status !== 'success'
    )
    if (task.autonomyMode === 'ask' && remaining.length) {
      currentStore.patchTask(task.id, {
        status: 'waiting',
        stage: `等待确认下一步 · ${remaining[0].data.title}`,
        completedNodeIds
      })
      currentStore.addMessage({
        role: 'assistant',
        content: lastAttemptSucceeded
          ? `“${batch[0].data.title}”已完成。下一步将处理“${remaining[0].data.title}”，你可以先调整节点参数，再到任务区确认继续。`
          : `“${batch[0]?.data.title ?? '当前节点'}”未完成。你可以调整参数后在任务区重试，成功前不会推进到下一步。`,
        actionable: true
      })
      return
    }
    const hasFailure = currentStore.tasks.some(
      (item) => item.runId === task.id && item.status === 'failed'
    )
    currentStore.patchTask(task.id, {
      status: hasFailure ? 'failed' : 'succeeded',
      stage: hasFailure ? '部分完成，可局部修复' : '管线完成',
      progress: 100,
      errorMessage: hasFailure ? '部分节点生成失败，已保留成功结果' : undefined
    })
    currentStore.addMessage({
      role: 'assistant',
      content: hasFailure
        ? '管线部分完成。我保留了成功节点，可运行画布体检后仅修复失败项。'
        : '管线已完成。你可以继续调整任意节点，或进入导演台补充构图参考。',
      actionable: true
    })
  }, [generateNode])

  const runPipeline = useCallback(async (prompt: string, mode: StudioAutonomyMode) => {
    const trimmed = prompt.trim()
    if (!trimmed) {
      message.warning('先描述你想创作的内容')
      return false
    }
    if (useStudioUiStore.getState().studioMode === 'flow') {
      selectStudioFlowStepNode('story-script')
      message.info('请在流程对话向导或对应流程编辑器中启动真实任务')
      return false
    }
    if (!isStudioLocalDemoEnabled()) {
      message.info(STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE)
      return false
    }
    const task = createStudioTask('Agent · 短剧管线', {
      id: studioId('run'),
      stage: '分析输入',
      progress: 8,
      status: 'running',
      autonomyMode: mode,
      completedNodeIds: []
    })
    const controller = new AbortController()
    taskControllers.set(task.id, controller)
    const store = useStudioUiStore.getState()
    const scopeKey = store.scopeKey
    store.upsertTask(task)
    store.addMessage({ role: 'user', content: trimmed })
    try {
      const result = await mockStudioApi.planPipeline(trimmed, controller.signal)
      const currentStore = useStudioUiStore.getState()
      if (currentStore.scopeKey !== scopeKey) return
      const pipelineTemplate = layoutStudioPipelineTemplate(STUDIO_TEMPLATES[0], currentStore.studioMode)
      currentStore.applyTemplate(
        pipelineTemplate,
        studioPipelineAppendOffset(currentStore.nodes, pipelineTemplate)
      )
      currentStore.addMessage({ role: 'assistant', content: result.summary, actionable: true })
      const needsConfirmation = mode !== 'auto'
      if (needsConfirmation) {
        currentStore.patchTask(task.id, {
          status: 'waiting',
          stage:
            mode === 'structure'
              ? '等待确认画布结构'
              : mode === 'ask'
                ? '等待确认下一步'
                : '等待确认生成阶段',
          progress: 38
        })
        message.info('结构已落到画布，确认后才进入生成段')
        return true
      }
      await continuePipeline(task)
      return true
    } catch (error: unknown) {
      const currentStore = useStudioUiStore.getState()
      if (currentStore.scopeKey !== scopeKey) return
      if (isAbortError(error)) {
        currentStore.patchTask(task.id, { status: 'cancelled', stage: '已取消', progress: 0 })
        return
      }
      const detail = errorMessage(error)
      currentStore.patchTask(task.id, { status: 'failed', stage: '规划失败', errorMessage: detail })
      message.error(detail)
      return false
    } finally {
      taskControllers.delete(task.id)
    }
  }, [continuePipeline])

  const confirmPipeline = useCallback(async (taskId: string) => {
    const store = useStudioUiStore.getState()
    if (store.studioMode === 'flow') return
    const task = store.tasks.find((item) => item.id === taskId)
    if (!task || task.status !== 'waiting') return
    if (!isStudioLocalDemoEnabled()) {
      message.info(STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE)
      return
    }
    if (task.autonomyMode === 'ask') {
      store.setConversation({
        ...store.conversation,
        phase: 'executing',
        updatedAt: new Date().toISOString()
      })
    }
    await continuePipeline(task)
    if (task.autonomyMode !== 'ask') return
    const latestStore = useStudioUiStore.getState()
    const latestTask = latestStore.tasks.find((item) => item.id === taskId)
    latestStore.setConversation({
      ...latestStore.conversation,
      phase: latestTask?.status === 'succeeded'
        ? 'completed'
        : latestTask?.status === 'failed'
          ? 'failed'
          : 'paused',
      updatedAt: new Date().toISOString()
    })
  }, [continuePipeline])

  const cancelTask = useCallback((taskId: string) => {
    const store = useStudioUiStore.getState()
    if (store.studioMode === 'flow') {
      message.info('流程画布只允许通过真实任务入口取消')
      return
    }
    const task = store.tasks.find((item) => item.id === taskId)
    if (!task || ['succeeded', 'failed', 'cancelled'].includes(task.status)) return
    taskControllers.get(taskId)?.abort()
    for (const child of store.tasks.filter((item) => item.runId === taskId)) {
      taskControllers.get(child.id)?.abort()
      store.patchTask(child.id, { status: 'cancelled', stage: '随管线取消' })
      if (child.nodeId) store.updateNodeData(child.nodeId, { status: 'empty', errorMessage: undefined })
    }
    store.patchTask(taskId, { status: 'cancelled', stage: '已取消', progress: 0 })
    if (task.autonomyMode === 'ask') {
      const latest = useStudioUiStore.getState()
      latest.setConversation({
        ...latest.conversation,
        phase: 'paused',
        updatedAt: new Date().toISOString()
      })
    }
  }, [])

  const runSkill = useCallback((skill: StudioSkill) => {
    const store = useStudioUiStore.getState()
    if (store.studioMode === 'flow') {
      message.info('流程画布不运行自由画布 Skill，请打开对应流程节点')
      return
    }
    if (skill.action === 'diagnose' || skill.action === 'quality_check') {
      const diagnostics = store.runDiagnostics()
      message.info(diagnostics.length ? `发现 ${diagnostics.length} 项可改进内容` : '画布状态良好')
      return
    }
    if (skill.action === 'gen_assets') {
      store.applyTemplate(STUDIO_TEMPLATES[1], { x: store.nodes.length ? 90 : 0, y: 120 })
    } else if (skill.action === 'gen_unit_chain') {
      addStudioUnitChain(`镜头 ${store.nodes.filter(
        (node) => node.data.kind === 'image' && node.data.imagePurpose === 'storyboard'
      ).length + 1}`)
    } else {
      const template = STUDIO_TEMPLATES[0]
      store.applyTemplate({
        ...template,
        nodes: template.nodes.filter((node) =>
          node.kind === 'storyboard_script' || node.imagePurpose === 'storyboard'
        ),
        edges: []
      })
    }
    store.addMessage({ role: 'assistant', content: `Skill「${skill.name}」已应用，仅搭建可编辑结构。` })
    message.success(`${skill.name} 已应用到画布`)
  }, [])

  const sendChat = useCallback(async (content: string) => {
    const trimmed = content.trim()
    const store = useStudioUiStore.getState()
    if (store.studioMode === 'flow') {
      message.info('流程对话由真实流程向导处理，不会运行本地 Mock')
      return []
    }
    const editTarget = store.nodes.find((node) => node.id === store.activeNodeId && node.data.kind === 'smart_edit')
      ?? (store.chatContextNodeIds.length === 1 ? store.nodes.find((node) => node.id === store.chatContextNodeIds[0] && node.data.kind === 'smart_edit') : undefined)
    if (editTarget?.data.kind === 'smart_edit') return submitStudioAgentPrompt(trimmed, undefined, { smartEdit: { generateNode: smartEditGenerate, canEdit: !canvasRuntime || (canvasRuntime.canEdit && canvasRuntime.canMutate && !canvasRuntime.nodeEditLockOwner(editTarget.id)) } })
    if (!isStudioLocalDemoEnabled()) {
      message.info(STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE)
      return []
    }
    const waitingPipeline = store.tasks.find(
      (task) => task.autonomyMode === 'ask' && task.status === 'waiting'
    )
    const hasCanvasContext = store.chatContextNodeIds.length > 0 || Boolean(store.activeNodeId)
    if (trimmed && waitingPipeline && !hasCanvasContext && store.conversation.phase === 'paused') {
      store.addMessage({ role: 'user', content: trimmed })
      if (/^(继续|确认|下一步|开始|可以)(执行|生成|继续)?[。！!\s]*$/.test(trimmed)) {
        await confirmPipeline(waitingPipeline.id)
        return []
      }
      const nextNode = store.nodes.find(
        (node) => canGenerateStudioNode(node) && node.data.status !== 'success'
      )
      if (!nextNode) {
        store.addMessage({ role: 'assistant', content: '当前没有待执行节点，可以发起新的创作目标。' })
        return []
      }
      const adjustment = `调整要求：${trimmed}`
      store.updateNodeData(nextNode.id, {
        prompt: nextNode.data.prompt.trim()
          ? `${nextNode.data.prompt.trim()}\n${adjustment}`
          : adjustment
      })
      store.addMessage({
        role: 'assistant',
        content: `已把调整写入下一步“${nextNode.data.title}”。你可以继续修改，或发送“继续”执行该步骤。`,
        actionable: true
      })
      return []
    }
    return submitStudioAgentPrompt(trimmed, generateNode, { smartEdit: { generateNode: smartEditGenerate, canEdit: !canvasRuntime || (canvasRuntime.canEdit && canvasRuntime.canMutate) } })
  }, [confirmPipeline, generateNode, smartEditGenerate, canvasRuntime])

  const applyChatToActiveNode = useCallback((content: string) => {
    const store = useStudioUiStore.getState()
    const node = store.nodes.find((item) => item.id === store.activeNodeId)
    if (!node) {
      message.warning('请先在画布中选择一个节点')
      return
    }
    if (node.data.flowBinding) {
      selectStudioFlowStepNode(node.data.flowBinding.step)
      message.info('流程实体请在对应流程编辑器中修改')
      return
    }
    if (!STUDIO_NODE_META[node.data.kind].generatable) {
      message.info('当前节点没有生成器，可将内容发送为备注节点')
      return
    }
    store.updateNodeData(node.id, { prompt: content })
    message.success(`已应用到「${node.data.title}」`)
  }, [])

  const sendChatToCanvas = useCallback((content: string) => {
    const store = useStudioUiStore.getState()
    store.addNode('note', { x: 120 + store.nodes.length * 12, y: 420 }, {
      title: 'Agent 建议',
      subtitle: content,
      zoneId: 'input'
    })
    message.success('已发送为备注节点')
  }, [])

  return {
    generateNode,
    runPipeline,
    confirmPipeline,
    cancelTask,
    runSkill,
    sendChat,
    applyChatToActiveNode,
    sendChatToCanvas,
    skills: STUDIO_SKILLS
  }
}
