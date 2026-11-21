'use client'

function isSegmented(presentation?: 'canvas' | 'segmented') { return presentation === 'canvas' || presentation === 'segmented' }

import { AssistantTaskEta } from './AssistantTaskEta'
import { Fragment, type ReactNode, memo, useCallback, useEffect, useId, useRef, useState } from 'react'
import { Button } from 'antd'
import { CheckOutlined, ClockCircleOutlined, DownOutlined, ExclamationCircleOutlined, FileTextOutlined, LoadingOutlined, MessageOutlined, PictureOutlined, RightOutlined, SoundOutlined, StopOutlined, ToolOutlined, VideoCameraOutlined } from '@ant-design/icons'
import type { AssistantActivity, AssistantArtifact, AssistantInputRequest, AssistantMessage, AssistantMessageReference, AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantRealtimeReasoning } from '~/utils/assistant/assistantRealtime'
import type { AssistantReasoningPage } from '~/utils/assistant/assistantReasoningHistory'
import { assistantConversationActiveFailureReasons, assistantConversationActivityBlocks, assistantConversationDetailRows, assistantConversationFailureReasons, type AssistantActivityBlock } from '~/utils/assistant/assistantConversationActivities'
import type { AssistantArtifactChunk, AssistantArtifactDetail, AssistantArtifactMedia } from '~/types/assistant-artifact'
import type { AssistantEffectHandler, AssistantTargetEffect } from '~/types/assistant-effects'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { canLocateAssistantEffect } from '~/utils/assistant/assistantEffects'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantCanvasArtifactGroups, assistantConversationSegments, type AssistantConversationSegment } from '~/utils/assistant/assistantConversationSegments'
import { assistantArtifactTitle, isAssistantMediaArtifact, mergeAssistantArtifactContent, verifyAssistantArtifactMedia } from '~/utils/assistant/assistantArtifacts'
import { assistantConversationNodeCreationKind, assistantConversationArtifactAnchor, assistantConversationArtifactGroups, assistantConversationProgress, assistantConversationStepDisplay, assistantConversationTaskStatus, assistantConversationTaskReconciling, assistantConversationTaskTitle, assistantConversationTerminal, assistantMessageReferenceLabel, assistantQuestionSummary, assistantQuestionSummaryAnchor, assistantReferencePreviewUrl } from '~/utils/assistant/assistantConversationPresentation'
import { markdownToAssistantConversationHtml } from '~/utils/htmlPlain'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'

interface MediaReadFlight { promise: Promise<unknown>; consumers: Set<() => boolean> }
interface MediaReadQueue { active: number; queue: (() => void)[]; flights: Map<string, MediaReadFlight> }
const mediaReadQueues = new WeakMap<AssistantSessionStore, MediaReadQueue>()
function drainMediaReadQueue(reads: MediaReadQueue) {
  while (reads.active < 4 && reads.queue.length) { reads.active++; reads.queue.shift()!() }
}
/** Bound reads across every visible group; retain only in-flight requests, never a global URL cache. */
function mediaReadFlight<T>(store: AssistantSessionStore, key: string, read: () => Promise<T>, isCurrent: () => boolean): Promise<T> {
  let reads = mediaReadQueues.get(store)
  if (!reads) { reads = { active: 0, queue: [], flights: new Map() }; mediaReadQueues.set(store, reads) }
  const pending = reads.flights.get(key)
  if (pending) { pending.consumers.add(isCurrent); return pending.promise as Promise<T> }
  const currentReads = reads
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail })
  const flight = { promise, consumers: new Set([isCurrent]) }
  currentReads.flights.set(key, flight)
  currentReads.queue.push(() => {
    const done = () => { currentReads.active--; if (currentReads.flights.get(key) === flight) currentReads.flights.delete(key); drainMediaReadQueue(currentReads) }
    void Promise.resolve().then(() => {
      if (![...flight.consumers].some(current => current())) throw new Error('读取范围已变化')
      return read()
    }).then(value => { resolve(value); done() }, cause => { reject(cause); done() })
  })
  drainMediaReadQueue(currentReads)
  return promise
}

export type AssistantConversationReasoning = AssistantRealtimeReasoning

export interface AssistantConversationProgressEvent {
  runId: string
  stage?: string
  eventType?: string
  stepId?: string
  mediaTaskId?: string
}

/** Reuse the existing escape-first Markdown renderer. Model HTML is always rendered as text. */
export function AssistantConversationContent({ text }: { text: string }) {
  return <div className="assistant-conversation-content" dangerouslySetInnerHTML={{ __html: markdownToAssistantConversationHtml(text) }} />
}

const CanvasConversationContent = memo(AssistantConversationContent)

export function AssistantMessageReferences({ references }: { references?: AssistantMessageReference[] }) {
  if (!references?.length) return null
  return <ul className="assistant-message-references" aria-label={references.some(ref => ['MODEL', 'SKILL'].includes(ref.kind)) ? '这条消息选择的模型、Skill与素材' : '这条消息引用的素材'}>{references.map((ref, index) => {
    const thumbnail = ref.status === 'AVAILABLE' && ref.mediaKind === 'IMAGE' ? assistantReferencePreviewUrl(ref.previewUrl) : undefined
    return <li key={`${ref.ordinal}:${index}`} className={ref.status === 'UNAVAILABLE' ? 'is-unavailable' : undefined}>
      {thumbnail ? (
        // eslint-disable-next-line @next/next/no-img-element -- Fresh server-authorized projection of this persisted message's reference.
        <img src={thumbnail} alt="" loading="lazy" referrerPolicy="no-referrer" onError={event => { event.currentTarget.hidden = true }} />
      ) : ref.kind === 'SKILL' ? <ToolOutlined aria-hidden /> : ref.mediaKind === 'IMAGE' ? <PictureOutlined aria-hidden /> : ref.mediaKind === 'VIDEO' ? <VideoCameraOutlined aria-hidden /> : ref.mediaKind === 'AUDIO' ? <SoundOutlined aria-hidden /> : <FileTextOutlined aria-hidden />}
      <span>{assistantMessageReferenceLabel(ref)}</span>{ref.status === 'UNAVAILABLE' ? <small>当前不可读取</small> : null}
    </li>
  })}</ul>
}

