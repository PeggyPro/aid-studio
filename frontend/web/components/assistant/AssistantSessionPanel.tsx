'use client'

import { ComposerSendGlyph } from '@/components/common/composer-send/ComposerSendButton'

import type { AssistantSeriesEditorAdapter } from '~/types/assistant-series'

import { useCallback, useEffect, useEffectEvent, useId, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import './assistant.css'
import { Button, Modal, Select, Tooltip } from 'antd'
import { ArrowUpOutlined, DeleteOutlined, HistoryOutlined, PaperClipOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { ASSISTANT_HISTORY_MORE_KEY, studioAssistantHistoryItems } from '@/components/studio/canvas/studioAssistantHistoryMenu'
import type { ReactNode, SetStateAction } from 'react'
import { useAssistantComposerDraft, type AssistantComposerDraftStore } from '~/hooks/assistant/useAssistantComposerDraft'
export type { AssistantComposerDraftStore } from '~/hooks/assistant/useAssistantComposerDraft'
import type { AssistantArtifact, AssistantCapabilities, AssistantContextRef, AssistantMessage, AssistantMessageReference, AssistantModelSelection, AssistantScope, AssistantSessionSnapshot } from '~/types/assistant-api'
import { assistantModelSelectionWire } from '~/utils/assistant/assistantModelSelections'
import type { AssistantTargetRef } from '~/types/assistant-effects'
import type { AssistantAdoptionAdapter, AssistantAdoptionReceipt } from '~/types/assistant-artifact'
import type { AssistantTransport } from '~/utils/business/assistant'
import { useAssistantSession } from '~/hooks/assistant/useAssistantSession'
import { assistantCommandId, assistantDigest, assistantErrorCode, assistantErrorMessage, type AssistantErrorPresentation } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantRunBudgetInput } from '~/utils/assistant/assistantBudget'
import { assistantContextIdentity, assistantContextWire } from '~/utils/assistant/assistantContext'
import { AssistantSourceImportPanel } from './AssistantSourceImportPanel'
import { AssistantSourcesPanel } from './AssistantSourcesPanel'
import { AssistantArtifactsPanel } from './AssistantArtifactsPanel'
import { AssistantStepHistory } from './AssistantStepHistory'
import { AssistantQuestionDeck } from './AssistantQuestionDeck'
import { AssistantApprovalCard } from './AssistantApprovalCard'
import { AssistantTaskCard } from './AssistantResultCards'
import { AssistantEffectLocator } from '~/utils/assistant/assistantEffects'
import { AssistantComposerQueue, assistantQueuedRejectionIsFinal } from '~/utils/assistant/assistantComposerQueue'
import { assistantComposerReceiptMatches, assistantComposerSettlement, type AssistantComposerCommand } from '~/utils/assistant/assistantComposerCommand'
import type { AssistantDispatchReceipt } from '~/utils/assistant/assistantSessionStore'
import { AssistantConversationContent, AssistantConversationTimeline } from './AssistantConversationTimeline'
import AgentConversationViewport from '@/components/common/agent-conversation/AgentConversationViewport'
import { StudioCanvasAssistantLatest } from '@/components/studio/shell/StudioCanvasAssistantLatest'
import { AssistantRunActivity } from './AssistantRunActivity'
import { assistantCanResume, assistantOperationFailedPause, assistantResumeCopy, assistantResumeStillCurrent } from '~/utils/assistant/assistantPausedResume'
import { assistantComposerPolicy } from '~/utils/assistant/assistantComposerPolicy'
import { assistantContextRecoveryAction, assistantConversationContextBlocked } from '~/utils/assistant/assistantConversationPresentation'
import { assistantReadSourceScopeBlocked } from '~/utils/assistant/assistantReadSourceRecovery'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { canvasAssistantComposerState } from '~/utils/studio/canvasAssistantComposerState'
import { useCanvasAssistantContextSync } from '~/hooks/studio/useCanvasAssistantContextSync'
import { assistantContextChangeText } from '~/utils/assistant/assistantContextChange'

export interface AssistantSessionPickerProps {
  onDelete?: (sessionId: string) => void
  deleteDisabled?: boolean
  value?: string
  options: { value: string; label: string }[]
  loading: boolean
  disabled: boolean
  onChange: (sessionId: string) => void
  /** Custom shells must expose this action when older sessions are available. */
  hasMore?: boolean
  onLoadMore?: () => void
  error?: string | null
}

export interface AssistantConversationView {
  snapshot: AssistantSessionSnapshot; capabilities: AssistantCapabilities; store: AssistantSessionStore; busy: boolean
  pendingPrompt?: string; pendingState?: 'sending' | 'accepted' | 'unknown'; records: ReactNode
  executionPreference?: 'MANUAL' | 'AUTO'; onBudgetMode?: (value: 'MANUAL' | 'AUTO') => void
  canRefreshContext: boolean; refreshingContext: boolean; refreshContext: () => void
}

interface AssistantMessageEditorView { id: string; value: string; disabled: boolean; active?: boolean; idle?: boolean; placeholder: string; onChange: (value: string) => void; onSubmit: () => void }
function AssistantMessageEditorSlot({ render, view }: { render: (view: AssistantMessageEditorView) => ReactNode; view: AssistantMessageEditorView }) { return render(view) }

interface AssistantComposerClaim {
  sessionId: string
  revision: number
  content: string
  clientRequestId?: string
}


export interface AssistantSessionPanelProps {
  scope: AssistantScope
  actorId: string
  transport?: AssistantTransport
  conversational?: boolean
  /** Reuse the canvas transcript and composer surface without changing workflow policies. */
  studioLayout?: boolean
  /** Independent hosts may use per-step history without canvas business integration. */
  segmentedTimeline?: boolean
  /** Scope imperative confirmations to the embedding workspace's dialog materials. */
  modalRootClassName?: string
  composerSendIcon?: (hasContent: boolean) => ReactNode
  composerStopIcon?: ReactNode
  /** A conversation host can keep one primary action; active drafts remain local until it returns to send. */
  composerActionMode?: 'exclusive'
  /** Host opt-in: volatile status/error feedback belongs to the transcript, not the draft editor. */
  composerFeedbackPlacement?: 'thread'
  /** Private canvas presentation; shared hosts retain their default rendering. */
  presentation?: 'canvas'
  onReferencesConsumed?: (refs: AssistantContextRef[]) => void
  /** Re-select only authorized historical references after explicitly ending a stale question. */
  onRestoreReferences?: (refs: AssistantMessageReference[], sessionId: string) => void
  contextLabel?: string
  /** Editable references and selections only; host status/recovery belongs in threadFeedback. */
  contextSlot?: ReactNode | ((view: { disabled: boolean; interjecting: boolean; documentRefs: readonly AssistantContextRef[]; removeDocument: (ref: AssistantContextRef) => void }) => ReactNode)
  /** Host-owned recovery UI in the transcript, including while answering a question. */
  threadFeedback?: ReactNode
  hasDraftReferences?: boolean
  resolveContext?: () => Promise<AssistantContextRef[]>
  /** Synchronous saved identities only; adding to the local queue must not save or send. */
  captureQueuedContext?: () => AssistantContextRef[]
  /** Save current workspace before explicitly refreshing original server-bound targets. */
  prepareExistingContextRefresh?: () => Promise<void>
  requiredContext?: boolean
  prepareMessage?: (text: string) => Promise<string>
  /** Pure synchronous formatting/validation captured with the local queue's fixed references. */
  prepareQueuedMessage?: (text: string) => string
  renderMessageEditor?: (view: AssistantMessageEditorView) => ReactNode
  /** Host-owned result card; undefined preserves the standard artifact renderer. */
  renderArtifact?: (artifact: AssistantArtifact) => ReactNode | undefined
  /** 仅收到刚由 effect/resolve 鉴权的 currentTargetRef；不接受产物猜测目标。 */
  onLocateTarget?: (target: AssistantTargetRef, isCurrent: () => boolean) => void | Promise<void>
  renderSessionPicker?: (props: AssistantSessionPickerProps) => ReactNode
  disabledReason?: string
  messageDisabledReason?: string
  /** Presentation override only; messageDisabledReason still gates all sending. */
  renderMessageDisabledReason?: (reason: string) => ReactNode
  /** An embedding workflow may own a context-preparation error and its recovery UI. */
  isContextErrorHandled?: (error: unknown) => boolean
  renderDisabledHint?: (control: ReactNode, reason?: string) => ReactNode
  onSnapshot?: (snapshot: AssistantSessionSnapshot) => void
  onComposerSessionReady?: (snapshot: AssistantSessionSnapshot) => void
  /** Runs in the user's send gesture, before asynchronous preparation (e.g. browser notification consent). */
  onMessageSubmit?: () => void
  renderMessageActions?: (message: AssistantMessage) => ReactNode
  adoptionAdapter?: AssistantAdoptionAdapter
  seriesAdapter?: AssistantSeriesEditorAdapter
  executionPreference?: 'MANUAL' | 'AUTO'
  canvasBudget?: import('~/types/assistant-budget').CanvasBudget
  onExecutionPreferenceChange?: (value: 'MANUAL' | 'AUTO') => void
  selectedSkillCode?: string
  /** Capture the host's synchronously selected Skill before React publishes the next render. */
  captureSelectedSkillCode?: () => string
  captureSelectedModels?: () => AssistantModelSelection[]
  captureSelectedTextModelCode?: () => string | undefined
  allowDocumentAttachments?: boolean
  documentAttachmentMode?: 'direct'
  composerPlaceholder?: string
  attachmentTools?: ReactNode | ((view: { disabled: boolean; openDocuments: () => void }) => ReactNode)
  startNewToken?: number
  composerDraftStore?: AssistantComposerDraftStore
  initialSubmission?: { id: string; sessionId: string; content: string; claim: () => boolean; onAccepted: () => void }
  /** The host keeps its shell and local drafts visible before the server session is ready. */
  initialization?: { enabled: boolean; onCapabilities: (capabilities: AssistantCapabilities) => void }
  composerTools?: ReactNode | ((view: { disabled: boolean; skillLocked: boolean; selectedSkillCode: string; prepareSwitch: () => Promise<boolean>; changeExecutionPreference: (value: 'MANUAL' | 'AUTO') => Promise<void> }) => ReactNode)
  renderShell?: (view: { store: AssistantSessionStore; mutationBlocked: boolean; snapshot?: AssistantSessionSnapshot | null; displaySnapshot?: AssistantSessionSnapshot | null; loading: boolean; errorPresentation: AssistantErrorPresentation | null; error?: string | null; retry: () => void; hasConversation: boolean; updateToken: string; picker: AssistantSessionPickerProps; newSession: () => void; prepareSwitch: () => Promise<boolean>; thread: ReactNode; composer: ReactNode; composerDisabled: boolean; insertComposerPrompt: (prompt: string) => void }) => ReactNode
}

const emptyDocumentRefs: AssistantContextRef[] = []

const pauseLabels: Record<string, string> = { USER_REQUEST: '用户暂停', INPUT_CANCELLED: '已取消提问', INPUT_DISMISSED: '已取消提问', INPUT_SUSPENDED: '问答已暂停', USER_SUSPEND: '问答已暂停', BUDGET_EXCEEDED: '预算不足', WAITING_RECONCILIATION: '正在核实上游结果', OPERATION_FAILED: '本步执行失败', OPERATION_PREPARATION_REJECTED: '执行条件未满足' }
const runLabels: Record<string, string> = { READY: '等待执行', WAITING_APPROVAL: '等待确认', WAITING_INPUT: '等待回答', RUNNING: '运行中', STOPPING: '正在停止后续执行', PAUSING: '正在暂停后续执行', RECONCILING: '正在核实在途结果', PAUSED: '已暂停', STOPPED: '已停止', CANCELLED: '已取消', COMPLETED: '已完成', SUCCEEDED: '已完成', FAILED: '运行失败', PLANNING: '正在规划' }

export function AssistantSessionPanel(props: AssistantSessionPanelProps) {
  const session = useAssistantSession(props.scope, props.actorId, props.transport, Boolean(props.startNewToken), props.initialization?.enabled ?? true)
  const onCapabilities = props.initialization?.onCapabilities
  useEffect(() => { if (session.capabilities) onCapabilities?.(session.capabilities) }, [session.capabilities, onCapabilities])
  const conversational = props.conversational === true
  const composerId = useId()
  const [includeContext, setIncludeContext] = useState(props.requiredContext === true)
  const [documentDrafts, setDocumentDrafts] = useState<Record<string, AssistantContextRef[]>>({})
  const [refreshingContext, setRefreshingContext] = useState(false)
  const [sending, setSending] = useState(false)
  const [resuming, setResuming] = useState(false)
  const resumeFlight = useRef(false)
  const [pendingMessage, setPendingMessage] = useState<{ sessionId: string; clientRequestId: string; content: string; afterSeq: number; status: 'sending' | 'accepted' | 'unknown' } | null>(null)
  const sendFlight = useRef(false)
  const composerRevision = useRef(0)
  const consumedComposer = useRef<AssistantComposerClaim | null>(null)
  const directComposer = useRef<{ command: AssistantComposerCommand; claim: AssistantComposerClaim; documentRefs: AssistantContextRef[]; sentRefs: AssistantContextRef[]; precleared: boolean; consumeReferences: boolean } | null>(null)
  const [referenceOpen, setReferenceOpen] = useState(false)
  const [referenceSelectionActivation, setReferenceSelectionActivation] = useState(0)
  const openReferenceDocuments = () => {
    if (props.allowDocumentAttachments === false) return
    setReferenceSelectionActivation(value => value + 1)
    setReferenceOpen(true)
  }
  const [localError, setLocalError] = useState<string | null>(null)
  const [localNotice, setLocalNotice] = useState<string | null>(null)
  const snapshot = session.snapshot
  const displaySnapshot = snapshot ?? session.rememberedTranscript
  const run = snapshot?.activeRun
  const draft = useAssistantComposerDraft(displaySnapshot?.sessionId || '', props.composerDraftStore)
  const { text, setText } = draft
  const documentSessionId = snapshot?.sessionId || ''
  const documentRefs = draft.transitioning ? emptyDocumentRefs : documentDrafts[documentSessionId] || emptyDocumentRefs
  const setDocumentRefs = (value: SetStateAction<AssistantContextRef[]>) => setDocumentDrafts(previous => ({ ...previous,
    [documentSessionId]: typeof value === 'function' ? value(previous[documentSessionId] || []) : value }))
  const sourceImport = props.allowDocumentAttachments !== false && snapshot && session.capabilities?.contextKinds.includes('DOCUMENT') ? {
    key: ['source-import', session.store.api.transport.storageNamespace?.() || session.store.api.transport.kind, session.store.api.transport.identityKey(), props.actorId, snapshot.sessionId].join(':'),
    store: session.store, sessionId: snapshot.sessionId, actorId: props.actorId
  } : null
  const directAttachments = props.documentAttachmentMode === 'direct'
  const [uploadingDocumentSession, setUploadingDocumentSession] = useState('')
  const uploadingDocument = Boolean(documentSessionId && uploadingDocumentSession === documentSessionId)
  const setUploadingDocument = (busy: boolean) => setUploadingDocumentSession(previous => busy ? documentSessionId : previous === documentSessionId ? '' : previous)
  const addDocument = (ref: AssistantContextRef) => {
    if (session.store.getSnapshot().snapshot?.sessionId !== documentSessionId) return
    composerRevision.current += 1
    setDocumentRefs(current => current.some(item => item.kind === 'DOCUMENT' && ref.kind === 'DOCUMENT' && item.documentId === ref.documentId) ? current : [...current, ref])
  }
  const removeDocument = (ref: AssistantContextRef) => {
    composerRevision.current += 1
    setDocumentRefs(current => current.filter(item => item !== ref))
  }
  const contextBlocked = Boolean(snapshot && (assistantConversationContextBlocked(snapshot)
    || props.presentation === 'canvas' && assistantReadSourceScopeBlocked(snapshot)
    || session.contextConflict?.sessionId === snapshot.sessionId && session.contextConflict.runId === run?.runId))
  const contextRecovery = snapshot ? assistantContextRecoveryAction(snapshot) : 'UNAVAILABLE'
  const queue = useMemo(() => new AssistantComposerQueue(session.store.recoveryScopeKey + ':composer-queue:' + (snapshot?.sessionId || 'unopened'), snapshot?.sessionId || 'unopened'), [session.store, snapshot?.sessionId])
  const queued = useSyncExternalStore(queue.subscribe, queue.getSnapshot, queue.getServerSnapshot)
  useEffect(() => queue.activate(), [queue])
  useEffect(() => {
    const result = session.lastCommandResult
    if (result?.action !== 'send' || result.body.sessionId !== snapshot?.sessionId) return
    if (queued.items.some(item => item.commandId === result.body.clientRequestId)) setPendingMessage(current => current && current.sessionId === result.body.sessionId && current.clientRequestId === result.body.clientRequestId ? { ...current, status: 'accepted' } : current)
    try { queue.accepted(String(result.body.clientRequestId)) }
    catch (cause) { session.store.reportError(cause) }
  }, [session.lastCommandResult, snapshot?.sessionId, queue, queued.items, session.store])
  useEffect(() => {
    const rejected = session.lastRejectedCommand
    if (rejected?.action !== 'send' || rejected.sessionId !== snapshot?.sessionId || !assistantQueuedRejectionIsFinal(rejected.errorCode)) return
    if (!queued.items.some(item => item.commandId === rejected.clientRequestId)) return
    setPendingMessage(current => current?.sessionId === rejected.sessionId && current.clientRequestId === rejected.clientRequestId ? null : current)
    try { queue.rejected(rejected.clientRequestId) }
    catch (cause) { session.store.reportError(cause) }
  }, [session.lastRejectedCommand, snapshot?.sessionId, queue, queued.items, session.store])
  const openedToken = useRef<number | null>(null)
  const openSession = useRef<() => Promise<void>>(async () => {})
  useEffect(() => {
    if (props.disabledReason || props.initialization && (!props.initialization.enabled || !session.capabilities?.freeCanvasAvailable) || session.loading || session.error || !session.capabilities || !session.writable || session.busy || session.pendingCommand) return
    const token = props.startNewToken || 0
    if (openedToken.current === token) return
    // A restored initial session consumes the initialization token; later history reads must not create a session.
    if (snapshot && !token) { openedToken.current = token; return }
    openedToken.current = token
    void openSession.current()
  }, [props.disabledReason, props.initialization, session.loading, session.error, session.capabilities, session.writable, session.busy, session.pendingCommand, snapshot, session.store, props.startNewToken])
  const locateTarget = props.onLocateTarget
  const locator = useMemo(() => {
    const sessionId = snapshot?.sessionId, actorId = props.actorId
    return locateTarget && sessionId ? new AssistantEffectLocator(session.store.api, () => {
      const current = session.store.getSnapshot().snapshot
      return current?.sessionId === sessionId && current.actorId === actorId ? current : null
    }, locateTarget) : null
  }, [session.store, locateTarget, snapshot?.sessionId, props.actorId])
  useEffect(() => { locator?.activate(); return () => locator?.dispose() }, [locator])
  const pausedInterjection = run?.status === 'PAUSED'
  const waitingForApproval = !!run && ['WAITING_APPROVAL', 'PAUSED'].includes(run.status)
  const interjecting = !!run && !run.stopRequested && ['RUNNING', 'PAUSING', 'RECONCILING', 'PAUSED', 'WAITING_APPROVAL'].includes(run.status)
  const hasComposerReferences = Boolean(props.hasDraftReferences || documentRefs.length || (conversational || includeContext) && props.resolveContext)
  const queueInterjection = interjecting && (hasComposerReferences || !!props.renderShell && !waitingForApproval)
  const pendingInSession = conversational && pendingMessage?.sessionId === snapshot?.sessionId ? pendingMessage : null
  const pendingRecorded = !!pendingInSession && !!snapshot?.messages.some(item => item.role === 'user' && item.seq > pendingInSession.afterSeq && item.content.trim() === pendingInSession.content.trim())
  const pendingPrompt = pendingInSession && !pendingRecorded ? pendingInSession.content : undefined
  const awaitingMessageSync = pendingInSession?.status === 'accepted' && !pendingRecorded
  const canSend = !awaitingMessageSync && (!run || interjecting || ['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(run.status))
  const skillLocked = !!run && !['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(run.status)
  const busy = draft.transitioning || session.busy || sending || resuming || refreshingContext || Boolean(props.disabledReason)
  const mutationBlocked = busy || !session.writable || !!session.queuedControl
  const controlBlocked = draft.transitioning || !session.writable || Boolean(props.disabledReason) || !!session.queuedControl
  const contextSync = useCanvasAssistantContextSync({ enabled: props.presentation === 'canvas', store: session.store,
    prepare: props.prepareExistingContextRefresh, blocked: draft.transitioning || sending || resuming || refreshingContext || Boolean(props.disabledReason) })
  useEffect(() => {
    if (props.presentation !== 'canvas' || !assistantContextChangeText(session.error)) return
    session.store.reportContextConflict({ errorCode: 'ASSISTANT_CONTEXT_STALE', message: session.error })
  }, [props.presentation, session.error, session.store])
  const composerPolicy = assistantComposerPolicy({ conversational, hasShell: !!props.renderShell, hasSession: !!snapshot,
    runStatus: run?.status, hasDraftReferences: hasComposerReferences, disabledReason: props.disabledReason, messageDisabledReason: props.messageDisabledReason,
    canSend, mutationBlocked, pendingCommand: !!session.pendingCommand, awaitingMessageSync: !!awaitingMessageSync, sending })
  // Drafting is local; dispatch and uploads keep the authoritative session guards.
  if (draft.transitioning || props.initialization && !snapshot) composerPolicy.editorDisabled = Boolean(props.disabledReason)
  if (draft.transitioning) composerPolicy.submitDisabled = true
  const exclusiveComposerActions = props.composerActionMode === 'exclusive'
  const primaryIsStop = Boolean((props.initialization || props.presentation === 'canvas' || exclusiveComposerActions) && (skillLocked && !waitingForApproval || sending || awaitingMessageSync || session.pendingCommand))
  const showComposerStop = Boolean(props.renderShell && (primaryIsStop || !exclusiveComposerActions && !props.initialization && props.presentation !== 'canvas' && run?.allowedActions.includes('STOP')))
  const showComposerSend = exclusiveComposerActions ? !showComposerStop : !primaryIsStop || composerPolicy.queueing && Boolean(text.trim())
  const canvasComposer = props.presentation === 'canvas' ? canvasAssistantComposerState(snapshot,
    busy || session.loading || !session.writable || !!session.pendingCommand || !!pendingPrompt || !!awaitingMessageSync
      || !!session.offline || !!session.error || !!localError || !!props.messageDisabledReason
      || Boolean(session.reasoning?.live || session.liveReply?.live || session.progress)) : null
  const hint = (control: ReactNode, reason = props.disabledReason) => props.renderDisabledHint ? props.renderDisabledHint(control, reason) : control
  const appliedCommand = useRef('')
  const adoptionAdapter = props.adoptionAdapter
  useEffect(() => {
    const result = session.lastCommandResult
    if (!adoptionAdapter || result?.action !== 'adopt' || appliedCommand.current === result.body.clientRequestId) return
    appliedCommand.current = String(result.body.clientRequestId)
    let active = true
    void adoptionAdapter.applied(result.result as AssistantAdoptionReceipt, result.body).then((notice) => { if (active && notice) setLocalNotice(notice) })
      .catch((cause) => { if (active) setLocalError('服务端已采用，编辑器刷新未完成：' + assistantErrorMessage(cause)) })
    return () => { active = false }
  }, [session.lastCommandResult, adoptionAdapter])
  const onSnapshot = props.onSnapshot
  useEffect(() => { if (snapshot) onSnapshot?.(snapshot) }, [snapshot, onSnapshot])
  const onComposerSessionReady = props.onComposerSessionReady
  useEffect(() => {
    if (snapshot && !draft.transitioning && !session.loading) onComposerSessionReady?.(snapshot)
  }, [snapshot, draft.transitioning, session.loading, onComposerSessionReady])
  const editComposer = (value: string) => { composerRevision.current += 1; setText(value) }
  const claimComposer = (): AssistantComposerClaim | null => {
    if (!snapshot) return null
    const revision = composerRevision.current
    if (consumedComposer.current?.sessionId === snapshot.sessionId && consumedComposer.current.revision === revision) return null
    if (!text.trim()) return null
    if (text.length > 100000) { setLocalError('消息需为 1 到 100000 个字符'); return null }
    const claim = { sessionId: snapshot.sessionId, revision, content: text }
    consumedComposer.current = claim
    return claim
  }
  const releaseComposer = (claim: AssistantComposerClaim) => {
    const consumed = consumedComposer.current
    const current = session.store.getSnapshot().snapshot
    if (consumed?.sessionId === claim.sessionId && consumed.revision === claim.revision
      && composerRevision.current === claim.revision && current?.sessionId === claim.sessionId) consumedComposer.current = null
  }
  // The stable settlement reads the latest session-bound draft setter and selection.
  const composerView = useRef({ text, setText, documentRefs, documentSessionId, conversational, onReferencesConsumed: props.onReferencesConsumed })
  useLayoutEffect(() => { composerView.current = { text, setText, documentRefs, documentSessionId, conversational, onReferencesConsumed: props.onReferencesConsumed } })
  const settleComposer = useCallback((receipt?: AssistantDispatchReceipt) => {
    const direct = directComposer.current
    if (!direct) return
    if (receipt && assistantComposerReceiptMatches(direct.command, receipt) && receipt.dispatchState === 'DISPATCHED') direct.command.everDispatched = true
    const state = session.store.getSnapshot()
    const outcome = assistantComposerSettlement(direct.command, state, receipt)
    if (!outcome) {
      if (receipt && assistantComposerReceiptMatches(direct.command, receipt) && receipt.dispatchState === 'DISPATCHED') {
        setPendingMessage(current => current?.sessionId === direct.command.sessionId && current.clientRequestId === direct.command.clientRequestId ? { ...current, status: 'unknown' } : current)
      }
      return
    }
    // Effects and the awaiting send can observe the same event; consume it before UI callbacks.
    directComposer.current = null
    setPendingMessage(current => current?.sessionId === direct.command.sessionId && current.clientRequestId === direct.command.clientRequestId
      ? outcome === 'ACCEPTED' ? { ...current, status: 'accepted' } : null : current)
    const { claim } = direct, view = composerView.current
    if (consumedComposer.current !== claim || composerRevision.current !== claim.revision
      || state.snapshot?.sessionId !== claim.sessionId || view.documentSessionId !== claim.sessionId) return
    if (outcome !== 'ACCEPTED') {
      consumedComposer.current = null
      if (direct.precleared) {
        composerRevision.current += 1
        view.setText(current => current === '' ? claim.content : current)
      }
      return
    }
    if (!direct.precleared) view.setText(current => current === claim.content ? '' : current)
    if (direct.consumeReferences && view.documentRefs === direct.documentRefs) {
      setDocumentDrafts(previous => previous[claim.sessionId] && previous[claim.sessionId] !== direct.documentRefs ? previous : { ...previous,
        [claim.sessionId]: direct.documentRefs.filter(ref => !direct.sentRefs.some(sent => assistantDigest(assistantContextIdentity(sent)) === assistantDigest(assistantContextIdentity(ref)))) })
      try { view.onReferencesConsumed?.(direct.sentRefs) }
      catch (cause) { setLocalError('消息已保存，引用显示更新未完成：' + assistantErrorMessage(cause)) }
    }
    const result = state.lastCommandResult?.result as { status?: string; nextAction?: string } | undefined
    setLocalNotice(view.conversational ? null : result?.nextAction === 'CANCEL_FOR_REPLAN' ? '调整已保存，正在核实原任务和费用；确认后应用调整并保持暂停。' : result?.status === 'PAUSED' && result.nextAction === 'REPLANNING_REQUIRED'
      ? '补充已保存，当前运行仍暂停。旧问题、计划与未执行报价已失效；点击继续后重新规划并核对授权。'
      : result?.nextAction === 'QUEUED_FOR_NEXT_AGENT_TURN' ? '补充已保存，将在后续主助手轮次读取；原授权和在途任务保持原范围。' : '消息已保存，后续执行仍需满足运行授权。')
  }, [session.store])
  useEffect(() => { settleComposer() }, [session.lastCommandResult, session.lastRejectedCommand, snapshot?.sessionId, settleComposer])
  const switchSession = async (target?: string) => {
    if (target && target === snapshot?.sessionId || session.busy || session.pendingCommand) return
    const token = draft.begin(target)
    if (!token) return
    composerRevision.current += 1; consumedComposer.current = null; directComposer.current = null
    setLocalNotice(null); setLocalError(null); setReferenceOpen(false)
    const before = snapshot?.sessionId
    let selected: string | undefined
    try {
      selected = target ? await session.store.select(target) : await session.store.create()
    } catch (cause) { setLocalError(assistantErrorMessage(cause)) }
    finally {
      const destination = selected ?? session.store.getSnapshot().snapshot?.sessionId
      const changed = target ? destination === target : Boolean(destination && destination !== before)
      draft.finish(token, changed ? destination : undefined)
      if (changed) { setPendingMessage(null); setIncludeContext(props.requiredContext === true) }
      else if (!target) setLocalNotice('新对话尚未打开；输入草稿已保留，重试新建时恢复。')
    }
  }
  const newSession = () => switchSession()
  useLayoutEffect(() => { openSession.current = newSession })
  const deleteSession = (sessionId: string) => {
    const target = session.sessions.find(item => item.sessionId === sessionId)
    if (!target || busy || session.loading || session.pendingCommand) return
    Modal.confirm({ rootClassName: props.modalRootClassName, title: `删除“${target.title}”？`, okText: '删除对话', okButtonProps: { danger: true }, cancelText: '取消',
      content: '此对话将从历史列表中移除，已生成的素材保留。正在处理的任务需先结束。',
      onOk: async () => {
        try {
          const deletingCurrent = session.store.getSnapshot().snapshot?.sessionId === sessionId
          const deletionIdentity = session.store.api.transport.identityKey()
          if (!await session.store.deleteSession(sessionId)) throw new Error('会话暂不可删除，请等待当前操作结束后重试')
          if (session.store.api.transport.identityKey() !== deletionIdentity) return
          draft.clear(sessionId)
          setDocumentDrafts(previous => { const next = { ...previous }; delete next[sessionId]; return next })
          if (deletingCurrent && !session.store.getSnapshot().snapshot) {
            composerRevision.current += 1; consumedComposer.current = null; directComposer.current = null
            setPendingMessage(null)
            await switchSession()
          }
        } catch (cause) { setLocalError(assistantErrorMessage(cause)); throw cause }
      } })
  }
  const picker: AssistantSessionPickerProps = { onDelete: session.capabilities?.sessionDeletionAvailable ? deleteSession : undefined, deleteDisabled: busy || session.loading || !!session.pendingCommand || !session.writable, value: snapshot?.sessionId, options: session.sessions.map((item) => {
    const time = item.updatedAt ? new Date(item.updatedAt) : null
    const suffix = time && Number.isFinite(time.getTime()) ? time.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) : ''
    return { value: item.sessionId, label: item.title === '新对话' && suffix ? `${item.title} · ${suffix}` : item.title }
  }), error: session.sessionPageError, hasMore: Boolean(session.nextSessionCursor), onLoadMore: () => {
    if (busy || session.loading || session.pendingCommand) return
    void session.store.loadMoreSessions()
  }, loading: session.loading, disabled: busy || session.loading || !!session.pendingCommand || !!props.initialization && (!props.initialization.enabled || !session.capabilities || !session.writable), onChange: (id) => { void switchSession(id) } }
  const changeExecutionPreference = async (preference: 'MANUAL' | 'AUTO') => {
    if (mutationBlocked || session.pendingCommand || preference === props.executionPreference) return
    setSending(true); setLocalError(null)
    try {
      if (run && !['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(run.status)) {
        let budget
        try { budget = await session.store.api.budgetDetail(run.runId) }
        catch (cause) { if (assistantErrorCode(cause) !== 'RUN_BUDGET_NOT_FOUND') throw cause }
        if (budget && budget.mode !== preference) {
          // 策略摘要包含金额、期限与模式，不能与能力范围摘要比较；范围有效性以服务端投影为准。
          if (budget.scopeState !== 'CURRENT' || !budget.availableRunBudgetScope) throw new Error('运行授权范围已变化，请先核对运行预算')
          const current = session.store.getSnapshot()
          if (current.snapshot?.sessionId !== snapshot?.sessionId || current.snapshot.activeRun?.runId !== run.runId || current.busy || current.pendingCommand) throw new Error('会话状态已变化，请重新选择生成模式')
          const runBudget = assistantRunBudgetInput(budget.availableRunBudgetScope, { ...budget, mode: preference, automaticConsent: preference === 'AUTO' }, { policy: budget })
          // 切换只改变确认方式，不增加金额、次数、期限或对象权限。
          Object.assign(runBudget, { allowedToolCodes: [...budget.allowedToolCodes], allowedModelCodes: [...budget.allowedModelCodes], allowedCapabilityCodes: [...budget.allowedCapabilityCodes], allowedMediaTypes: [...budget.allowedMediaTypes], targetRefs: structuredClone(budget.targetRefs), automaticForbiddenOperationKinds: [...budget.automaticForbiddenOperationKinds] })
          if (!await session.store.execute('reviseBudget', { runId: run.runId, expectedRunRevision: budget.runRevision,
            expectedPolicyRevision: budget.policyRevision, expectedAuthorizationRevision: budget.authorizationRevision,
            clientRequestId: assistantCommandId(), runBudget })) return
        }
      }
      props.onExecutionPreferenceChange?.(preference)
    } catch (cause) { setLocalError(assistantErrorMessage(cause)) }
    finally { setSending(false) }
  }
  const send = async (queuedContent?: string, queuedCommandId?: string, queuedRefs: AssistantContextRef[] = [], composerClaim?: AssistantComposerClaim, initialRequestId?: string): Promise<boolean> => {
    const content = queuedContent ?? composerClaim?.content ?? text
    if (!snapshot || !canSend || !content.trim() || content.length > 100000 || sendFlight.current || mutationBlocked || props.messageDisabledReason || session.pendingCommand || interjecting && queuedRefs.length) {
      if (composerClaim) releaseComposer(composerClaim)
      return false
    }
    const submittedSkillCode = props.scope.moduleCode === 'UNIVERSAL'
      ? skillLocked ? run?.selectedSkillCode : props.captureSelectedSkillCode?.() ?? props.selectedSkillCode
      : undefined
    const submittedModels = props.presentation === 'canvas' && props.scope.moduleCode === 'UNIVERSAL'
      ? assistantModelSelectionWire(skillLocked ? run?.selectedModels || [] : props.captureSelectedModels?.() || []) : []
    const submittedTextModel = props.scope.moduleCode === 'PREVIZ_3DBOX' && !interjecting
      ? props.captureSelectedTextModelCode?.() : undefined
    setSending(true); setLocalError(null)
    sendFlight.current = true
    const clientRequestId = queuedCommandId || initialRequestId || assistantCommandId()
    if (composerClaim) composerClaim.clientRequestId = clientRequestId
    let calledStore = false
    if (conversational) {
      setPendingMessage({ sessionId: snapshot.sessionId, clientRequestId, content, afterSeq: snapshot.messages.at(-1)?.seq || 0, status: 'sending' })
      // The submitted draft belongs to this message. New typing belongs to the queue.
      if (composerClaim && composerRevision.current === composerClaim.revision
        && session.store.getSnapshot().snapshot?.sessionId === composerClaim.sessionId) setText('')
    }
    const restoreUndispatchedDraft = () => {
      if (!conversational || !composerClaim) { if (composerClaim) releaseComposer(composerClaim); return }
      const consumed = consumedComposer.current
      const currentSession = session.store.getSnapshot().snapshot?.sessionId
      if (consumed?.sessionId !== composerClaim.sessionId || consumed.revision !== composerClaim.revision
        || composerRevision.current !== composerClaim.revision || currentSession !== composerClaim.sessionId) return
      // Keep identity mutations outside React state updaters, which may be replayed or abandoned.
      consumedComposer.current = null
      composerRevision.current += 1
      composerView.current.setText(current => current === '' ? composerClaim.content : current)
    }
    try {
      const refs = queuedContent !== undefined ? queuedRefs : interjecting ? [] : [...((conversational || includeContext) && props.resolveContext ? await props.resolveContext() : []),...documentRefs]
      const preparedContent = !interjecting && queuedContent === undefined && props.prepareMessage ? await props.prepareMessage(content) : content
      if (preparedContent !== content) setPendingMessage(current => current?.clientRequestId === clientRequestId ? { ...current, content: preparedContent } : current)
      if (session.store.getSnapshot().snapshot?.sessionId !== snapshot.sessionId) throw new Error('会话已切换，请重新发送')
      if (refs.some((ref) => !session.capabilities?.contextKinds.includes(ref.kind))) throw new Error('当前服务尚未开放所选内容引用')
      const current = session.store.getSnapshot().snapshot, currentRun = current?.activeRun
      const currentlyInterjecting = !!currentRun && !currentRun.stopRequested && ['RUNNING', 'PAUSING', 'RECONCILING', 'PAUSED', 'WAITING_APPROVAL'].includes(currentRun.status)
      if (!current || currentRun?.runId !== run?.runId || currentlyInterjecting !== interjecting
        || !interjecting && currentRun && !['COMPLETED', 'SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELLED'].includes(currentRun.status)) throw new Error('运行状态已变化，消息已保留，请核对后发送')
      const body = { sessionId: snapshot.sessionId,
        clientRequestId, expectedSessionRevision: current.revision,
        ...(interjecting && currentRun ? { expectedRunRevision: currentRun.stateRevision } : {}),
        parts: [{ type: 'TEXT', text: preparedContent }], contextRefs: refs.map(assistantContextWire), executionPreference: props.executionPreference || 'MANUAL',
        ...(!interjecting && props.canvasBudget ? { canvasBudget: props.canvasBudget } : {}),
        ...(submittedSkillCode ? { selectedSkillCode: submittedSkillCode } : {}),
        ...(submittedTextModel ? { selectedTextModelCode: submittedTextModel } : {}),
        ...(submittedModels.length ? { selectedModels: submittedModels } : {}) }
      if (queuedCommandId) queue.attachBody(queuedCommandId, body)
      if (composerClaim) directComposer.current = { claim: composerClaim, command: { sessionId: composerClaim.sessionId, revision: composerClaim.revision,
        content: composerClaim.content, clientRequestId, bodyDigest: assistantDigest(body), everDispatched: false }, documentRefs, sentRefs: refs, precleared: conversational, consumeReferences: !interjecting }
      calledStore = true
      const dispatch = composerClaim ? await session.store.executeWithReceipt('send', body) : null
      const accepted = dispatch ? dispatch.accepted : await session.store.execute('send', body)
      if (dispatch) settleComposer(dispatch.receipt)
      if (composerClaim) return accepted
      // Queue commands have their own fixed-body lifecycle and pending presentation.
      if (accepted) setPendingMessage(current => current?.clientRequestId === clientRequestId ? { ...current, status: 'accepted' } : current)
      else setPendingMessage(current => current?.clientRequestId === clientRequestId ? { ...current, status: 'unknown' } : current)
      return accepted
    } catch (cause) {
      if (!calledStore) {
        if (directComposer.current?.claim === composerClaim) directComposer.current = null
        restoreUndispatchedDraft()
        setPendingMessage(current => current?.clientRequestId === clientRequestId ? null : current)
      } else settleComposer()
      if (calledStore || !props.isContextErrorHandled?.(cause)) setLocalError(assistantErrorMessage(cause))
      return false
    }
    finally { sendFlight.current = false; setSending(false) }
  }
  const submitComposer = () => {
    // The editor and its keyboard shortcut follow the same primary action as the visible button.
    if (exclusiveComposerActions && showComposerStop) return
    if (draft.transitioning || uploadingDocument) return
    if (composerPolicy.queueing || queueInterjection) {
      if (composerPolicy.editorDisabled) return
      const claim = claimComposer()
      if (!claim) return
      let refs: AssistantContextRef[]
      try {
        if ((conversational || includeContext) && props.resolveContext && !props.captureQueuedContext) throw new Error('引用尚未固定，请先保存素材后加入队列')
        refs = [...(conversational || includeContext ? props.captureQueuedContext?.() || [] : []), ...documentRefs]
        const queuedText = claim.content.trim() && props.prepareQueuedMessage ? props.prepareQueuedMessage(claim.content) : claim.content
        queue.enqueue(queuedText, refs)
      }
      catch (cause) { releaseComposer(claim); setLocalError(assistantErrorMessage(cause)); return }
      // Persistence commits this draft. A presentation callback cannot release or enqueue it again.
      setDocumentRefs(current => current !== documentRefs ? current : current.filter(ref => !refs.some(saved => JSON.stringify(assistantContextIdentity(saved)) === JSON.stringify(assistantContextIdentity(ref)))))
      if (consumedComposer.current === claim && composerRevision.current === claim.revision
        && session.store.getSnapshot().snapshot?.sessionId === claim.sessionId) setText(current => current === claim.content ? '' : current)
      setLocalError(null); setLocalNotice(null)
      try { props.onReferencesConsumed?.(refs) }
      catch (cause) { setLocalError('已入队，引用显示更新未完成：' + assistantErrorMessage(cause)) }
    } else {
      if (composerPolicy.submitDisabled || sendFlight.current) return
      const claim = claimComposer()
      if (claim) {
        props.onMessageSubmit?.()
        void send(undefined, undefined, [], claim)
      }
    }
  }
  const initialSubmission = props.initialSubmission
  const initialSubmissionReady = Boolean(initialSubmission && snapshot?.sessionId === initialSubmission.sessionId
    && !snapshot?.messages.length && !run && text === initialSubmission.content
    && !composerPolicy.submitDisabled && !composerPolicy.queueing && !uploadingDocument)
  const submitInitialDraft = useEffectEvent(() => {
    if (!initialSubmission || !initialSubmissionReady || sendFlight.current) return
    const claim = claimComposer()
    if (!claim) return
    try {
      if (!initialSubmission.claim()) { releaseComposer(claim); return }
    } catch (cause) {
      releaseComposer(claim)
      setLocalError(assistantErrorMessage(cause))
      return
    }
    void send(undefined, undefined, [], claim, initialSubmission.id).then(accepted => {
      if (accepted) initialSubmission.onAccepted()
    })
  })
  useEffect(() => {
    if (initialSubmissionReady) submitInitialDraft()
  }, [initialSubmission?.id, initialSubmissionReady])
  const guideQueued = async (id: string) => {
    if (mutationBlocked || session.pendingCommand || props.messageDisabledReason || sendFlight.current) return
    const queuedItem = queued.items.find(item => item.id === id)
    if (!queuedItem || !queuedItem.body && (!canSend || interjecting && !!queuedItem.contextRefs?.length)) return
    let commandId: string | undefined
    try {
      const item = queue.begin(id); commandId = item.commandId
      const accepted = item.body ? await session.store.execute('send', item.body) : await send(item.text, commandId, item.contextRefs || [])
      const latest = session.store.getSnapshot()
      if (accepted || latest.lastCommandResult?.body.clientRequestId === commandId) queue.accepted(commandId)
      else if (latest.lastRejectedCommand?.action === 'send' && latest.lastRejectedCommand.clientRequestId === commandId && assistantQueuedRejectionIsFinal(latest.lastRejectedCommand.errorCode)
        || !queue.getSnapshot().items.find(row => row.commandId === commandId)?.body && latest.pendingCommand?.body.clientRequestId !== commandId) queue.rejected(commandId)
    } catch (cause) { setLocalError(assistantErrorMessage(cause)) }
  }
  const refreshContext = () => {
    if (!run || !snapshot || (!props.resolveContext && !props.prepareExistingContextRefresh && !documentRefs.length) || mutationBlocked || session.pendingCommand) return
    const sessionId = snapshot.sessionId, runId = run.runId
    if (contextRecovery === 'END_QUESTION') {
      Modal.confirm({ rootClassName: props.modalRootClassName, title: '保留回答并结束旧问答？', okText: '结束旧问答', cancelText: '暂不处理',
        content: '改用新引用需要结束当前问答。回答会保留；结束后保留你选中的新引用并带回可用的原引用，由你重新发送需求，不会自动生成或重放消息。',
        onOk: async () => {
          setRefreshingContext(true); setLocalError(null)
          try {
            if (!await session.store.prepareContextReplacement()) throw new Error('回答尚未可靠保存或存在未核实操作，请先处理后重试')
            const current = session.store.getSnapshot()
            if (current.snapshot?.sessionId !== sessionId || current.snapshot.activeRun?.runId !== runId || current.busy || current.pendingCommand
              || assistantContextRecoveryAction(current.snapshot) !== 'END_QUESTION') throw new Error('运行状态已变化，请重新核对')
            const refs = snapshot.messages.find(item => item.runId === runId && item.role === 'user' && item.references?.length)?.references || []
            if (await session.store.control('stop')) props.onRestoreReferences?.(refs, sessionId)
          } catch (cause) { setLocalError(assistantErrorMessage(cause)) }
          finally { setRefreshingContext(false) }
        } })
      return
    }
    if (contextRecovery !== 'REFRESH') { setLocalError('当前运行状态不能更新引用，请先恢复会话状态'); return }
    Modal.confirm({ rootClassName: props.modalRootClassName, title: '使用当前内容重新规划？', okText: '保留回答并更新', cancelText: '保留现有上下文',
      content: contextBlocked ? '你的回答会保存在本地。重新读取本轮原引用的最新内容，旧问题和待生成内容将失效；更新后保持暂停，确认继续后才重新规划。旧回答不会自动填入新的问题。' : conversational ? '保存当前编辑内容，并使用这些素材重新规划。原问题和待生成内容会更新；暂停中的创作仍保持暂停。' : '将先保存问题草稿和当前编辑内容。旧问题、计划和未执行报价随后失效；预算范围需重新核对并显式确认，不会自动继续执行。',
      onOk: async () => {
        setRefreshingContext(true); setLocalError(null)
        try {
          const before = session.store.getSnapshot()
          if (before.snapshot?.sessionId !== sessionId || before.snapshot.activeRun?.runId !== runId || before.busy || before.pendingCommand) throw new Error('当前会话或运行已变化，请重新核对')
          if (!await session.store.prepareContextReplacement()) throw new Error('问题草稿尚未保存或存在冲突，请先处理问题卡后重试')
          const preserveExistingTargets = !!props.prepareExistingContextRefresh && !documentRefs.length
            && (contextBlocked || (!conversational && !includeContext) || !props.resolveContext)
          if (preserveExistingTargets) await props.prepareExistingContextRefresh!()
          const refs = preserveExistingTargets ? [] : [...((conversational || includeContext) && props.resolveContext ? await props.resolveContext() : []),...documentRefs]
          if (!refs.length && !preserveExistingTargets) throw new Error('请先选择要引用的已保存内容')
          if (refs.some((ref) => !session.capabilities?.contextKinds.includes(ref.kind))) throw new Error('当前服务尚未开放所选内容引用')
          const current = session.store.getSnapshot()
          if (current.snapshot?.sessionId !== sessionId || current.snapshot.activeRun?.runId !== runId || current.busy || current.pendingCommand) throw new Error('当前会话或运行已变化，请重新核对')
          const accepted = await session.store.execute('refreshContext', { runId, expectedRunRevision: current.snapshot.activeRun.stateRevision,
            clientRequestId: assistantCommandId(), targetRefs: refs.map(assistantContextIdentity), ...(preserveExistingTargets ? { preserveExistingTargets: true } : {}) })
          if (accepted) {
            setLocalError(null)
            const result = session.store.getSnapshot().lastCommandResult?.result as { status?: string } | undefined
            setLocalNotice(result?.status === 'PAUSING' ? '引用调整已保存，正在核实原任务与费用；完成后以当前已保存内容重新解析引用，并保持暂停。' : result?.status === 'PAUSED' ? '已更新上下文，仍保持暂停。点击继续后重新规划，并核对预算范围及报价。' : '已更新上下文。请核对预算授权范围，再重新报价和确认执行。')
          }
        } catch (cause) { if (!props.isContextErrorHandled?.(cause)) setLocalError(assistantErrorMessage(cause)) }
        finally { setRefreshingContext(false) }
      }
    })
  }
  const resume = async () => {
    if (resumeFlight.current) return
    const latest = session.store.getSnapshot()
    const latestContextBlocked = Boolean(latest.snapshot && (assistantConversationContextBlocked(latest.snapshot)
      || latest.contextConflict?.sessionId === latest.snapshot.sessionId && latest.contextConflict.runId === latest.snapshot.activeRun?.runId))
    if (mutationBlocked || !assistantCanResume(snapshot, contextBlocked) || !assistantResumeStillCurrent(snapshot, latest, latestContextBlocked)) {
      setLocalError('会话状态已变化，请刷新会话后重新核对')
      return
    }
    resumeFlight.current = true
    setResuming(true); setLocalError(null)
    try { await session.store.control('resume') }
    catch (cause) { setLocalError(assistantErrorMessage(cause)) }
    finally { resumeFlight.current = false; setResuming(false) }
  }
  if (!props.actorId && !props.initialization) return <p className="p-4 text-sm text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">请登录后使用助手。</p>
  const toolbar = <div className="assistant-toolbar flex flex-wrap gap-2 border-b border-[var(--studio-ui-border,rgba(255,255,255,0.1))] p-3">
      {props.renderSessionPicker ? props.renderSessionPicker(picker) : <StudioCanvasDropdownPopover items={studioAssistantHistoryItems(picker, <HistoryOutlined />)} activeKey={picker.value} ariaLabel="历史会话" placement="bottomLeft"
        onSelect={key => { if (key === ASSISTANT_HISTORY_MORE_KEY) picker.onLoadMore?.(); else picker.onChange(key) }}
        trigger={<Button aria-label="历史会话" icon={<HistoryOutlined />} disabled={picker.disabled || (!picker.options.length && !picker.hasMore)}>{picker.options.find(item => item.value === picker.value)?.label || '历史对话'}</Button>} />}
      {hint(<Tooltip title="新对话"><Button aria-label="新对话" icon={<PlusOutlined />} disabled={mutationBlocked || session.loading || !!session.pendingCommand || !session.capabilities?.modules.includes(props.scope.moduleCode)} onClick={() => void newSession()} /></Tooltip>)}
      {picker.error ? <span role="status">历史对话加载失败，请重试。</span> : null}
      <Tooltip title="刷新会话"><Button aria-label="刷新会话" icon={<ReloadOutlined />} disabled={busy} onClick={() => void session.store.retryRead()} /></Tooltip>
    </div>
  const composerInput = canvasComposer ? canvasComposer.input : snapshot?.inputRequest
  const questionCard = !draft.transitioning && composerInput && (!conversational || !run?.stopRequested && ['OPEN', 'DRAFTING', 'SUSPENDED'].includes(composerInput.status)) ? <AssistantQuestionDeck key={'questions:' + composerInput.requestId + ':' + composerInput.schemaDigest} input={composerInput} store={session.store} busy={mutationBlocked} conversational={conversational} presentation={props.presentation} waitForAutosaveOnSubmit={props.presentation === 'canvas'} /> : null
  // A suspended question must not trap the user away from the editor and reference picker.
  // Keep its saved answers/resume control visible above the ordinary composer.
  const answeringQuestion = conversational && Boolean(props.renderShell && questionCard)
    && snapshot?.inputRequest?.status !== 'SUSPENDED'
  // Keep spending recovery available while paused as well as awaiting approval.
  // Unmounting also discards late reads from the previous phase.
  const showApproval = props.scope.moduleCode !== 'SPATIAL_PREVIS'
    && (!conversational || !!run && ['WAITING_APPROVAL', 'PAUSED'].includes(run.status) && run.stopRequested !== true)
  const approvalCard = !draft.transitioning && showApproval && snapshot && session.capabilities ? <AssistantApprovalCard key={'approval:' + (run?.runId || 'none')} snapshot={snapshot} capabilities={session.capabilities} store={session.store} busy={mutationBlocked} executionPreference={props.executionPreference} onBudgetMode={props.onExecutionPreferenceChange} conversational={conversational} onLocateTarget={locator?.resolve} presentation={props.presentation} /> : null
  const feedbackInThread = props.presentation === 'canvas' || props.composerFeedbackPlacement === 'thread'
  const composerFeedback = <>
      {queued.error ? <p role="alert" className="m-0 text-xs">{queued.error}</p> : null}
      {props.messageDisabledReason ? props.renderMessageDisabledReason ? props.renderMessageDisabledReason(props.messageDisabledReason) : <p role="status" className="m-0 text-xs">{props.messageDisabledReason}</p> : null}
      {props.disabledReason ? <p role="status" className="m-0 text-xs text-[var(--studio-danger,#fde68a)]">{props.disabledReason}</p> : null}
      {session.offline ? <p role="status" className="m-0 text-xs text-[var(--studio-danger,#fde68a)]">连接已断开，已保存内容会在重新连接后恢复。</p> : null}
      {session.writeNotice ? <p role="status" className="m-0 text-xs leading-5">{session.writeNotice}</p> : null}
      {!conversational && localNotice ? <p role="status" className="m-0 text-xs leading-5">{localNotice}</p> : null}
      {localError || !props.initialization && session.error ? <p role="alert" className="m-0 text-xs leading-5 text-[var(--studio-danger,#fde68a)]">{localError || session.error}</p> : null}
      {!conversational && session.pendingCommand ? <div className="text-xs leading-5"><p className="m-0">上次操作结果待核实。重试会使用原操作标识。</p><Button size="small" disabled={mutationBlocked} onClick={() => void session.store.retryPending()}>核实上次操作</Button></div> : null}
      {!conversational && session.pendingCommand ? <Button size="small" disabled={busy} onClick={() => void session.store.inspectPending()}>只读查询操作回执</Button> : null}
      {!conversational && session.pendingCommand && session.commandRecovery ? <p role="status" className="m-0 text-xs leading-5">{session.commandRecovery.recoveryDirective === 'REFRESH_AUTHORITATIVE_STATE'
        ? '服务器已记录操作完成，已同步权威状态；继续核实时仍使用原请求正文与标识。'
        : session.commandRecovery.observation === 'NOT_OBSERVED' ? '当前尚未观察到已提交回执，不代表操作失败。仅可用原请求核实，不会创建替代操作。' : '服务器仍在处理原操作。仅可继续核实原请求。'}</p> : null}
    </>
  const composerReferenceHint = <>
      {conversational && interjecting && (props.hasDraftReferences || documentRefs.length) ? <p className="assistant-inline-loading" role="status">{exclusiveComposerActions && showComposerStop ? "补充内容与素材会保留，当前处理结束后可发送。" : "素材随消息一起排队；当前处理结束后作为新需求发送。"}</p> : null}
    </>
  const composerRunHint = <>
      {!conversational && pausedInterjection ? <p className="m-0 text-xs">{props.renderShell ? '暂停中追加文字先进入待发送队列；点击引导后才提交。' : '暂停中可补充文字。'}提交后旧问题、计划与未执行报价失效，仍保持暂停，需点击继续。如需使用编辑后的内容，请单独更新引用。</p>
        : !conversational && interjecting ? <p className="m-0 text-xs">{props.renderShell ? '运行中追加的文字进入待发送队列，点击消息右侧的引导箭头后才会发送。' : '运行中补充只发送文字。'}更新引用内容请先暂停。</p> : !conversational && run && !canSend ? <p className="m-0 text-xs">当前请先处理问题、计划或运行确认；也可停止后续执行，再发起新需求。</p> : null}
    </>
  const thread = <div className={`assistant-thread flex min-h-0 flex-1 flex-col gap-3${props.studioLayout || props.renderShell ? "" : " overflow-y-auto overscroll-contain p-3"}`} aria-label="助手对话">
      {!props.initialization && session.loading ? <p role="status" className="text-sm text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">正在恢复会话…</p> : null}
      {!props.initialization && !session.loading && !snapshot ? <p className="text-sm leading-6 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">新建对话，描述你希望完成的工作。问题由助手根据上下文决定。</p> : null}
      {!conversational && snapshot?.nextMessageCursor ? <Button disabled={busy} onClick={() => void session.store.loadOlder()}>加载更早消息</Button> : null}
      {conversational && displaySnapshot ? <AssistantConversationTimeline activeApprovalId={showApproval && snapshot?.approval?.scopeKind === 'PLAN_EXECUTION' ? snapshot.approval.approvalId : undefined} renderArtifact={props.renderArtifact} renderMessageActions={snapshot ? props.renderMessageActions : undefined} presentation={props.segmentedTimeline ? 'segmented' : props.presentation} snapshot={displaySnapshot} store={session.store} busy={mutationBlocked} onLocateTarget={locator?.resolve} inputHistory={displaySnapshot.inputHistory} reasoning={session.reasoning || undefined} publicSummaries={session.publicSummaries} transientActivities={session.transientActivities} liveReply={session.liveReply || undefined} progress={session.progress || undefined} pendingPrompt={pendingPrompt} pendingState={pendingInSession?.status} /> : null}
      {contextSync.error ? <div className="assistant-inline-error" role="alert"><span>{contextSync.error}</span><Button size="small" disabled={mutationBlocked || !!session.pendingCommand} onClick={contextSync.retry}>重试</Button></div> : null}
      {run?.automaticTextFailure ? <p className="assistant-inline-error" role="alert">{run.automaticTextFailure}</p> : null}
      {conversational && !contextSync.automatic && (contextBlocked || snapshot?.inputRequest?.status === 'SUSPENDED' && props.hasDraftReferences) && (props.resolveContext || props.prepareExistingContextRefresh || documentRefs.length > 0) ? <div className="assistant-context-recovery" role="status"><p>{contextRecovery === 'END_QUESTION' ? '回答已保留。请结束旧问答，再使用选中的引用发送需求。' : '引用内容需要重新确认。请选择需要使用的素材，再更新引用后继续。已有对话与结果会保留。'}</p><Button size="small" disabled={contextRecovery === 'UNAVAILABLE' || mutationBlocked || !!session.pendingCommand} loading={refreshingContext} onClick={refreshContext}>{contextRecovery === 'END_QUESTION' ? '保留回答并结束旧问答' : '更新引用内容'}</Button>{localError || session.error && !assistantContextChangeText(session.error) ? <p role="alert">{localError || session.error}</p> : null}</div> : null}
      {conversational && assistantCanResume(snapshot, contextBlocked) ? <div className={`assistant-context-recovery${props.presentation === 'canvas' ? ' is-inline' : ''}`} role="status"><p>{assistantOperationFailedPause(snapshot) ? '本步执行失败，流程已暂停。可展开处理过程查看原因，再确认继续。' : assistantResumeCopy(run?.nextAction)}</p>{props.presentation === 'canvas' ? <Button type="primary" disabled={mutationBlocked || !!session.pendingCommand} loading={resuming} aria-busy={resuming} onClick={() => void resume()}>继续处理</Button> : <Button size="small" disabled={mutationBlocked || !!session.pendingCommand} loading={resuming} aria-busy={resuming} onClick={() => void resume()}>继续处理</Button>}</div> : null}
      {session.queuedControl && !session.queuedControl.dispatched ? <p className="m-0 text-xs leading-5" role="status">已暂停本页自动继续，等待当前指令核对后提交{session.queuedControl.action === 'stop' ? '停止' : '暂停'}；尚未确认服务端已停止。</p> : null}
      {conversational && session.pendingCommand?.action === 'stop' && session.busy ? <div className="assistant-inline-error" role="status"><span className={props.presentation === 'canvas' ? 'canvas-assistant-status-shimmer' : undefined}>正在提交停止请求；当前运行状态仍以服务端同步结果为准。</span></div> : null}
      {conversational && session.pendingCommand && !session.busy ? <div className="assistant-inline-error" role="status"><span className={props.presentation === 'canvas' ? 'canvas-assistant-status-shimmer' : undefined}>{session.pendingCommand.action === 'stop' ? '停止请求结果待核实；尚未确认已停止，不会自动重复请求。' : '正在确认上次发送的结果。'}</span><Button type="link" size="small" disabled={mutationBlocked} onClick={() => void session.store.retryPending()}>{session.pendingCommand.action === 'stop' ? '核实并重试停止' : '重新连接'}</Button></div> : null}
      {conversational && session.lastRejectedCommand?.action === 'stop' && session.lastRejectedCommand.sessionId === snapshot?.sessionId && !session.pendingCommand ? <p className="assistant-inline-error" role="status">停止请求未受理；当前运行状态仍以服务端快照为准。</p> : null}
      {!conversational && snapshot?.messages.map((item) => <article key={item.id} className={props.studioLayout ? 'assistant-conversation-message is-' + item.role : item.role === 'user' ? 'assistant-message assistant-message-user ml-6 rounded-xl bg-[var(--studio-ui-hover,rgba(255,255,255,0.1))] p-3' : 'assistant-message assistant-message-other mr-3 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.1))] p-3'}>
        <span className="assistant-message-role mb-1 block text-xs text-[var(--studio-ui-muted,rgba(255,255,255,0.6))]">{item.role === 'user' ? '你' : item.role === 'assistant' ? '助手' : '系统'}</span>
        {item.messageKind === 'USER_INTERJECTION_PENDING' ? <span className="mb-1 block text-xs">调整已保存 · 等待原任务和费用核实后生效</span> : item.messageKind === 'USER_INTERJECTION_CANCELLED' ? <span className="mb-1 block text-xs">调整已保存 · 已停止应用</span> : null}
        {item.messageKind === 'USER_INTERJECTION' ? <span className="mb-1 block text-xs">补充要求 · 已保存，供后续助手轮次读取</span> : null}
        {props.studioLayout && item.role === "assistant" ? <AssistantConversationContent text={item.content} /> : <div className="whitespace-pre-wrap break-words text-sm leading-6">{item.content}</div>}
      </article>)}
      {run && !conversational ? <section className="flex flex-wrap items-center gap-2 text-xs" aria-label="运行控制">
        <span role="status">{['SUCCEEDED', 'COMPLETED'].includes(run.status) && !snapshot?.plan ? '本轮对话已结束' : runLabels[run.status] || '状态待确认'}{run.pauseReason ? ' · ' + (pauseLabels[run.pauseReason] || '等待确认后继续') : ''}</span>
        {run.allowedActions.includes('PAUSE') && !run.stopRequested ? <Button size="small" disabled={controlBlocked} onClick={() => void session.store.control('pause')}>暂停执行</Button> : null}
        {assistantCanResume(snapshot, contextBlocked) ? <Button size="small" disabled={mutationBlocked || !!session.pendingCommand} loading={resuming} aria-busy={resuming} onClick={() => void resume()}>继续</Button> : null}
        {!props.renderShell && run.allowedActions.includes('STOP') ? <Button size="small" disabled={controlBlocked || run.stopRequested === true} onClick={() => { if (!run.stopRequested) void session.store.control('stop') }}>{run.stopRequested ? '已请求停止' : '停止后续执行'}</Button> : null}
        {run.stopRequested && !['STOPPED', 'COMPLETED', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.status) ? <p role="status" className="m-0 w-full leading-5">已请求停止：当前已提交的操作结束后停止，不再发起后续请求；已发生费用按实际结算。</p> : null}
        {(props.resolveContext || props.prepareExistingContextRefresh || documentRefs.length > 0) && ['WAITING_APPROVAL', 'WAITING_INPUT', 'PAUSED'].includes(run.status) ? <Button size="small" disabled={mutationBlocked || !!session.pendingCommand} loading={refreshingContext} onClick={refreshContext}>更新引用内容</Button> : null}
        {!conversational && run.hasActiveTasks ? <p className="m-0 w-full leading-5 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">暂停或停止后续执行不会取消在途生成或撤销费用；请在任务卡查看可用操作。</p> : null}
      </section> : null}
      {!props.renderShell ? questionCard : null}
      {conversational ? <div className="assistant-thread-approvals">{approvalCard}</div> : !props.renderShell ? approvalCard : null}
      {!conversational && snapshot?.tasks.map((task) => <AssistantTaskCard key={'task:' + task.taskId} task={task} step={snapshot.steps.find((step) => step.stepId === task.stepId && step.mediaTaskId === task.taskId)} store={session.store} busy={mutationBlocked} />)}
      {!conversational && props.renderShell && snapshot ? <AssistantRunActivity snapshot={snapshot} onLocateTarget={locator?.resolve} /> : null}
      {!conversational && snapshot ? <AssistantStepHistory key={'steps:' + snapshot.sessionId} snapshot={snapshot} store={session.store} onLocateTarget={locator?.resolve} /> : null}
      {!conversational && snapshot ? <AssistantSourcesPanel key={'sources:' + snapshot.sessionId} store={session.store} eventSeq={snapshot.lastEventSeq}
        runIds={[...new Set([snapshot.activeRun?.runId, ...[...snapshot.messages].reverse().map((item) => item.runId)].filter((value): value is string => !!value))]} /> : null}
      {!conversational && snapshot ? <AssistantArtifactsPanel key={'artifacts:' + snapshot.sessionId} store={session.store}
        runIds={[...new Set([snapshot.activeRun?.runId, ...[...snapshot.messages].reverse().map((item) => item.runId)].filter((value): value is string => !!value))]}
        eventSeq={snapshot.lastEventSeq} adoptionAdapter={props.adoptionAdapter} seriesAdapter={props.seriesAdapter} onLocateTarget={locator?.resolve} /> : null}
      {props.threadFeedback || feedbackInThread && !answeringQuestion ? <div className="assistant-thread-feedback">{props.threadFeedback}{feedbackInThread && !answeringQuestion ? <>{composerFeedback}{composerReferenceHint}{composerRunHint}</> : null}</div> : null}
    </div>
  const composer = <div data-assistant-primary-action={exclusiveComposerActions ? showComposerStop ? "stop" : "send" : undefined} data-assistant-composer-phase={canvasComposer?.phase} className={`assistant-composer${answeringQuestion ? ' assistant-composer--question' : ''} flex shrink-0 flex-col gap-2 border-t border-[var(--studio-ui-border,rgba(255,255,255,0.1))] p-3`}>
      {answeringQuestion ? questionCard : null}
      {answeringQuestion && localError ? <p role="alert" className="m-0 text-xs leading-5 text-[var(--studio-danger,#fde68a)]">{localError}</p> : null}
      {!answeringQuestion ? <><div className="assistant-composer-content">
      {props.renderShell && !answeringQuestion ? questionCard : null}
      {props.renderShell && !conversational ? approvalCard : null}
      {!directAttachments && sourceImport ? <Modal title="本地文档" open={referenceOpen} onCancel={() => setReferenceOpen(false)} footer={null} destroyOnHidden={false}>
        <AssistantSourceImportPanel key={sourceImport.key} store={sourceImport.store} sessionId={sourceImport.sessionId} actorId={sourceImport.actorId} disabled={mutationBlocked} active={referenceOpen} selectionActivation={referenceSelectionActivation} onSelection={(refs) => { composerRevision.current += 1; setDocumentRefs(refs) }} />
      </Modal> : null}
      {!draft.transitioning && queued.items.length ? <details className="assistant-message-queue" open>
        <summary>{queued.items.length} 条待发送</summary>
        <ul>{queued.items.map(item => <li key={item.id}>
          <p>{item.text}</p>
          {item.contextRefs?.length ? <ul className="assistant-message-queue-references" aria-label="待发送消息引用的素材">{item.contextRefs.map((ref, index) => <li key={index}>{ref.label || '已固定的参考素材'}</li>)}</ul> : null}
          <div><span>{item.commandId ? '已提交 · 等待核实' : item.contextRefs?.length ? interjecting ? '待当前处理结束后发送' : '等待发送新需求' : '等待引导'}</span>
            {hint(<Button aria-label={item.commandId ? '核实此排队消息' : item.contextRefs?.length ? '发送这条新需求' : '引导此消息'} icon={<ArrowUpOutlined />} disabled={mutationBlocked || !!session.pendingCommand || !item.body && (!canSend || interjecting && !!item.contextRefs?.length) || !!props.messageDisabledReason} onClick={() => void guideQueued(item.id)} />, item.commandId ? '使用原标识与原正文核实，不重复发送' : item.contextRefs?.length ? '当前处理结束后，使用原素材版本发送新需求' : '引导后才会发送这条消息')}
            {hint(<Button aria-label="删除待发送消息及其素材" icon={<DeleteOutlined />} disabled={!!item.commandId || composerPolicy.editorDisabled} onClick={() => { try { queue.remove(item.id) } catch (cause) { setLocalError(assistantErrorMessage(cause)) } }} />, item.commandId ? '已提交的消息须先核实结果' : '删除这条尚未发送的消息及其素材')}
          </div></li>)}</ul>
      </details> : null}
      {!feedbackInThread ? composerFeedback : null}
      {!draft.transitioning ? typeof props.contextSlot === 'function' ? props.contextSlot({ disabled: conversational ? composerPolicy.editorDisabled : mutationBlocked || !!session.pendingCommand, interjecting, documentRefs, removeDocument }) : props.contextSlot : null}
      {!feedbackInThread ? composerReferenceHint : null}
      {!conversational && props.resolveContext ? <label className="flex min-h-9 items-center gap-2 text-xs"><input type="checkbox" checked={includeContext && !interjecting} disabled={interjecting || mutationBlocked || props.requiredContext === true} onChange={(event) => setIncludeContext(event.target.checked)} />{props.contextLabel || '引用当前已保存内容'}</label> : null}
      {!feedbackInThread ? composerRunHint : null}
      <label className="sr-only" htmlFor={composerId}>发送消息</label>
      {props.renderMessageEditor ? <AssistantMessageEditorSlot render={props.renderMessageEditor} view={{ id: composerId, value: text, disabled: composerPolicy.editorDisabled, active: canvasComposer?.active ?? primaryIsStop,
        idle: canvasComposer ? canvasComposer.idle && !hasComposerReferences && !queued.items.length && !referenceOpen : undefined,
        placeholder: exclusiveComposerActions && showComposerStop ? '正在生成，补充想法会保留在输入框…' : composerPolicy.queueing || interjecting ? queueInterjection || composerPolicy.queueing ? '消息与素材先保存在待发送队列…' : '补充要求，发送到当前对话…' : props.composerPlaceholder ?? '开始你的创作，或 @ 引用画布节点…', onChange: editComposer, onSubmit: submitComposer }} /> : <textarea id={composerId} maxLength={100000} rows={conversational ? 2 : 3} value={text} disabled={composerPolicy.editorDisabled} placeholder={exclusiveComposerActions && showComposerStop ? '正在生成，补充想法会保留在输入框…' : composerPolicy.queueing || interjecting ? queueInterjection || composerPolicy.queueing ? '消息与素材先保存在待发送队列…' : '补充要求，发送到当前对话…' : props.composerPlaceholder ?? '开始你的创作，或添加参考素材…'}
        onChange={(event) => editComposer(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); submitComposer() } }} className="assistant-field w-full min-w-0 resize-y rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.2))] bg-transparent px-3 py-2 text-sm leading-6" />}
      </div>
      <div className="assistant-composer-tools">
        {directAttachments ? sourceImport ? <AssistantSourceImportPanel key={sourceImport.key} store={sourceImport.store} sessionId={sourceImport.sessionId} actorId={sourceImport.actorId} disabled={mutationBlocked || !!session.pendingCommand || documentRefs.length >= 20} direct onAttach={addDocument} onBusyChange={setUploadingDocument} />
          : <button type="button" className="assistant-source-import-trigger" aria-label="上传文本附件" disabled><PaperClipOutlined aria-hidden /></button>
          : props.attachmentTools ? typeof props.attachmentTools === 'function' ? props.attachmentTools({ disabled: mutationBlocked || !!session.pendingCommand || !!props.initialization && !snapshot, openDocuments: openReferenceDocuments }) : props.attachmentTools : sourceImport ? hint(<Button aria-label="本地上传文档" icon={<PlusOutlined />} disabled={mutationBlocked} onClick={openReferenceDocuments} />) : null}
        {typeof props.composerTools === 'function' ? props.composerTools({ disabled: mutationBlocked || !!session.pendingCommand, skillLocked, selectedSkillCode: skillLocked ? run?.selectedSkillCode || '' : props.selectedSkillCode || '', prepareSwitch: session.store.prepareContextRefresh, changeExecutionPreference }) : props.composerTools}
        <div className="assistant-composer-primary-actions">
          {showComposerSend ? hint(<Button aria-label={composerPolicy.queueing || queueInterjection ? '加入待发送队列' : interjecting ? '发送补充' : '发送消息'} className={props.presentation === 'canvas' ? 'assistant-composer-glyph-button' : undefined} type="primary" loading={sending && !composerPolicy.queueing} disabled={composerPolicy.submitDisabled || uploadingDocument || !text.trim() || text.length > 100000} onClick={submitComposer}>{props.composerSendIcon?.(Boolean(text.trim())) ?? (props.presentation === 'canvas' ? <ComposerSendGlyph empty={!text.trim()} /> : props.renderShell ? '↑' : interjecting ? '发送补充' : '发送')}</Button>, awaitingMessageSync ? '消息已发送，等待会话同步后再继续' : props.messageDisabledReason || props.disabledReason || (!text.trim() ? '输入内容后发送' : undefined)) : null}
          {showComposerStop ? hint(<Button aria-label={run?.stopRequested ? '已请求停止' : '停止后续执行'} className={`assistant-composer-stop${props.presentation === 'canvas' ? ' assistant-composer-glyph-button' : ''}`} disabled={controlBlocked || run?.stopRequested === true || !run?.allowedActions.includes('STOP')} onClick={() => { if (run?.allowedActions.includes('STOP') && !run.stopRequested) void session.store.control('stop') }}>{props.composerStopIcon ?? (props.presentation === 'canvas' ? <ComposerSendGlyph stop /> : <span className="assistant-stop-glyph" aria-hidden />)}</Button>, session.queuedControl ? '等待提交停止，请勿重复操作' : run?.stopRequested ? '正在核实已受理操作' : !run?.allowedActions.includes('STOP') ? run ? '当前运行暂不允许停止' : '正在同步运行状态，请稍候' : '停止后续执行') : null}
        </div>
      </div></> : null}
    </div>
  const insertComposerPrompt = (prompt: string) => {
    if (composerPolicy.editorDisabled || answeringQuestion) return
    const next = text.trimEnd().endsWith(prompt) ? text : text.trim() ? `${text}\n\n${prompt}` : prompt
    if (next.length > 100000) { setLocalError('输入内容已达上限，请缩短后再添加创作要求'); return }
    editComposer(next)
    requestAnimationFrame(() => {
      const editor = document.getElementById(composerId)
      if (editor instanceof HTMLTextAreaElement && !editor.disabled) {
        editor.focus({ preventScroll: true }); editor.setSelectionRange(next.length, next.length)
      }
    })
  }
  const scopeKey = displaySnapshot?.sessionId || snapshot?.sessionId || "new"
  const updateToken = [scopeKey, displaySnapshot?.lastEventSeq, displaySnapshot?.messages.length, session.reasoning?.startedAt, session.reasoning?.text.length, session.liveReply?.startedAt, session.liveReply?.text.length, pendingInSession?.clientRequestId, pendingInSession?.status].join(":")
  const content = props.renderShell ? props.renderShell({ store: session.store, mutationBlocked, snapshot, displaySnapshot, errorPresentation: session.errorPresentation, loading: draft.transitioning || session.loading || Boolean(props.initialization?.enabled && !session.capabilities && !session.error), error: session.error, retry: () => { if (props.initialization && !snapshot) openedToken.current = null; void session.store.retryRead() }, hasConversation: Boolean(displaySnapshot?.messages.length || run || pendingPrompt), updateToken, picker, newSession: () => { void newSession() }, prepareSwitch: session.store.prepareContextRefresh, thread, composer, composerDisabled: draft.transitioning || composerPolicy.editorDisabled || answeringQuestion, insertComposerPrompt }) : props.studioLayout ? <>
    {toolbar}
    <AgentConversationViewport className="agent-conversation-panel__messages" scopeKey={scopeKey} updateToken={updateToken}
      initialLoading={session.loading && !displaySnapshot} followContentResize revealHistory={false}>
      {thread}
    </AgentConversationViewport>
    <StudioCanvasAssistantLatest sessionId={scopeKey} />
    {composer}
  </> : <>{toolbar}{thread}{composer}</>
  return <div data-assistant-session-id={snapshot?.sessionId} data-assistant-run-id={run?.runId} className={`assistant-panel${conversational ? ' assistant-panel--conversational' : ''}${props.studioLayout ? ' assistant-panel--studio-layout' : ''} flex h-full min-h-0 min-w-0 flex-col text-[var(--studio-ui-text,#fff)]`}>{content}</div>
}
