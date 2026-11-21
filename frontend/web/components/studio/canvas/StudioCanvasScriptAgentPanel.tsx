'use client'

import { ASSISTANT_HISTORY_MORE_KEY, studioAssistantHistoryItems } from './studioAssistantHistoryMenu'

import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'

import { useCallback, useEffect, useRef, useState } from 'react'
import { FileTextOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import AgentConversationPanel from '@/components/common/agent-conversation/AgentConversationPanel'
import { AssistantSessionPanel } from '@/components/assistant/AssistantSessionPanel'
import { AssistantConfirmationSection } from '@/components/assistant/AssistantConfirmationSection'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useCanvasAssistantIdentity } from '@/hooks/studio/useCanvasAssistantIdentity'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasAssistantTransport } from '@/utils/canvas/canvasAssistantTransport'
import { canvasAssistantSnapshotSignature, captureCanvasAssistantContext, prepareCanvasAssistantContextRefresh, resolveCanvasAssistantContext } from '@/utils/studio/canvasAssistantContext'
import { assistantErrorMessage } from '@/utils/assistant/assistantRequestCoordinator'
import type { AssistantTargetRef } from '@/types/assistant-effects'
import { createCanvasAssistantEffectLocator } from '@/utils/studio/canvasAssistantEffects'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import '../composer/studio-composer.css'
import type { AssistantSessionSnapshot } from '@/types/assistant-api'
import type { EditorTextSelection } from '@/utils/quill/editorTextSelection'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioCanvasAssistantConnectionStatus, canvasAssistantConnectionReason } from '../shell/StudioCanvasAssistantConnectionStatus'
import { useCanvasAgentCollaborationDemand } from '@/hooks/studio/useCanvasAgentCollaborationDemand'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import '../shell/studio-canvas-conversation.css'
import '../shell/studio-canvas-assistant.css'
import '../shell/studio-canvas-assistant-interaction.css'
import '../shell/studio-canvas-assistant-controls.css'

