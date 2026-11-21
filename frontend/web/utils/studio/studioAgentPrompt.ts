import { canGenerateStudioNode, useStudioUiStore } from '@/stores/studioUi'
import {
  advanceStudioAgentThinkingStep,
  buildStudioAgentThinkingSteps,
  type AgentThinkingStep
} from '@/utils/agentThinkingSteps'
import { answerStudioConversation, beginStudioConversation } from './studioConversation'
import { startSmartEditJob, type SmartEditJobOptions } from './smartEditJob'

const STUDIO_REASONING_STEP_DELAY_MS = 140

export interface StudioAgentPromptOptions {
  smartEdit?: SmartEditJobOptions
  wait?: (milliseconds: number) => Promise<void>
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds))
}

function reasoningLinesFromSteps(steps: AgentThinkingStep[]): string {
  return steps
    .filter((step) => step.status === 'done' || step.status === 'active')
    .map((step) => step.label)
    .join('\n')
}

function replyWithoutGeneration(referenceCount: number, activeTitle?: string): string {
  if (referenceCount > 1) {
    return `已带入 ${referenceCount} 个节点作为上下文。我会基于这些引用给出编排建议，不会改写节点原始 Prompt。`
  }
  if (activeTitle) {
    return `已聚焦“${activeTitle}”。该节点不支持直接生成，我会将它作为当前创作上下文。`
  }
  return '这条建议可以落为备注节点，也可以先选择图片、视频、文本或配音节点再执行。'
}

function handleConversationPlanning(content: string): boolean {
  const store = useStudioUiStore.getState()
  const hasCanvasContext = store.chatContextNodeIds.length > 0 || Boolean(store.activeNodeId)
  if (hasCanvasContext) return false
  const current = store.conversation
  if (current.phase === 'idle' || current.phase === 'completed' || current.phase === 'failed') {
    store.addMessage({ role: 'user', content })
    store.setConversation(beginStudioConversation(content))
    store.addMessage({
      role: 'assistant',
      content: '我会先规划整条创作流水线。你希望最终成片多长？也可以直接输入自定义时长。',
      quickReplies: [
        { label: '15 秒', value: '15秒' },
        { label: '30 秒', value: '30秒' },
        { label: '1 分钟', value: '1分钟' }
      ],
      quickReplyField: 'durationSeconds'
    })
    return true
  }
  if (current.phase !== 'clarifying') return false

  store.addMessage({ role: 'user', content })
  const next = answerStudioConversation(current, content)
  store.setConversation(next)
  if (next === current) {
    store.addMessage({
      role: 'assistant',
      content: '请给出明确时长，例如“45 秒”或“2 分钟”。',
      quickReplies: [
        { label: '15 秒', value: '15秒' },
        { label: '30 秒', value: '30秒' },
        { label: '1 分钟', value: '1分钟' }
      ],
      quickReplyField: 'durationSeconds'
    })
    return true
  }
  if (next.pendingField === 'style') {
    store.addMessage({
      role: 'assistant',
      content: '成片时长已记录。请选择整体视觉风格，也可以直接描述自定义风格。',
      quickReplies: [
        { label: '电影写实', value: '电影写实' },
        { label: '国风动画', value: '国风动画' },
        { label: '日漫风格', value: '日漫风格' }
      ],
      quickReplyField: 'style'
    })
    return true
  }
  const totalCredits = next.planSteps.reduce((sum, step) => sum + (step.credits ?? 0), 0)
  store.addMessage({
    role: 'assistant',
    content: `方案已就绪：${next.parameters.durationSeconds ?? 0} 秒 · ${next.parameters.style}。将按“剧本 → 角色/场景/道具 → 分镜 → 视频”执行，每个生成阶段均可确认和调整，当前预估 ${totalCredits} 积分。`,
    actions: [
      { id: 'confirm_plan', label: '确认搭建流程', primary: true },
      { id: 'revise_plan', label: '调整方案' }
    ]
  })
  return true
}

