'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CloseOutlined, RobotOutlined } from '@ant-design/icons'
import { Tooltip, message } from 'antd'
import agentIconRaw from '@/assets/img/home/agent-icon.svg'
import skillIconRaw from '@/assets/img/icon/skill.svg'
import { assetUrl } from '@/utils/assetUrl'
import { AssistantConversationContent } from '@/components/assistant/AssistantConversationTimeline'
import { AssistantSessionPanel, type AssistantComposerDraftStore } from '@/components/assistant/AssistantSessionPanel'
import AgentConversationPanel from '@/components/common/agent-conversation/AgentConversationPanel'
import { ComposerSendGlyph } from '@/components/common/composer-send/ComposerSendButton'
import { useUserStore } from '@/stores/user'
import { useCreationStore } from '@/stores/creation'
import { storyScriptOriginalTextForApi } from '@/utils/htmlPlain'
import { getStoryScriptServerBaseline } from '@/utils/storyScriptPersistence'
import type { AssistantMessage, AssistantSessionSnapshot } from '@/types/assistant-api'
import { claimHomeScreenplayRequest, clearHomeScreenplayRequest, type HomeScreenplayRequest } from '@/utils/homeComposerScreenplay'
import { resolveAssistantScriptContext } from '@/utils/assistant/assistantContext'
import { flowScreenplayTransport } from '@/utils/assistant/flowScreenplayTransport'
import { createAssistantScriptAdoption } from '@/utils/assistant/assistantScriptAdoption'
import { createAssistantScriptTarget } from '@/utils/assistant/assistantScriptTarget'
import { userSkillRuntimeRunHistory } from '@/utils/business/skill'
import type { EditorTextSelection } from '@/utils/quill/editorTextSelection'
import { historyRunMessages, mergeHydratedMessages } from '@/utils/storyScriptAgentMessages'
import { resolveStoryScriptAgentMessagePresentation } from '@/utils/storyScriptAgentMessagePresentation'
import { storyScriptAgentResponseForDisplay } from '@/utils/storyScriptAgentReference'
import type { StoryScriptAgentViewMessage } from '@/hooks/useStoryScriptAgent'
import '@/components/assistant/assistant.css'
import '@/components/studio/shell/studio-canvas-assistant.css'
import './story-script-agent.css'

const agentIconUrl = assetUrl(agentIconRaw)
const skillIconUrl = assetUrl(skillIconRaw)

interface StoryScriptEditConversationProps {
  id: string
  state: 'idle' | 'open' | 'collapsed'
  projectId: number | null
  episodeId: number | null
  references: EditorTextSelection[]
  scriptEmpty: boolean
  initialRequest?: HomeScreenplayRequest
  onClearReferences: () => void
  onApplyHistoricalScript: (content: string, references?: EditorTextSelection[]) => void
  onLocateEditor: () => boolean
  onClose: () => void
}

function ScriptConversationHistory({ projectId, episodeId, onApply }: {
  projectId: number
  episodeId: number
  onApply: StoryScriptEditConversationProps['onApplyHistoricalScript']
}) {
  const [request, setRequest] = useState<{ beforeRunId?: number }>({})
  const [history, setHistory] = useState<StoryScriptAgentViewMessage[]>([])
  const [nextPage, setNextPage] = useState<number>()
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let current = true
    // The retired screenplay entry still has an authenticated history endpoint.
    void userSkillRuntimeRunHistory({ projectId, episodeId, skillCode: 'screenplay', pageSize: 20, ...request })
      .then(page => {
        if (!current) return
        const runs = page.data.filter(run => Number.isSafeInteger(Number(run.runId)) && Number(run.runId) > 0)
        setHistory(previous => mergeHydratedMessages(runs.flatMap(historyRunMessages), previous))
        const earliest = runs[0] ? Number(runs[0].runId) : undefined
        setNextPage(page.hasMore && earliest && (request.beforeRunId === undefined || earliest < request.beforeRunId) ? earliest : undefined)
      })
      .catch(() => { if (current) setFailed(true) })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [projectId, episodeId, request])

  const read = (beforeRunId?: number) => {
    setLoading(true)
    setFailed(false)
    setRequest({ beforeRunId })
  }

  if (!history.length && !loading && !failed) return null
  return <section className="story-script-edit-history" aria-label="历史剧本对话">
    <p className="canvas-assistant-eyebrow">历史剧本对话</p>
    {loading ? <p className="canvas-assistant-muted" role="status">正在读取历史剧本对话…</p> : null}
    {failed ? <button className="canvas-assistant-text-link" type="button" onClick={() => read(request.beforeRunId)}>历史读取失败，重试</button>
      : nextPage && !loading ? <button className="canvas-assistant-text-link" type="button" onClick={() => read(nextPage)}>加载更早对话</button> : null}
    {history.filter(item => item.content).map(item => {
      const display = storyScriptAgentResponseForDisplay(item.content, item.references)
      const canApply = display.applicable && item.role === 'assistant' && item.partialOutputTrusted && item.status === 'complete'
        && item.responseMode === 'SCREENPLAY' && resolveStoryScriptAgentMessagePresentation(item) === 'document'
      return <article key={item.id} className={'canvas-assistant-message is-' + item.role}>
        {item.role === 'user' ? <p>{item.content}</p>
          : <AssistantConversationContent text={display.text} />}
        {canApply ? <button className="canvas-assistant-text-link" type="button" onClick={() => onApply(item.content, item.references)}>带入当前剧本</button> : null}
      </article>
    })}
  </section>
}