/** Every click is still checked through AssistantEffectLocator; this component never turns targetRefs into write authority. */
export function AssistantPromptAction({ effect, onLocateTarget, label = '在画布中查看' }: { effect?: AssistantTargetEffect; onLocateTarget?: AssistantEffectHandler; label?: string }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const mounted = useRef(true), pending = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  if (!effect || !onLocateTarget || !canLocateAssistantEffect(effect)) return null
  const locate = async () => {
    if (pending.current) return
    pending.current = true; setBusy(true); setError(null)
    try { await onLocateTarget(effect, () => mounted.current) }
    catch (cause) { if (mounted.current) setError(assistantErrorMessage(cause)) }
    finally { pending.current = false; if (mounted.current) setBusy(false) }
  }
  return <span className="assistant-prompt-action"><Button type="link" size="small" loading={busy} disabled={busy} onClick={() => void locate()}>{label}<RightOutlined aria-hidden /></Button>{error ? <span className="assistant-inline-error" role="alert">{error}</span> : null}</span>
}

function AnsweredQuestions({ input }: { input: AssistantInputRequest }) {
  const rows = assistantQuestionSummary(input)
  if (!rows.length) return null
  return <details className="assistant-answered-questions" open>
    <summary><MessageOutlined aria-hidden /><span>已询问</span><DownOutlined aria-hidden /></summary>
    <dl>{rows.map((row, index) => <div key={input.questions[index].id}><dt>{row.question}</dt><dd>{row.answer}</dd></div>)}</dl>
  </details>
}

const failureReasonReads = new Map<string, Promise<string[]>>()

function readFailureReasons(store: AssistantSessionStore, sessionId: string, runId: string, title: string, stepIds?: string[]): Promise<string[]> {
  const key = `${store.api.transport.identityKey()}:${sessionId}:${runId}:${title}:${stepIds?.join(',') || ''}`
  const pending = failureReasonReads.get(key)
  if (pending) return pending
  const request = (async () => {
    const steps = []
    const targeted = stepIds?.length === 1 && /^[1-9][0-9]{0,18}$/.test(stepIds[0])
    let beforeStepId: string | undefined = targeted ? String(BigInt(stepIds![0]) + BigInt(1)) : undefined
    for (let page = 0; page < 3; page += 1) {
      const result = await store.api.runStepPage({ runId, beforeStepId, limit: targeted ? 1 : 20 })
      if (result.scope.sessionId !== sessionId || result.scope.runId !== runId) throw new Error('失败原因不属于当前会话')
      steps.push(...result.items.filter(step => !stepIds?.length || stepIds.includes(step.stepId)))
      const reasons = assistantConversationFailureReasons(steps, [], title)
      if (targeted || reasons.length || !result.hasMore || !result.nextCursor) return reasons
      beforeStepId = result.nextCursor
    }
    return assistantConversationFailureReasons(steps, [], title)
  })()
  failureReasonReads.set(key, request)
  const release = () => { if (failureReasonReads.get(key) === request) failureReasonReads.delete(key) }
  void request.then(release, release)
  return request
}