export async function submitStudioAgentPrompt(
  content: string,
  generateNode?: (nodeId: string) => void | Promise<boolean>,
  options: StudioAgentPromptOptions = {}
): Promise<string[]> {
  const trimmed = content.trim()
  if (!trimmed) return []

  const store = useStudioUiStore.getState()
  const active = store.nodes.find((node) => node.id === store.activeNodeId)
  const onlyReference = store.chatContextNodeIds.length === 1 ? store.nodes.find((node) => node.id === store.chatContextNodeIds[0]) : undefined
  const editNode = active?.data.kind === 'smart_edit' ? active : onlyReference?.data.kind === 'smart_edit' ? onlyReference : undefined
  if (editNode) {
    store.addMessage({ role: 'user', content: trimmed, nodeIds: [editNode.id] })
    const result = await startSmartEditJob(editNode.id, trimmed, { generateNode, ...options.smartEdit })
    if (!result.ok && result.message) store.addMessage({ role: 'assistant', content: result.message, nodeIds: [editNode.id] })
    return result.ok ? [editNode.id] : []
  }
  if (store.messages.some((item) => item.status === 'thinking' || item.status === 'generating')) {
    return []
  }
  if (handleConversationPlanning(trimmed)) return []
  const scopeKey = store.scopeKey
  const contextIds = store.chatContextNodeIds.length
    ? store.chatContextNodeIds
    : store.activeNodeId ? [store.activeNodeId] : []
  const references = store.nodes
    .filter((node) => contextIds.includes(node.id))
    .map((node) => ({ id: node.id, title: node.data.title, kind: node.data.kind }))
  const generatedIds = references
    .map((reference) => reference.id)
    .filter((nodeId) => canGenerateStudioNode(store.nodes.find((node) => node.id === nodeId)))

  store.addMessage({
    role: 'user',
    content: trimmed,
    nodeIds: references.map((item) => item.id),
    nodeReferences: references
  })

  const thinkingStartedAt = new Date().toISOString()
  const plannedSteps = buildStudioAgentThinkingSteps(trimmed, references.length, generatedIds.length)
  const assistantMessageId = store.addMessage({
    role: 'assistant',
    content: '',
    reasoning: '',
    thinkingSteps: advanceStudioAgentThinkingStep(plannedSteps, 0),
    thinkingStartedAt,
    status: 'thinking',
    actionable: false,
    nodeIds: references.map((item) => item.id),
    nodeReferences: references
  })
  const delay = options.wait ?? wait

  for (let index = 0; index < plannedSteps.length; index += 1) {
    await delay(STUDIO_REASONING_STEP_DELAY_MS)
    const currentStore = useStudioUiStore.getState()
    if (currentStore.scopeKey !== scopeKey) return []
    const nextSteps = advanceStudioAgentThinkingStep(plannedSteps, index + 1)
    currentStore.updateMessage(assistantMessageId, {
      thinkingSteps: nextSteps,
      reasoning: reasoningLinesFromSteps(nextSteps.filter((step) => step.status === 'done'))
    })
  }

  const currentStore = useStudioUiStore.getState()
  if (currentStore.scopeKey !== scopeKey) return []
  const thinkingCompletedAt = new Date().toISOString()
  const completedSteps = plannedSteps.map((step) => ({ ...step, status: 'done' as const }))

  if (!generatedIds.length) {
    currentStore.updateMessage(assistantMessageId, {
      status: 'complete',
      thinkingSteps: completedSteps,
      thinkingCompletedAt,
      reasoning: reasoningLinesFromSteps(completedSteps),
      content: replyWithoutGeneration(
        references.length,
        references.length === 1 ? references[0]?.title : undefined
      ),
      actionable: true
    })
    return []
  }

  currentStore.updateMessage(assistantMessageId, {
    status: 'generating',
    thinkingSteps: completedSteps,
    thinkingCompletedAt,
    reasoning: reasoningLinesFromSteps(completedSteps),
    content: `思考完成，正在生成 ${generatedIds.length} 个引用节点…`
  })
  const results = await Promise.all(generatedIds.map(async (nodeId) => {
    const latestStore = useStudioUiStore.getState()
    if (latestStore.scopeKey !== scopeKey) return false
    latestStore.updateNodeData(nodeId, { prompt: trimmed })
    try {
      return Boolean(await generateNode?.(nodeId))
    } catch {
      return false
    }
  }))

  const completedStore = useStudioUiStore.getState()
  if (completedStore.scopeKey !== scopeKey) return []
  const successCount = results.filter(Boolean).length
  const failedCount = generatedIds.length - successCount
  completedStore.updateMessage(assistantMessageId, {
    status: failedCount === generatedIds.length ? 'failed' : 'complete',
    content: failedCount
      ? `已完成 ${successCount} 个引用节点，${failedCount} 个生成失败，可在节点或任务区重试。`
      : `已按要求完成 ${successCount} 个引用节点的生成。`,
    actionable: true
  })
  return generatedIds
}