function ScriptEditSession({ projectId, episodeId, actorId, open, ...props }: Omit<StoryScriptEditConversationProps, 'id' | 'state' | 'projectId' | 'episodeId'> & {
  projectId: number
  episodeId: number
  actorId: string
  open: boolean
}) {
  const adoption = useMemo(() => createAssistantScriptAdoption(projectId, episodeId), [projectId, episodeId])
  const locate = useMemo(() => createAssistantScriptTarget(projectId, episodeId, props.onLocateEditor), [projectId, episodeId, props.onLocateEditor])
  const initialRequest = props.initialRequest
  const restoredRequest = useRef<string | null>(null)
  const [initialSessionId, setInitialSessionId] = useState('')
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const draftStore = useMemo<AssistantComposerDraftStore>(() => ({
    read: sessionId => drafts[sessionId] || '',
    update: (sessionId, value) => setDrafts(previous => ({
      ...previous,
      [sessionId]: typeof value === 'function' ? value(previous[sessionId] || '') : value
    }))
  }), [drafts])
  const restoreRequest = useCallback((snapshot: AssistantSessionSnapshot) => {
    if (!open || !initialRequest || restoredRequest.current === initialRequest.id) return
    restoredRequest.current = initialRequest.id
    if (snapshot.messages.some(item => item.role === 'user' && item.content === initialRequest.text)) {
      clearHomeScreenplayRequest(projectId, initialRequest.id)
      return
    }
    setDrafts(previous => ({
      ...previous,
      [snapshot.sessionId]: previous[snapshot.sessionId] || initialRequest.text
    }))
    setInitialSessionId(snapshot.sessionId)
  }, [open, initialRequest, projectId])
  const applyDraft = (reply: AssistantMessage) => {
    const draft = reply.flowScreenplayDraft
    if (!draft) return
    const baseline = getStoryScriptServerBaseline({ projectId, episodeId })
    const state = useCreationStore.getState()
    const openEpisode = state.currentProjectType === 'movie' ? 0 : state.currentEpisodeId
    if (state.currentProjectId !== projectId || openEpisode !== episodeId
      || draft.baseline !== 'script:' + baseline.scriptId + ':v' + baseline.comicVersion + ':' + baseline.contentHash
      || storyScriptOriginalTextForApi(state.formData.storyScript.content) !== baseline.originalText) {
      void message.warning('当前剧本已更新，请按最新版本重新生成草稿。')
      return
    }
    const range = draft.selection
    if (range && range.end > baseline.originalText.length) {
      void message.warning('原选段已不可用，请重新选取。')
      return
    }
    const content = range ? baseline.originalText.slice(0, range.start) + reply.content + baseline.originalText.slice(range.end)
      : reply.content
    props.onApplyHistoricalScript(content)
  }
  return <AssistantSessionPanel
    renderMessageActions={reply => reply.flowScreenplayDraft ? <button className="canvas-assistant-text-link" type="button"
      onClick={() => applyDraft(reply)}>带入当前剧本</button> : null}
    actorId={actorId}
    transport={flowScreenplayTransport}
    scope={{ moduleCode: 'SCREENWRITER', mode: 'ADAPT', projectId: String(projectId), episodeId: String(episodeId) }}
    conversational
    studioLayout
    segmentedTimeline
    composerActionMode="exclusive"
    composerSendIcon={hasContent => <ComposerSendGlyph empty={!hasContent} />}
    composerPlaceholder="输入内容"
    composerDraftStore={initialRequest ? draftStore : undefined}
    onComposerSessionReady={initialRequest ? restoreRequest : undefined}
    initialSubmission={open && initialRequest && !initialRequest.attempted && initialSessionId ? {
      id: initialRequest.id,
      sessionId: initialSessionId,
      content: initialRequest.text,
      claim: () => claimHomeScreenplayRequest(projectId, initialRequest.id),
      onAccepted: () => clearHomeScreenplayRequest(projectId, initialRequest.id)
    } : undefined}
    documentAttachmentMode="direct"
    resolveContext={props.scriptEmpty && !props.references.length ? undefined : () => resolveAssistantScriptContext(projectId, episodeId, props.references)}
    hasDraftReferences={props.references.length > 0}
    composerTools={<div className="story-script-edit-tools">
      <Tooltip title="改编流程按需读取，改编设计仅在需要设计时使用">
        <span className="story-script-edit-tool" tabIndex={0} aria-label="当前技能：剧本编辑，由服务端固定">
          <img src={skillIconUrl} alt="" width={18} height={18} />
        </span>
      </Tooltip>
      <Tooltip title="剧本编辑使用服务端默认模型">
        <span className="story-script-edit-tool" tabIndex={0} aria-label="服务端默认模型">
          <RobotOutlined aria-hidden />
          <span>默认模型</span>
        </span>
      </Tooltip>
    </div>}
    contextSlot={({ disabled, documentRefs, removeDocument }) => <>
      {props.references.length > 0 ? <div className="story-script-edit-references">
        <span>已选 {props.references.length} 个剧本选段</span>
        <button type="button" className="canvas-assistant-text-link" onClick={props.onClearReferences} disabled={disabled}>清空选段</button>
      </div> : null}
      {documentRefs.length > 0 ? <ul className="story-script-edit-documents" aria-label="待发送附件">
        {documentRefs.map(ref => ref.kind === 'DOCUMENT' ? <li key={ref.documentId}>
          <span>{ref.label || '参考文档'}</span>
          <button type="button" disabled={disabled} aria-label={`移除附件 ${ref.label || '参考文档'}`} onClick={() => removeDocument(ref)}><CloseOutlined aria-hidden /></button>
        </li> : null)}
      </ul> : null}
    </>}
    adoptionAdapter={adoption}
    onLocateTarget={locate}
    renderShell={view => <AgentConversationPanel
      open={open}
      title={view.hasConversation ? '剧本对话' : '新对话'}
      brandIcon={<img className="story-script-agent-panel__brand-icon" src={agentIconUrl} alt="" width={28} height={28} />}
      onClose={props.onClose}
      surfaceClassName="story-script-agent-panel__surface"
      messagesClassName="agent-conversation-panel__messages story-script-agent-panel__messages"
      emptySlot={!view.hasConversation && !view.loading && !view.error ? <div className="story-agent-empty">
        <img src={agentIconUrl} alt="" width={28} height={28} />
        <strong>{props.scriptEmpty ? '描述你想写的剧本' : '从一个剧本修改要求开始'}</strong>
        <p>{props.scriptEmpty ? '可以补充题材、角色和时长。草稿生成后可带入左侧编辑器。' : '可以选中剧本片段或添加参考文档，完成后将结果带入左侧编辑器。'}</p>
      </div> : null}
      scopeKey={view.displaySnapshot?.sessionId ?? 'script-edit'}
      updateToken={view.updateToken}
      initialLoading={view.loading}
      followContentResize
      composer={view.composer}
    >
      <ScriptConversationHistory projectId={projectId} episodeId={episodeId} onApply={props.onApplyHistoricalScript} />
      {view.displaySnapshot || view.loading ? view.thread : null}
    </AgentConversationPanel>}
  />
}