function ArchivedReasoning({ store, sessionId, runId, stepId, innerStepId, skillRunId, scrollPages }: { store: AssistantSessionStore; sessionId: string; runId: string; stepId: string; innerStepId?: string; skillRunId?: string; scrollPages: boolean }) {
  const [page, setPage] = useState<AssistantReasoningPage | null>(null)
  const [text, setText] = useState('')
  const archive = useRef<HTMLDivElement>(null)
  const sentinel = useRef<HTMLDivElement>(null)
  const failedRequest = useRef<{ stream?: string; offset: number; append: boolean } | null>(null)
  const [streams, setStreams] = useState<string[]>([])
  const [history, setHistory] = useState<number[]>([])
  const [offset, setOffset] = useState(0)
  const [loading, setLoading] = useState(false), [error, setError] = useState('')
  const mounted = useRef(true), pending = useRef(false)
  const load = useCallback(async (stream?: string, nextOffset = 0, append = false) => {
    if (pending.current) return false
    pending.current = true; setLoading(true); setError('')
    failedRequest.current = null
    try {
      const request = { sessionId, runId, stepId, innerStepId, skillRunId, stream, offset: nextOffset, limit: 16 }
      const value = await mediaReadFlight(store, 'reasoning:' + JSON.stringify(request), () => store.api.reasoningRead(request), () => mounted.current)
      if (!mounted.current) return false
      const nextText = value.chunks?.map(chunk => chunk.content).join('') || ''
      setText(current => append ? current + nextText : nextText)
      const viewport = archive.current?.closest('.assistant-thinking-viewport')
      if (!append && viewport) viewport.scrollTop = 0
      setPage(value); setOffset(nextOffset); if (value.streams) setStreams(value.streams)
      return true
    } catch (cause) {
      if (mounted.current) {
        failedRequest.current = { stream, offset: nextOffset, append }
        setError(assistantErrorMessage(cause))
      }
      return false
    }
    finally { pending.current = false; if (mounted.current) setLoading(false) }
  }, [store, sessionId, runId, stepId, innerStepId, skillRunId])
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false } }, [load])
  useEffect(() => {
    const root = archive.current?.closest('.assistant-thinking-viewport')
    const target = sentinel.current
    if (!scrollPages || !root || !target || loading || error || !page?.hasMore || page.nextOffset === undefined) return
    const advance = () => { void load(page.stream, page.nextOffset, true) }
    if (typeof IntersectionObserver === 'undefined') {
      const onScroll = () => { if (root.scrollHeight - root.scrollTop - root.clientHeight < 32) advance() }
      onScroll()
      root.addEventListener('scroll', onScroll, { passive: true })
      return () => root.removeEventListener('scroll', onScroll)
    }
    const observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) advance() },
      { root, rootMargin: '0px 0px 24px 0px' })
    observer.observe(target)
    return () => observer.disconnect()
  }, [scrollPages, page, loading, error, load])
  const streamIndex = page?.stream ? streams.indexOf(page.stream) : -1
  return <div ref={archive} className="assistant-archived-reasoning">
    {text ? <div className="assistant-model-reasoning">{text}</div> : null}
    {loading ? <p role="status">正在读取思考记录…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {page?.status === 'UNAVAILABLE' ? <p>该步骤的思考记录未保存或已清理，正文与执行结果仍保留。</p> : null}
    {page && !page.hasMore && ['PARTIAL', 'INCOMPLETE'].includes(page.status) ? <p>思考记录不完整，当前展示已保存的内容。</p> : null}
    {page?.status === 'COMPLETE' && !text ? <p>该步骤没有返回可展示的思考内容。</p> : null}
    <div className="assistant-reasoning-page-actions">
      {!scrollPages && history.length ? <Button type="text" disabled={loading} onClick={() => { const previous = history.at(-1)!; void load(page?.stream, previous).then(ok => { if (ok) setHistory(history.slice(0, -1)) }) }}>上一页</Button> : null}
      {!scrollPages && page?.hasMore ? <Button type="text" disabled={loading} onClick={() => { void load(page.stream, page.nextOffset).then(ok => { if (ok) setHistory([...history, offset]) }) }}>下一页</Button> : null}
      {streamIndex > 0 ? <Button type="text" disabled={loading} onClick={() => { void load(streams[streamIndex - 1]).then(ok => { if (ok) setHistory([]) }) }}>上一段思考</Button> : null}
      {streamIndex >= 0 && streamIndex < streams.length - 1 ? <Button type="text" disabled={loading} onClick={() => { void load(streams[streamIndex + 1]).then(ok => { if (ok) setHistory([]) }) }}>下一段思考</Button> : null}
      {error || page?.status === 'UNAVAILABLE' || page?.status === 'INCOMPLETE' ? <Button type="text" disabled={loading} onClick={() => {
        const request = failedRequest.current
        void load(request?.stream ?? page?.stream, request?.offset ?? (scrollPages ? 0 : offset), request?.append)
      }}>重新读取</Button> : null}
    </div>
    {scrollPages && page?.hasMore ? <div ref={sentinel} className="assistant-reasoning-scroll-end" aria-hidden /> : null}
  </div>
}