/** A real node-scoped Screenwriter session using the existing Canvas identity and conversation UI. */
export function StudioCanvasScriptAgentPanel({ nodeId, title, draft, baseline, references, draftBlocked, onTargetLocated }: {
  nodeId: string; title: string; draft: string; baseline: string; references: EditorTextSelection[]; draftBlocked: boolean; onTargetLocated?: () => void
}) {
  const runtime = useCanvasProjectRuntime()
  useCanvasAgentCollaborationDemand()
  const identity = useCanvasAssistantIdentity()
  const runtimeRef = useRef(runtime)
  runtimeRef.current = runtime
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const [preference, setPreference] = useState<'MANUAL' | 'AUTO'>('MANUAL')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [modeOpen, setModeOpen] = useState(false)
  const [refreshError, setRefreshError] = useState<string | null>(null)
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const [projectionSignature, setProjectionSignature] = useState('')
  const [refreshTick, setRefreshTick] = useState(0)
  const signature = useRef('')
  const alive = useRef(true)
  const targetLocatedRef = useRef(onTargetLocated)
  targetLocatedRef.current = onTargetLocated
  const refreshing = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const effectLocator = useRef<ReturnType<typeof createCanvasAssistantEffectLocator> | null>(null)
  useEffect(() => {
    const authIdentity = canvasAssistantTransport.identityKey()
    effectLocator.current = createCanvasAssistantEffectLocator(() => runtimeRef.current, scopeKey,
      () => alive.current && canvasAssistantTransport.identityKey() === authIdentity)
    return () => { effectLocator.current = null }
  }, [scopeKey, identity.identity])
  const locateTarget = useCallback(async (target: AssistantTargetRef, isCurrent: () => boolean) => {
    const locator = effectLocator.current
    if (!locator) throw new Error('节点定位尚未就绪')
    await locator(target, isCurrent)
    // 只在当前会话鉴权与定位成功后退出正文弹窗，让画布目标可见；草稿仍由父层确认。
    if (alive.current && isCurrent() && effectLocator.current === locator) targetLocatedRef.current?.()
  }, [])
  const onSnapshot = useCallback((snapshot: AssistantSessionSnapshot) => {
    if (!runtime || snapshot.nodeObjectId !== nodeId || useStudioUiStore.getState().scopeKey !== scopeKey) return
    const changed = canvasAssistantSnapshotSignature(snapshot, String(runtime.project?.workspace.workspaceId))
    if (changed) setProjectionSignature(scopeKey + ':' + changed)
  }, [runtime, nodeId, scopeKey])
  useEffect(() => {
    if (!runtime || !projectionSignature || !projectionSignature.startsWith(scopeKey + ':')
      || projectionSignature === signature.current || refreshing.current || dirtyRevision > 0) return
    signature.current = projectionSignature; refreshing.current = true
    void runtime.refreshWorkspace().catch((cause) => {
      if (alive.current && useStudioUiStore.getState().scopeKey === scopeKey) setRefreshError(assistantErrorMessage(cause))
    }).finally(() => {
      refreshing.current = false
      if (alive.current) setRefreshTick((value) => value + 1)
    })
  }, [runtime, projectionSignature, scopeKey, dirtyRevision, refreshTick])
  const prepareMessage = (text: string) => {
    const state = useStudioUiStore.getState()
    const node = state.nodes.find((item) => item.id === nodeId)
    if (state.scopeKey !== scopeKey || !node || draftBlocked || draft !== baseline
      || resolveStudioScriptNodeText(node.data) !== baseline) throw new Error('正文尚未保存或已更新，请核对后发送')
    for (const selection of references) {
      if (selection.index < 0 || selection.length < 1 || draft.slice(selection.index, selection.index + selection.length) !== selection.text) throw new Error('批注选段已变化，请重新选取')
    }
    if (!references.length) return text
    return `${text}\n\n本次批注针对已引用节点「${title}」的以下原文选段，请按要求修改这些选段，保留其余正文：\n${references.map((item, index) => `${index + 1}. 字符区间 [${item.index}, ${item.index + item.length})：\n${item.text}`).join('\n\n')}`
  }
  if (!runtime?.project || !identity.actorId) return <p role="status" className="p-4 text-sm">{identity.error || '正在读取画布账号与节点会话…'}</p>
  const reason = !runtime.canEdit ? '当前账号没有画布编辑权限' : undefined
  const messageReason = draftBlocked ? '请先保存或处理正文冲突，再向节点编剧发送需求。' : runtime.canEdit && !runtime.canMutate ? canvasAssistantConnectionReason(runtime.collaborationStatus, runtime.collaborationError) : undefined
  return <AssistantSessionPanel presentation="canvas"
    scope={{ moduleCode: 'SCREENWRITER', mode: 'ADAPT', workspaceId: String(runtime.project.workspace.workspaceId), nodeObjectId: nodeId }}
    conversational onLocateTarget={locateTarget}
    actorId={identity.actorId} transport={canvasAssistantTransport} disabledReason={reason} messageDisabledReason={messageReason} requiredContext
    renderMessageDisabledReason={message => draftBlocked ? <p role="status" className="m-0 text-xs">{message}</p> : <StudioCanvasAssistantConnectionStatus status={runtime.collaborationStatus} error={runtime.collaborationError} recovering={runtime.collaborationRecoverable} />}
    resolveContext={() => resolveCanvasAssistantContext(runtime, scopeKey, [nodeId], 'FIXED')}
    captureQueuedContext={() => captureCanvasAssistantContext(runtime, scopeKey, [nodeId])}
    prepareExistingContextRefresh={() => { if (draftBlocked) return Promise.reject(new Error('请先保存或处理正文冲突')); return prepareCanvasAssistantContextRefresh(runtime, scopeKey) }}
    prepareMessage={async text => prepareMessage(text)} prepareQueuedMessage={prepareMessage} contextLabel={`引用当前已保存节点「${title}」`} onSnapshot={onSnapshot}
    executionPreference={preference} onExecutionPreferenceChange={setPreference}
    threadFeedback={refreshError ? <AssistantConfirmationSection presentation="canvas" label="节点同步未完成" role="alert"
      actions={<Button type="primary" onClick={() => { void runtime.refreshWorkspace().then(() => setRefreshError(null)).catch((cause) => setRefreshError(assistantErrorMessage(cause))) }}>重新读取节点</Button>}>
      <p>{refreshError}</p>
    </AssistantConfirmationSection> : null}
    contextSlot={<ul className="canvas-assistant-references" aria-label="本次引用的素材"><li className="canvas-assistant-reference"><StudioCanvasTooltip title={`引用已保存的文本节点：${title}`}><span className="canvas-assistant-reference__body"><FileTextOutlined aria-hidden /><span>{title}</span></span></StudioCanvasTooltip></li></ul>}
    renderDisabledHint={(control, disabledReason) => disabledReason ? <StudioCanvasTooltip title={disabledReason}>{control}</StudioCanvasTooltip> : control}
    composerTools={({ disabled, changeExecutionPreference }) => <div className="canvas-agent-composer__tools"><StudioComposerChip label="节点编剧" icon={<StudioIconGlyph name="assistantCraft" />} arrow={false} disabled /><StudioCanvasDropdownPopover
      items={[{ key: 'MANUAL', label: '手动生成 · 收费前确认', icon: <StudioIconGlyph name="manual" /> }, { key: 'AUTO', label: '自动生成 · 授权内执行', icon: <StudioIconGlyph name="auto" /> }]}
      activeKey={preference} open={modeOpen} onOpenChange={setModeOpen} placement="topLeft" getPopupContainer={getStudioComposerPopupContainer} ariaLabel="节点编剧生成模式"
      onSelect={(key) => { if (key === 'AUTO' || key === 'MANUAL') void changeExecutionPreference(key) }}
      trigger={<StudioCanvasTooltip title={preference === 'AUTO' ? '自动生成' : '手动生成'}><StudioComposerChip disabled={disabled} label={preference === 'AUTO' ? '自动生成' : '手动生成'} icon={<StudioIconGlyph name={preference === 'AUTO' ? 'auto' : 'manual'} />} arrow={false} className="canvas-agent-tool-select" active={modeOpen} aria-label="节点编剧生成模式" aria-haspopup="menu" aria-expanded={modeOpen} /></StudioCanvasTooltip>} />
    </div>}
    renderShell={(view) => <AgentConversationPanel className="canvas-agent-panel canvas-assistant-panel" title={view.displaySnapshot?.title || view.snapshot?.title || '节点编剧'} showBrandOrb={false} followContentResize
      scopeKey={view.displaySnapshot?.sessionId || view.snapshot?.sessionId || nodeId} updateToken={(view.displaySnapshot?.sessionId || view.snapshot?.sessionId || 'new') + ':' + view.updateToken} initialLoading={view.loading && !view.hasConversation}
      headerActions={<div className="canvas-agent-header"><StudioCanvasTooltip title="新对话"><button type="button" aria-label="新对话" disabled={view.picker.disabled} onClick={view.newSession}><StudioIconGlyph name="newChat" /></button></StudioCanvasTooltip>
            <StudioCanvasDropdownPopover items={studioAssistantHistoryItems(view.picker, <StudioIconGlyph name="history" />)} activeKey={view.picker.value}
          open={historyOpen} onOpenChange={setHistoryOpen} onSelect={(key) => { if (key === ASSISTANT_HISTORY_MORE_KEY) view.picker.onLoadMore?.(); else view.picker.onChange(key) }} ariaLabel="节点历史对话"
          trigger={<StudioCanvasTooltip title="历史对话"><button type="button" aria-label="节点历史对话" disabled={view.picker.disabled || (!view.picker.options.length && !view.picker.hasMore)}><StudioIconGlyph name="history" /></button></StudioCanvasTooltip>} />
      </div>}
      composer={view.composer}>{view.thread}</AgentConversationPanel>}
  />
}