export function StoryScriptEditConversation({ id, state, projectId, episodeId, ...props }: StoryScriptEditConversationProps) {
  const actorId = useUserStore(state => state.token && state.user?.id ? String(state.user.id) : '')
  const open = state === 'open'
  useEffect(() => {
    if (open) document.getElementById(id)?.focus({ preventScroll: true })
  }, [id, open])
  return <aside id={id} className={`story-script-agent-panel story-script-edit-conversation canvas-assistant-panel${open ? ' is-open' : ''}`}
    aria-label="剧本对话" tabIndex={-1} inert={!open}
    onKeyDown={event => {
      if (event.key === 'Escape' && !event.defaultPrevented && event.currentTarget.contains(event.target as Node)) props.onClose()
    }}>
    {state === 'idle' ? null : projectId != null && episodeId != null && actorId ? <ScriptEditSession
      key={actorId + ':' + projectId + ':' + episodeId}
      projectId={projectId} episodeId={episodeId} actorId={actorId} open={open} {...props} />
      : <AgentConversationPanel open={open} title="剧本对话" showBrandOrb={false} onClose={props.onClose}
        surfaceClassName="story-script-agent-panel__surface" updateToken="unavailable" composer={null}>
        <p className="canvas-assistant-muted">{actorId ? '当前剧本尚未就绪。' : '请登录后编辑剧本。'}</p>
      </AgentConversationPanel>}
  </aside>
}