function ThinkingProgress({ snapshot, block, store, presentation, expanded, onToggle }: { renderArtifact?: (artifact: AssistantArtifact) => ReactNode | undefined; presentation?: 'canvas' | 'segmented'; snapshot: AssistantSessionSnapshot; block: AssistantActivityBlock; store: AssistantSessionStore; expanded: boolean; onToggle: () => void }) {
  const run = snapshot.activeRun?.runId === block.runId ? snapshot.activeRun : undefined
  const segment = 'segmentId' in block ? block as AssistantConversationSegment : undefined
  const scopedSteps = segment ? snapshot.steps.filter(step => segment.stepIds.includes(step.stepId)) : snapshot.steps
  const scopedTasks = segment ? snapshot.tasks.filter(task => scopedSteps.some(step => step.stepId === task.stepId && step.mediaTaskId === task.taskId)) : snapshot.tasks
  const inlineDetails = isSegmented(presentation)
  const scrollThinking = presentation === 'canvas' && segment?.kind === 'thinking'
  const useInnerViewport = !inlineDetails || scrollThinking
  const body = useRef<HTMLDivElement>(null)
  const follow = useRef(true)
  const localReasons = segment ? run ? assistantConversationFailureReasons(scopedSteps, scopedTasks, block.label, isSegmented(presentation)) : [] : assistantConversationActiveFailureReasons(snapshot, block.runId, block.label)
  const localReasonSignature = localReasons.join('\n')
  const failureStepIds = JSON.stringify(segment?.stepIds || null)
  const readableRun = /^[1-9][0-9]{0,18}$/.test(block.runId)
  const remoteKey = `${snapshot.sessionId}:${segment?.segmentId || block.runId}:${block.label}`
  const [remote, setRemote] = useState<{ key: string; reasons: string[]; error: string; loading: boolean }>({ key: '', reasons: [], error: '', loading: false })
  useEffect(() => {
    if (useInnerViewport && block.live && follow.current && body.current) body.current.scrollTop = body.current.scrollHeight
  }, [block.live, block.rows, block.summaries, localReasonSignature, useInnerViewport])
  useEffect(() => {
    if (!expanded || block.state !== 'failed' || localReasons.length || !readableRun) return
    let active = true
    setRemote({ key: remoteKey, reasons: [], error: '', loading: true })
    void readFailureReasons(store, snapshot.sessionId, block.runId, block.label, JSON.parse(failureStepIds) || undefined).then(
      reasons => { if (active) setRemote({ key: remoteKey, reasons, error: '', loading: false }) },
      cause => { if (active) setRemote({ key: remoteKey, reasons: [], error: assistantErrorMessage(cause), loading: false }) }
    )
    return () => { active = false }
  }, [expanded, block.state, block.runId, block.label, snapshot.sessionId, localReasons.length, readableRun, remoteKey, store, failureStepIds])
  const tasks = run ? scopedTasks.filter((task) => !scopedSteps.some((step) => step.stepId === task.stepId && step.mediaTaskId === task.taskId && (step.stepKind === 'MAIN_AGENT' || step.operationKind === 'ASSISTANT_DECISION'))) : []
  const detailRows = assistantConversationDetailRows(block.rows, block.label)
  const remoteCurrent = remote.key === remoteKey ? remote : { key: remoteKey, reasons: [], error: '', loading: expanded && block.state === 'failed' && !localReasons.length && readableRun }
  const reasons = localReasons.length ? localReasons : remoteCurrent.reasons
  const loadingFailure = !localReasons.length && remoteCurrent.loading
  const missingFailure = expanded && block.state === 'failed' && !reasons.length && !loadingFailure && !remoteCurrent.error && !detailRows.length && !block.summaries.length
  const emptyThinking = segment?.kind === 'thinking' && !block.summaries.length && !detailRows.length && !reasons.length && !loadingFailure && !remoteCurrent.error
  const showViewport = block.summaries.length > 0 || detailRows.length > 0 || !segment && reasons.length > 0 || loadingFailure || Boolean(remoteCurrent.error) || missingFailure || block.live || emptyThinking
  const archivedStep = isSegmented(presentation) && segment?.kind === 'thinking' && segment.archiveStepId && /^[1-9][0-9]{0,18}$/.test(segment.archiveStepId) ? segment.archiveStepId : undefined
  const showArchive = expanded && !block.live && !!archivedStep && (!block.summaries.length || block.summaries.some(item => item.text.length >= 131072))
  const expandable = !segment || showViewport || !!archivedStep
  const titleHighlight = isSegmented(presentation) && (block.live || block.pendingHighlight) ? 'canvas-assistant-status-shimmer' : undefined
  return <section className={`assistant-conversation-process is-${block.state}`} data-assistant-activity-run={block.runId} data-assistant-segment={segment?.segmentId} data-assistant-segment-kind={segment?.kind} aria-label={segment ? block.label : '本轮处理过程'}><details className={`assistant-thinking-disclosure${block.live ? ' is-live' : ''}${block.live && block.summaries.some(summary => summary.live) ? ' has-live-reasoning' : ''}`} open={expanded}>
    <summary aria-expanded={expandable && expanded} data-expandable={expandable} onClick={event => {
      event.preventDefault()
      if (expandable) onToggle()
    }}>{block.state === 'failed' ? <ExclamationCircleOutlined aria-hidden /> : segment?.stopped ? <StopOutlined aria-hidden /> : block.live ? <LoadingOutlined spin aria-hidden /> : block.state === 'complete' && segment?.kind === 'thinking' ? <StudioIconGlyph className="assistant-thinking-mark" name="idea" /> : block.state === 'complete' ? <CheckOutlined aria-hidden /> : <ClockCircleOutlined aria-hidden />}<span className={titleHighlight}>{block.label}</span>{expandable ? <DownOutlined aria-hidden /> : null}</summary>
    {showViewport || showArchive ? <div ref={useInnerViewport ? body : undefined}
      className={useInnerViewport ? "assistant-thinking-content assistant-thinking-viewport" : "assistant-thinking-content"}
      tabIndex={scrollThinking && expanded ? 0 : undefined} role={scrollThinking ? 'region' : undefined}
      aria-label={scrollThinking ? '思考记录' : undefined} onScroll={!useInnerViewport ? undefined : (event) => { const view = event.currentTarget; follow.current = view.scrollHeight - view.scrollTop - view.clientHeight < 36 }}>
      {!showArchive && block.summaries.map((summary, index) => <div key={summary.streamId || index} className="assistant-model-reasoning">{summary.text}</div>)}
      {!segment && reasons.map(reason => <p key={reason} className="assistant-inline-error" role="alert">{reason}</p>)}
      {loadingFailure ? <p className="assistant-inline-loading" role="status">正在读取失败原因…</p> : null}
      {remoteCurrent.error ? <p className="assistant-inline-error" role="alert">{remoteCurrent.error}</p> : null}
      {missingFailure ? <p className="assistant-thinking-placeholder">这次失败没有返回具体原因。</p> : null}
      {detailRows.length ? <ol className="assistant-thinking-stages">{detailRows.map(row => <li key={row.key} className={'is-' + row.state}>
        <span aria-hidden>{row.spinning ? <LoadingOutlined spin /> : row.state === 'failed' ? <ExclamationCircleOutlined /> : row.state === 'waiting' ? <ClockCircleOutlined /> : <span className="assistant-stage-dot" />}</span><span className={isSegmented(presentation) && (row.spinning || block.pendingHighlight && row.state === 'waiting') ? 'canvas-assistant-status-shimmer' : undefined}>{row.label}</span>
      </li>)}</ol> : null}
      {!detailRows.length && !block.summaries.length && !reasons.length && block.live ? <p className="assistant-thinking-placeholder">{segment?.kind === 'thinking' ? '正在分析你的需求…' : '正在准备处理你的需求…'}</p> : null}
      {showArchive ? <ArchivedReasoning key={JSON.stringify([store.api.transport.identityKey(), snapshot.sessionId, block.runId, archivedStep, segment?.archiveInnerStepId, segment?.archiveSkillRunId])} store={store} sessionId={snapshot.sessionId} runId={block.runId} stepId={archivedStep!} innerStepId={segment?.archiveInnerStepId} skillRunId={segment?.archiveSkillRunId} scrollPages={scrollThinking} /> : null}
      {emptyThinking && !showArchive && !block.live && block.state === 'complete' ? <p className="assistant-thinking-placeholder">本阶段处理已结束，当前记录中没有可展示的思考内容。</p> : null}
    </div> : null}
  </details>
    {segment && reasons.map(reason => <p key={reason} className="assistant-inline-error" role="alert">{reason}</p>)}
    {tasks.map((task) => {
      const value = assistantConversationProgress(task, snapshot.steps)
      const reconciling = assistantConversationTaskReconciling(task, snapshot.steps)
      return <div className="assistant-generation-progress" key={task.taskId}><span>{assistantConversationTaskTitle(task, snapshot.steps)}</span><span className={isSegmented(presentation) && block.live && !assistantConversationTerminal(task.status) ? 'canvas-assistant-status-shimmer' : undefined}>{assistantConversationTaskStatus(task, snapshot.steps)}{value !== null && !assistantConversationTerminal(task.status) ? ` · ${value}%` : ''}</span>{value !== null && !assistantConversationTerminal(task.status) ? <progress value={value} max={100} aria-label={assistantConversationTaskTitle(task, snapshot.steps) + '进度'} /> : null}<AssistantTaskEta task={task} hidden={reconciling} />{reconciling ? <p role="status">上游是否受理尚未确认，请勿重复生成。</p> : null}{task.errorMessage && !reasons.includes(task.errorMessage) ? <p className="assistant-inline-error" role="alert">{task.errorMessage}</p> : null}</div>
    })}
  </section>
}

function InlineArtifact({ artifact, sessionId, store, onLocateTarget, preload, onRetryRead }: { artifact: AssistantArtifact; sessionId: string; store: AssistantSessionStore; onLocateTarget?: AssistantEffectHandler; preload?: { detail: AssistantArtifactDetail; media: AssistantArtifactMedia }; onRetryRead?: () => void }) {
  const host = useRef<HTMLElement>(null)
  const [visible, setVisible] = useState(Boolean(preload))
  const [detail, setDetail] = useState<AssistantArtifactDetail | null>(preload?.detail || null)
  const [media, setMedia] = useState<AssistantArtifactMedia | null>(preload?.media || null)
  const [chunks, setChunks] = useState<AssistantArtifactChunk[]>([])
  const [complete, setComplete] = useState(false)
  const [loading, setLoading] = useState(!preload)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [playbackError, setPlaybackError] = useState(false)
  const mounted = useRef(true), action = useRef(false)
  const identity = store.api.transport.identityKey()
  useEffect(() => {
    const element = host.current
    if (!element) return
    let active = true
    if (typeof IntersectionObserver === 'undefined') {
      void Promise.resolve().then(() => { if (active) setVisible(true) })
      return () => { active = false }
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '160px' })
    observer.observe(element)
    return () => { active = false; observer.disconnect() }
  }, [])
  useEffect(() => {
    mounted.current = true
    if (!visible) return
    if (preload && reload === 0) return
    let active = true
    const isCurrent = () => active && store.getSnapshot().snapshot?.sessionId === sessionId && store.api.transport.identityKey() === identity
    void (async () => {
      try {
        if (!isCurrent()) return
        const value = await store.api.artifactDetail(artifact.artifactId)
        if (!isCurrent()) return
        if (value.artifactId !== artifact.artifactId || value.revision !== artifact.revision || value.artifactType !== artifact.type) throw new Error('内容版本已变化，请重新读取')
        const body = isAssistantMediaArtifact(value) ? { media: verifyAssistantArtifactMedia(value, await store.api.artifactMedia(value.artifactId)), text: null }
          : { media: null, text: await mergeAssistantArtifactContent(value, [], await store.api.artifactContent(value.artifactId)) }
        if (!isCurrent()) return
        setDetail(value); setMedia(body.media); setChunks(body.text?.chunks || []); setComplete(body.text?.complete || false); setPlaybackError(false)
      } catch (cause) { if (isCurrent()) setError(assistantErrorMessage(cause)) }
      finally { if (isCurrent()) setLoading(false) }
    })()
    return () => { active = false; mounted.current = false }
  }, [store, sessionId, artifact.artifactId, artifact.revision, artifact.type, reload, visible, preload, identity])
  const more = async () => {
    const isCurrent = () => mounted.current && store.getSnapshot().snapshot?.sessionId === sessionId && store.api.transport.identityKey() === identity
    if (!detail || action.current || !isCurrent()) return
    action.current = true; setLoading(true); setError(null)
    try {
      const page = await store.api.artifactContent(artifact.artifactId, chunks.at(-1)?.chunkIndex ?? -1)
      if (!isCurrent()) return
      const merged = await mergeAssistantArtifactContent(detail, chunks, page)
      if (!isCurrent()) return
      if (!merged.complete && merged.nextAfterChunkIndex <= (chunks.at(-1)?.chunkIndex ?? -1)) throw new Error('正文仍在保存，请稍后继续读取')
      setChunks(merged.chunks); setComplete(merged.complete)
    } catch (cause) { if (isCurrent()) setError(assistantErrorMessage(cause)) }
    finally { action.current = false; if (isCurrent()) setLoading(false) }
  }
  const title = detail ? assistantArtifactTitle(detail) : artifact.title || (artifact.type === 'IMAGE' ? '生成的图片' : artifact.type === 'VIDEO' ? '生成的视频' : '创作结果')
  const reloadArtifact = () => {
    if (onRetryRead) { onRetryRead(); return }
    setLoading(true); setError(null); setDetail(null); setMedia(null); setChunks([]); setComplete(false); setPlaybackError(false); setReload((value) => value + 1)
  }
  return <article ref={host} className="assistant-inline-artifact" aria-label={title} data-assistant-artifact-id={artifact.artifactId}>
    <header><span>{title}</span><AssistantPromptAction effect={artifact.latestTargetEffect} onLocateTarget={onLocateTarget} /></header>
    {loading && !detail ? <p className="assistant-inline-loading" role="status">正在读取创作结果…</p> : null}
    {media?.artifactType === 'IMAGE' ? (
      // eslint-disable-next-line @next/next/no-img-element -- Only freshly authorized archive URLs are rendered, without proxying credentials.
      <img src={media.resourceUrl} alt={title} loading="lazy" referrerPolicy="no-referrer" onError={() => setPlaybackError(true)} />
    ) : media?.artifactType === 'VIDEO' ? <video src={media.resourceUrl} controls playsInline preload="metadata" aria-label={title} onError={() => setPlaybackError(true)} />
      : media ? <audio src={media.resourceUrl} controls preload="metadata" aria-label={title} onError={() => setPlaybackError(true)} />
        : detail && !isAssistantMediaArtifact(detail) ? <div className="assistant-inline-artifact-text"><AssistantConversationContent text={chunks.map((chunk) => chunk.content).join('')} />{!complete ? <Button type="text" loading={loading} onClick={() => void more()}>继续阅读正文</Button> : null}</div> : null}
    {playbackError ? <p className="assistant-inline-error" role="alert">媒体暂时无法播放。<Button type="link" disabled={loading} onClick={reloadArtifact}>重新加载</Button></p> : null}
    {error ? <p className="assistant-inline-error" role="alert">{error}<Button type="link" disabled={loading} onClick={reloadArtifact}>重新读取</Button></p> : null}
  </article>
}

export function AssistantMediaResultGroup({ artifacts, sessionId, store, onLocateTarget }: { artifacts: AssistantArtifact[]; sessionId: string; store: AssistantSessionStore; onLocateTarget?: AssistantEffectHandler }) {
  const [expanded, setExpanded] = useState(false)
  const host = useRef<HTMLElement>(null)
  const [visible, setVisible] = useState(false)
  const [reload, setReload] = useState(0)
  const [resolved, setResolved] = useState<{ identity: string; signature: string; reload: number; values: { artifact: AssistantArtifact; detail: AssistantArtifactDetail; media: AssistantArtifactMedia }[]; errors: string[] } | null>(null)
  const regionId = useId()
  const first = artifacts[0]
  const finalized = artifacts.filter(artifact => artifact.status === 'FINALIZED')
  const signature = JSON.stringify(finalized.map(artifact => [artifact.artifactId, artifact.revision]))
  const identity = store.api.transport.identityKey()
  const artifactRefs = useRef(finalized)
  useEffect(() => { artifactRefs.current = finalized }, [finalized])
  useEffect(() => {
    if (!host.current) return
    let active = true
    if (typeof IntersectionObserver === 'undefined') { void Promise.resolve().then(() => { if (active) setVisible(true) }); return () => { active = false } }
    const observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect() } }, { rootMargin: '160px' })
    observer.observe(host.current)
    return () => { active = false; observer.disconnect() }
  }, [])
  useEffect(() => {
    if (!visible) return
    let active = true, index = 0
    const sources = artifactRefs.current
    const values: NonNullable<typeof resolved>['values'] = [], errors: string[] = []
    const isCurrent = () => active && store.getSnapshot().snapshot?.sessionId === sessionId && store.api.transport.identityKey() === identity
    const read = async () => {
      while (isCurrent() && index < sources.length) {
        const artifact = sources[index++]
        try {
          const readKey = JSON.stringify([identity, sessionId, artifact.artifactId, artifact.revision])
          const detail = await mediaReadFlight(store, readKey + ':detail', () => store.api.artifactDetail(artifact.artifactId), isCurrent)
          if (!isCurrent()) return
          if (detail.artifactId !== artifact.artifactId || detail.revision !== artifact.revision || detail.artifactType !== artifact.type) throw new Error('内容版本已变化，请重新读取')
          const media = verifyAssistantArtifactMedia(detail, await mediaReadFlight(store, readKey + ':media', () => store.api.artifactMedia(artifact.artifactId), isCurrent))
          if (isCurrent()) values.push({ artifact, detail, media })
        } catch (cause) { if (isCurrent()) errors.push(assistantErrorMessage(cause)) }
      }
    }
    void Promise.all(Array.from({ length: Math.min(4, sources.length) }, read)).then(() => {
      if (isCurrent()) setResolved({ identity, signature, reload, values: sources.flatMap(artifact => values.filter(value => value.artifact.artifactId === artifact.artifactId)), errors })
    })
    return () => { active = false }
  }, [visible, signature, store, sessionId, reload, identity])
  if (!first) return null
  const label = first.type === 'VIDEO' ? '视频' : '图片'
  const ready = resolved?.signature === signature && resolved.identity === identity && resolved.reload === reload
    && store.getSnapshot().snapshot?.sessionId === sessionId ? resolved : null
  const values = ready?.values || []
  return <section ref={host} className={`assistant-media-result-group${expanded ? ' is-expanded' : ''}`} aria-label={`${label}生成结果`}>
    {values.length ? <button type="button" className="assistant-media-result-toggle" aria-expanded={expanded} aria-controls={regionId} onClick={() => setExpanded(value => !value)}>
      {first.type === 'VIDEO' ? <VideoCameraOutlined aria-hidden /> : <PictureOutlined aria-hidden />}<span>{label}已生成 × {values.length}</span>{expanded ? <DownOutlined aria-hidden /> : <RightOutlined aria-hidden />}
    </button> : !ready ? <p className="assistant-inline-loading" role="status">正在读取{label}结果…</p> : null}
    <div id={regionId} className="assistant-media-result-grid">
      {(expanded ? values : values.slice(0, 1)).map(value => <InlineArtifact key={`${sessionId}:${value.artifact.artifactId}:${value.artifact.revision}:${ready?.reload}:${value.media.resourceUrl}`} artifact={value.artifact} preload={value} onRetryRead={() => setReload(value => value + 1)} sessionId={sessionId} store={store} onLocateTarget={onLocateTarget} />)}
      {!expanded && values.length > 1 ? <button type="button" className="assistant-media-result-more" aria-label={`展开全部 ${values.length} 个${label}`} onClick={() => setExpanded(true)}>+{values.length - 1}</button> : null}
    </div>
    {ready?.errors.length ? <p className="assistant-inline-error" role="alert">部分{label}暂时无法读取。<Button type="link" loading={ready.reload !== reload} disabled={ready.reload !== reload} onClick={() => setReload(value => value + 1)}>重新读取</Button></p> : null}
  </section>
}

export function AssistantConversationTimeline({ renderArtifact, renderMessageActions, snapshot, store, busy, onLocateTarget, inputHistory = [], reasoning, publicSummaries = [], transientActivities = [], liveReply, pendingPrompt, pendingState, presentation, activeApprovalId }: { renderArtifact?: (artifact: AssistantArtifact) => ReactNode | undefined; renderMessageActions?: (message: AssistantMessage) => ReactNode; presentation?: 'canvas' | 'segmented'; activeApprovalId?: string; snapshot: AssistantSessionSnapshot; store: AssistantSessionStore; busy: boolean; onLocateTarget?: AssistantEffectHandler; inputHistory?: AssistantInputRequest[]; reasoning?: AssistantConversationReasoning; publicSummaries?: AssistantConversationReasoning[]; transientActivities?: AssistantActivity[]; liveReply?: AssistantConversationReasoning; progress?: AssistantConversationProgressEvent; pendingPrompt?: string; pendingState?: 'sending' | 'accepted' | 'unknown' }) {
  // The process anchor moves before the first persisted reply; disclosure belongs to the session/run, not that message parent.
  const [disclosures, setDisclosures] = useState<{ sessionId: string; runs: Record<string, { live: boolean; expanded: boolean }> }>({ sessionId: snapshot.sessionId, runs: {} })
  const requests = [...new Map([...inputHistory, ...(snapshot.inputRequest ? [snapshot.inputRequest] : [])].map((input) => [input.requestId, input])).values()].filter((input) => assistantQuestionSummary(input).length)
  const messages = [...snapshot.messages].sort((a, b) => a.seq - b.seq).filter((message) => (message.content.trim() || message.references?.length) && message.role !== 'system')
  const answers = requests.map((input) => ({ input, anchor: assistantQuestionSummaryAnchor(input, messages) }))
  const nodeSteps = snapshot.steps.filter((step) => assistantConversationNodeCreationKind(step.toolCode))
  const artifacts = [...new Map([...(snapshot.conversationArtifacts || []), ...snapshot.artifacts].map((artifact) => [artifact.artifactId, artifact])).values()].filter(artifact => !['IMAGE', 'VIDEO'].includes(artifact.type) || artifact.status === 'FINALIZED')
  const result = (group: AssistantArtifact[]) => {
    const custom = renderArtifact?.(group[0])
    if (custom !== undefined) return <Fragment key={group[0].artifactId}>{custom}</Fragment>
    return ['IMAGE', 'VIDEO'].includes(group[0].type)
    ? <AssistantMediaResultGroup key={isSegmented(presentation) ? `${snapshot.sessionId}:${group[0].producerRunId || ''}:${group[0].producerStepId || group[0].artifactId}:${group[0].type}` : `${snapshot.sessionId}:${group[0].producerRunId || group[0].artifactId}:${group[0].type}`} artifacts={group} sessionId={snapshot.sessionId} store={store} onLocateTarget={onLocateTarget} />
    : <InlineArtifact key={`${snapshot.sessionId}:${group[0].artifactId}:${group[0].revision}`} artifact={group[0]} sessionId={snapshot.sessionId} store={store} onLocateTarget={onLocateTarget} />
  }
  const Content = isSegmented(presentation) ? CanvasConversationContent : AssistantConversationContent
  const pendingReply = (liveReply?.live || liveReply?.awaitingCommit) && snapshot.activeRun && liveReply.runId === snapshot.activeRun.runId
    && !assistantConversationTerminal(snapshot.activeRun.status) && !snapshot.activeRun.stopRequested && liveReply.text.trim()
    && !messages.some((message) => message.role === 'assistant' && message.runId === liveReply.runId && message.content.trim() === liveReply.text.trim()) ? liveReply : null
  const showRun = !pendingPrompt || Boolean(snapshot.activeRun && !assistantConversationTerminal(snapshot.activeRun.status))
  const summaries = [...new Map([...publicSummaries, ...(reasoning ? [reasoning] : [])].map(summary => [summary.streamId || `${summary.runId}:${summary.stepId || ''}`, summary])).values()]
  const blocks = isSegmented(presentation) ? assistantConversationSegments(snapshot, messages, transientActivities, summaries)
    : assistantConversationActivityBlocks(snapshot, messages, transientActivities, summaries)
  const results = (isSegmented(presentation) ? assistantCanvasArtifactGroups(artifacts) : assistantConversationArtifactGroups(artifacts)).map(group => {
    const owner = isSegmented(presentation) && group[0].producerStepId ? blocks.find(block => 'stepIds' in block
      && block.runId === group[0].producerRunId && (block as AssistantConversationSegment).stepIds.includes(group[0].producerStepId!)) as AssistantConversationSegment | undefined : undefined
    return { group, segmentId: owner?.segmentId, anchor: owner ? {} : assistantConversationArtifactAnchor(group[0], messages) }
  })
  const segmentOutputs = (segment: AssistantConversationSegment) => [
    ...(snapshot.activeRun?.runId === segment.runId
      ? snapshot.steps.filter(step => segment.stepIds.includes(step.stepId)).flatMap(step =>
        step.effects.filter(canLocateAssistantEffect).map(effect =>
          <AssistantPromptAction key={effect.effectId} effect={effect} onLocateTarget={onLocateTarget} label="查看结果" />))
      : []),
    ...results.filter(item => item.segmentId === segment.segmentId).map(item => result(item.group))
  ]
  const process = (block: AssistantActivityBlock, showResults = true) => {
    const segment = 'segmentId' in block ? block as AssistantConversationSegment : undefined
    const key = segment?.segmentId || block.runId
    const displayBlock = segment?.kind === 'thinking' && block.live && block.summaries.length > 0 && !block.summaries.some(summary => summary.live)
      ? { ...block, live: false, state: 'complete' as const, label: '思考过程' } : block
    const choice = disclosures.sessionId === snapshot.sessionId ? disclosures.runs[key] : undefined
    const expanded = choice?.live === displayBlock.live ? choice.expanded : displayBlock.live || !!segment && segment.kind !== 'thinking' && block.state === 'failed'
    const thinking = <ThinkingProgress key={`process:${snapshot.sessionId}:${key}`} snapshot={snapshot} block={displayBlock} store={store} presentation={presentation} expanded={expanded}
      onToggle={() => setDisclosures(current => ({ sessionId: snapshot.sessionId,
        runs: { ...(current.sessionId === snapshot.sessionId ? current.runs : {}), [key]: { live: displayBlock.live, expanded: !expanded } } }))} />
    if (!segment) return thinking
    // The current decision card owns this exact waiting receipt; historical and completed receipts stay visible.
    if (activeApprovalId && segment.segmentId === `${snapshot.activeRun?.runId}:OPERATION_APPROVAL:${activeApprovalId}`
      && block.state === 'waiting' && !block.live) return <Fragment key={`decision-results:${key}`}>{showResults ? segmentOutputs(segment) : null}</Fragment>
    return <div key={`process:${snapshot.sessionId}:${key}`}>{thinking}
      {showResults ? segmentOutputs(segment) : null}
    </div>
  }
  const processes = (anchoredBlocks: AssistantActivityBlock[]) => {
    if (!isSegmented(presentation)) return anchoredBlocks.map(block => process(block))
    const groups: AssistantActivityBlock[][] = []
    for (const block of anchoredBlocks) {
      const previous = groups.at(-1)
      if (block.state === 'complete' && !block.live && previous?.every(item => item.runId === block.runId && item.state === 'complete' && !item.live)) {
        previous.push(block)
      } else {
        groups.push([block])
      }
    }
    return groups.flatMap(group => {
      if (group.length === 1) return [process(group[0])]
      const segments = group as AssistantConversationSegment[]
      return [
        <details key={'completed:' + segments[0].segmentId} className="assistant-completed-process">
          <summary>处理过程 · {group.length} 步已完成</summary>
          <div>{group.map(block => process(block, false))}</div>
        </details>,
        ...segments.flatMap(segmentOutputs)
      ]
    })
  }
  return <div className="assistant-conversation-timeline">
    {snapshot.nextMessageCursor ? <Button type="text" loading={busy} onClick={() => void store.loadOlder()}>查看更早的对话</Button> : null}
    {messages.flatMap((message) => { const entries = [
      ...processes(blocks.filter(block => block.beforeMessageId === message.id)),
      ...answers.filter((item) => item.anchor.beforeMessageId === message.id).map(({ input }) => <AnsweredQuestions key={input.requestId} input={input} />),
      ...results.filter((item) => item.anchor.beforeMessageId === message.id).map(({ group }) => result(group)),
      <article key={`message:${message.id}`} className={`assistant-conversation-message is-${message.role}`} aria-label={message.role === 'user' ? '你的消息' : '助手回复'}>{message.role === 'user' ? <AssistantMessageReferences references={message.references} /> : null}<Content text={message.content} />{renderMessageActions?.(message)}</article>,
      ...answers.filter((item) => item.anchor.afterMessageId === message.id).map(({ input }) => <AnsweredQuestions key={input.requestId} input={input} />),
      ...results.filter((item) => item.anchor.afterMessageId === message.id).map(({ group }) => result(group)),
      ...processes(blocks.filter(block => block.afterMessageId === message.id)),
    ]; return isSegmented(presentation) ? entries : [<div key={message.id} className="assistant-conversation-entry">{entries}</div>] })}
    {answers.filter((item) => !item.anchor.beforeMessageId && !item.anchor.afterMessageId).map(({ input }) => <AnsweredQuestions key={input.requestId} input={input} />)}
    {pendingPrompt ? <article className="assistant-conversation-message is-user" aria-label="你的消息"><AssistantConversationContent text={pendingPrompt} /><span className={`assistant-inline-loading${isSegmented(presentation) ? ' canvas-assistant-status-shimmer' : ''}`} role="status">{pendingState === 'unknown' ? '正在核实发送结果…' : pendingState === 'accepted' ? '已发送，正在同步…' : '正在发送…'}</span></article> : null}
    {pendingPrompt && pendingState !== 'unknown' && (!snapshot.activeRun || assistantConversationTerminal(snapshot.activeRun.status)) ? <p className={`assistant-live-stage${isSegmented(presentation) ? ' canvas-assistant-status-shimmer' : ''}`} role="status"><LoadingOutlined spin aria-hidden /> 正在准备本次处理…</p> : null}
    {processes(blocks.filter(block => !block.beforeMessageId && !block.afterMessageId))}
    {pendingReply ? <article className="assistant-conversation-message is-assistant" aria-label="助手回复"><Content text={pendingReply.text} />{pendingReply.awaitingCommit ? <span role="status">正在确认回复…</span> : null}</article> : null}
    {!isSegmented(presentation) && showRun && nodeSteps.map((step) => {
      const kind = assistantConversationNodeCreationKind(step.toolCode)
      const effects = step.effects.filter(canLocateAssistantEffect)
      return <div key={step.stepId} className="assistant-node-receipt">{kind === 'image' ? <PictureOutlined aria-hidden /> : kind === 'video' ? <VideoCameraOutlined aria-hidden /> : <FileTextOutlined aria-hidden />}<span>{assistantConversationStepDisplay(step, snapshot.tasks, snapshot.activeRun).label}</span>{effects.map((effect) => <AssistantPromptAction key={effect.effectId} effect={effect} onLocateTarget={onLocateTarget} label="查看" />)}</div>
    })}
    {results.filter((item) => !item.segmentId && !item.anchor.beforeMessageId && !item.anchor.afterMessageId).map(({ group }) => result(group))}
    {snapshot.artifactsHasMore || snapshot.conversationArtifactsHasMore ? <p className="assistant-inline-loading">更多结果可在画布中查看。</p> : null}
    {snapshot.activitiesHasMore ? <p className="assistant-inline-loading">此处显示最近的处理步骤。</p> : null}
  </div>
}
