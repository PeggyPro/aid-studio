'use client'
import { createRandomUuid } from '@/utils/clientId'

import { StudioModelTypeTabs } from '@/components/studio/composer/StudioModelTypeTabs'

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Modal, message } from 'antd'
import {
  ArrowRightOutlined,
  BulbOutlined,
  CloseOutlined,
  EditOutlined,
  SyncOutlined,
  HistoryOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined
} from '@ant-design/icons'
import AgentConversationPanel from '@/components/common/agent-conversation/AgentConversationPanel'
import { StudioCanvasEmptyOrbAnchor } from './StudioCanvasEmptyOrb'
import {
  StudioCanvasDropdownPopover,
  type StudioCanvasDropdownItem
} from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { useCanvasSkillCatalog } from '@/hooks/studio/useCanvasSkillCatalog'
import { applyStudioSkillSlashChoice, listStudioSkillSlashChoices, studioSkillSlashQuery } from '@/utils/studio/canvasSkillCatalog'
import { shouldPlayCanvasAgentTypewriter } from '@/utils/studio/canvasAgentTypewriter'
import { useCanvasAgentTypewriter } from './useCanvasAgentTypewriter'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioModelProviderIcon } from '../composer/StudioModelProviderIcon'
import { resolveUserModelProviderLogo } from '@/utils/userModelOption'
import type { UserModelListItem } from '@/types/business-api'
import { StudioCanvasSkillPicker } from './StudioCanvasSkillPicker'
import { StudioCanvasSkillSlashMenu } from './StudioCanvasSkillSlashMenu'
import { StudioComposerChip } from '../composer/StudioComposerChip'
import { StudioCreditSendButton } from '../composer/StudioCreditSendButton'
import type { StudioFreeAgentPanelRenderProps } from './StudioFreeAgentPanel'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { useCanvasProjectRuntime } from '../free/CanvasProjectRuntimeContext'
import {
  canvasConversationCancel, canvasConversationConfirmPlan, canvasConversationConfirmStep,
  canvasConversationDetail, canvasConversationList, canvasConversationOpen, canvasConversationQuoteMessage,
  canvasConversationSend, canvasConversationUpdatePlan, canvasConversationQuotePlan,
  canvasConversationRequoteMessage, canvasConversationRetryMessage,
  subscribeCanvasConversation,
  type CanvasConversationQuote, type CanvasConversationReference, type CanvasConversationSession,
  type CanvasConversationTurn
} from '@/utils/business/canvasConversation'
import './studio-canvas-conversation.css'

type Starter = {
  id: string
  title: string
  detail: string
  prompt: string
  available: boolean
  icon: ReactNode
  reason?: string
}

function GenerationModeIcon({ auto }: { auto?: boolean }) {
  if (auto) return <SyncOutlined />
  return (
    <svg viewBox="1 3.2 17 17" width="1em" height="1em" fill="none" aria-hidden>
      <path d="M8.2 11.2V6.6a1.4 1.4 0 0 1 2.8 0v4.6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M11 10.6V5.5a1.4 1.4 0 0 1 2.8 0V11" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M13.8 11.2V7.4a1.4 1.4 0 0 1 2.8 0V13.2c0 3.3-2.1 6.1-5.5 6.1h-.7c-2.5 0-4.3-1.3-5.1-3.2L3.8 12.4a1.35 1.35 0 0 1 2.3-1.4L8.2 13" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function CanvasAgentSelect({
  items,
  value,
  label,
  icon,
  ariaLabel,
  iconOnly = false,
  disabled = false,
  title,
  onSelect
}: {
  items: StudioCanvasDropdownItem[]
  value: string
  label: string
  icon: ReactNode
  ariaLabel: string
  iconOnly?: boolean
  disabled?: boolean
  title?: string
  onSelect: (key: string) => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <StudioCanvasDropdownPopover
      items={items}
      activeKey={value}
      onSelect={onSelect}
      open={open}
      onOpenChange={setOpen}
      placement="topLeft"
      ariaLabel={ariaLabel}
      getPopupContainer={getStudioComposerPopupContainer}
      trigger={iconOnly ? (
        <StudioCanvasTooltip title={title || `${ariaLabel}：${label}`} placement="top">
          <StudioComposerChip
            className="canvas-agent-tool-select is-icon-only"
            icon={icon}
            label={label}
            arrow={false}
            active={open}
            disabled={disabled}
            aria-label={`${ariaLabel}，当前为${label}`}
            aria-haspopup="menu"
            aria-expanded={open}
          />
        </StudioCanvasTooltip>
      ) : (
        <StudioComposerChip
          className="canvas-agent-tool-select"
          icon={icon}
          label={label}
          active={open}
          disabled={disabled}
          aria-label={`${ariaLabel}，当前为${label}`}
          aria-haspopup="menu"
          aria-expanded={open}
          title={title}
        />
      )}
    />
  )
}

const MODEL_TABS = [
  ['text', '文本'],
  ['image', '图片'],
  ['video', '视频']
] as const

function CanvasAgentModelSelect({
  lists,
  loading,
  value,
  disabled,
  onSelect
}: {
  lists: Record<(typeof MODEL_TABS)[number][0], UserModelListItem[]>
  loading: Record<(typeof MODEL_TABS)[number][0], boolean>
  value: string
  disabled: boolean
  onSelect: (modelCode: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState<(typeof MODEL_TABS)[number][0]>('text')
  const selected = MODEL_TABS.flatMap(([key]) => lists[key]).find((item) => item.modelCode === value)
  const options = lists[tab]
  const tabLoading = loading[tab] && !options.length
  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="topLeft"
      arrow={false}
      autoAdjustOverflow
      align={{ htmlRegion: 'visible', overflow: { adjustX: true, adjustY: true, shiftX: true, shiftY: true } }}
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
      content={(
        <div className="studio-model-menu studio-model-menu--tabbed" role="dialog" aria-label="选择对话模型">
          <StudioModelTypeTabs options={MODEL_TABS.map(([value, label]) => ({ value, label }))} value={tab} onChange={setTab} />
          <div className="studio-model-menu__list studio-canvas-popover-scroll" role="listbox">
            {tabLoading ? <p className="studio-model-menu__state">正在读取可用模型…</p> : null}
            {!tabLoading && !options.length ? <p className="studio-model-menu__state">暂无可用模型</p> : null}
            {options.map((item) => (
              <button key={item.modelCode} type="button" role="option" aria-selected={item.modelCode === value} className={item.modelCode === value ? 'is-active' : ''} onClick={() => { onSelect(item.modelCode); setOpen(false) }}>
                <i><StudioModelProviderIcon logo={resolveUserModelProviderLogo(item)} name={item.modelName} /></i>
                <span><strong>{item.modelName}</strong><small>{item.providerName || item.modelCode}</small></span>
              </button>
            ))}
          </div>
        </div>
      )}
    >
      <StudioComposerChip
        className="canvas-agent-tool-select"
        icon={selected ? <StudioModelProviderIcon logo={resolveUserModelProviderLogo(selected)} name={selected.modelName} /> : <RobotOutlined />}
        label={selected?.modelName || (loading.text ? '模型加载中' : '选择模型')}
        active={open}
        disabled={disabled}
        aria-label={`选择对话模型，当前为${selected?.modelName || '未选择'}`}
        aria-haspopup="dialog"
        aria-expanded={open}
      />
    </StudioCanvasPopover>
  )
}

const failure = (error: unknown) => message.error(error instanceof Error ? error.message : '操作失败，请稍后再试')
const quoteCredits = (value: number | null | undefined) =>
  Number(value ?? 0).toFixed(6).replace(/0+$/, '').replace(/\.$/, '')

export function StudioCanvasConversationPanel({
  onClose,
  initialSessionId,
  onStartNew,
  headerProps,
  headerActions: panelHeaderActions
}: { onClose: () => void; initialSessionId?: string; onStartNew?: () => void } & StudioFreeAgentPanelRenderProps) {
  const runtime = useCanvasProjectRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const contextIds = useStudioUiStore((state) => state.chatContextNodeIds)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const [session, setSession] = useState<CanvasConversationSession | null>(null)
  const [sessions, setSessions] = useState<Array<{ sessionId: string; title: string }>>([])
  const [chat, setChat] = useState('')
  const [preferredModelCode, setModelCode] = useState('')
  const [mode, setMode] = useState<'MANUAL' | 'AUTO'>('MANUAL')
  const [selectedSkillCode, setSelectedSkillCode] = useState('')
  const [slashCursor, setSlashCursor] = useState(0)
  const [slashIndex, setSlashIndex] = useState(0)
  const [slashDismissed, setSlashDismissed] = useState(false)
  const [composerSheen, setComposerSheen] = useState(false)
  const [composerIntroDone, setComposerIntroDone] = useState(false)
  const [composerFocused, setComposerFocused] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(false)
  const skillCatalog = useCanvasSkillCatalog()
  const chatRef = useRef<HTMLTextAreaElement>(null)
  const composerSheenTimer = useRef<number | null>(null)
  const playComposerSheen = useCallback(() => {
    if (composerSheenTimer.current !== null) window.clearTimeout(composerSheenTimer.current)
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setComposerSheen(false)
      setComposerIntroDone(true)
      return
    }
    setComposerIntroDone(false)
    setComposerSheen(false)
    window.requestAnimationFrame(() => {
      setComposerSheen(true)
      composerSheenTimer.current = window.setTimeout(() => {
        setComposerSheen(false)
        setComposerIntroDone(true)
      }, 3000)
    })
  }, [])
  const [quotedRequest, setQuote] = useState<{ key: string; value: CanvasConversationQuote } | null>(null)
  const [quoteError, setQuoteError] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [planDraft, setPlanDraft] = useState<{ key: string; prompt: string; model: string } | null>(null)
  const [diagnostic, setDiagnostic] = useState('')
  const [sessionMenuOpen, setSessionMenuOpen] = useState(false)
  const [referenceRevision, setReferenceRevision] = useState(0)
  const requestKey = useRef<string | null>(null)
  const syncedResultId = useRef<string | null>(null)
  const workspaceId = runtime?.project?.workspace.workspaceId ? String(runtime.project.workspace.workspaceId) : ''
  const refreshWorkspace = runtime?.refreshWorkspace
  const { data: models, loading: modelLoading } = useStudioModels('text', { canvas: true, enabled: Boolean(workspaceId) })
  const { data: imageModels, loading: imageModelLoading } = useStudioModels('image', { canvas: true, enabled: Boolean(workspaceId) })
  const { data: videoModels, loading: videoModelLoading } = useStudioModels('video', { canvas: true, enabled: Boolean(workspaceId) })
  const modelCode = preferredModelCode || models[0]?.modelCode || ''
  const latest = session?.turns.at(-1)
  const planKey = latest ? `${latest.id}:${latest.planVersion}` : ''
  const planPrompt = planDraft?.key === planKey ? planDraft.prompt : latest?.planPrompt || ''
  const planModel = planDraft?.key === planKey ? planDraft.model : latest?.planModel || ''

  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    const sync = () => setReducedMotion(media.matches)
    sync()
    media.addEventListener('change', sync)
    return () => media.removeEventListener('change', sync)
  }, [])

  useEffect(() => {
    playComposerSheen()
    return () => { if (composerSheenTimer.current !== null) window.clearTimeout(composerSheenTimer.current) }
  }, [playComposerSheen])

  useEffect(() => {
    queueMicrotask(() => { setMode('MANUAL'); setSelectedSkillCode('') })
    if (!workspaceId) {
      queueMicrotask(() => { setSession(null); setSessions([]) })
      return
    }
    let alive = true
    Promise.resolve().then(() => { if (alive) setLoading(true); return canvasConversationList(workspaceId) }).then(async (list) => {
      if (!alive) return
      setSessions(list.items)
      const current = initialSessionId ? list.items.find((item) => item.sessionId === initialSessionId) : list.items[0]
      if (initialSessionId && !current) throw new Error('历史会话不可读取，请重新选择')
      const detail = current ? await canvasConversationDetail(current.sessionId, workspaceId) : await canvasConversationOpen(workspaceId)
      if (alive && !current) setSessions([{ sessionId: detail.sessionId, title: detail.title }])
      if (alive) setSession(detail)
    }).catch(failure).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [workspaceId, initialSessionId])

  useEffect(() => {
    if (!session?.sessionId) return
    const stop = subscribeCanvasConversation(session.sessionId, workspaceId, (next) => {
      setSession(next)
      setSessions((current) => current.map((item) => item.sessionId === next.sessionId && item.title !== next.title
        ? { ...item, title: next.title } : item))
      const resultId = next.turns.at(-1)?.resultNodeId
      if (resultId && resultId !== syncedResultId.current) {
        syncedResultId.current = resultId
        void refreshWorkspace?.().catch(failure)
      }
    })
    return stop
  }, [session?.sessionId, workspaceId, refreshWorkspace])

  const referencedNodes = useMemo(() => nodes.filter((node) => contextIds.includes(node.id)), [nodes, contextIds])
  const references = useMemo<CanvasConversationReference[]>(() => {
    void referenceRevision // Re-read authoritative node versions after saveNow updates the baseline ref.
    return referencedNodes.flatMap((node) => {
      const version = runtime?.getNodeVersion(node.id)
      return node.data.kind === 'text' && node.data.documentContent?.trim() && version
        ? [{ objectId: node.id, expectedVersion: version }]
        : []
    })
  }, [referencedNodes, runtime, referenceRevision])
  const supportedReferencedNodes = useMemo(() => {
    const supportedIds = new Set(references.map((reference) => reference.objectId))
    return referencedNodes.filter((node) => supportedIds.has(node.id))
  }, [referencedNodes, references])
  const refKey = references.map((ref) => `${ref.objectId}:${ref.expectedVersion}`).join('|')
  const quoteKey = `${session?.sessionId || ''}:${chat.trim()}:${modelCode}:${refKey}`
  const quote = quotedRequest?.key === quoteKey ? quotedRequest.value : null

  useEffect(() => {
    if (!session?.sessionId || !chat.trim() || !modelCode || !runtime?.canEdit) return
    let alive = true
    const timer = window.setTimeout(() => {
      void canvasConversationQuoteMessage({ workspaceId, sessionId: session.sessionId, prompt: chat.trim(), modelCode, references })
        .then((value) => { if (alive) { setQuote({ key: quoteKey, value }); setQuoteError('') } })
        .catch((error: unknown) => { if (alive) { setQuote(null); setQuoteError(error instanceof Error ? error.message : '报价失败，请稍后重试') } })
    }, 450)
    return () => { alive = false; window.clearTimeout(timer) }
  }, [session?.sessionId, workspaceId, chat, modelCode, references, quoteKey, runtime?.canEdit])

  const canText = Boolean(workspaceId && runtime?.canEdit && runtime?.canMutate && models.length)
  const canStartTurn = canText && (!latest || ['DONE', 'FAILED', 'CANCELLED'].includes(latest.status))
  const starters: Starter[] = [
    { id: 'story', title: '一句话拆镜', detail: '从想法生成可编辑的镜头分镜', prompt: '把这个想法拆成 6 个镜头，写出每镜的画面、动作、镜头和时长：', available: canText, icon: <BulbOutlined />, reason: !workspaceId ? '请先保存为项目画布' : !runtime?.canEdit ? '当前账号没有编辑权限' : '暂无可用对话模型' },
    ...(references.length > 0 ? [{
      id: 'continue',
      title: '接着当前画布创作',
      detail: '结合已选文本继续完善内容',
      prompt: '根据当前画布内容继续拆镜：',
      available: canText,
      icon: <EditOutlined />,
      reason: '当前能力不可用'
    }] : [])
  ]

  const ensureSession = async () => {
    if (session) return session
    if (!workspaceId) throw new Error('请先保存项目画布')
    const opened = await canvasConversationOpen(workspaceId)
    setSession(opened)
    setSessions((current) => [{ sessionId: opened.sessionId, title: opened.title }, ...current])
    return opened
  }

  const submit = async () => {
    const prompt = chat.trim()
    if (!prompt || !modelCode || busy || !canStartTurn) return
    if (selectedSkillCode) { message.info('画布对话暂不支持执行 Skill，请切回通用创作'); return }
    setBusy(true)
    try {
      await runtime?.saveNow()
      const opened = await ensureSession()
      const savedReferences = referencedNodes.flatMap((node) => {
        const version = runtime?.getNodeVersion(node.id)
        return node.data.kind === 'text' && node.data.documentContent?.trim() && version
          ? [{ objectId: node.id, expectedVersion: version }] : []
      })
      const savedRefKey = savedReferences.map((ref) => `${ref.objectId}:${ref.expectedVersion}`).join('|')
      const savedQuoteKey = `${opened.sessionId}:${prompt}:${modelCode}:${savedRefKey}`
      const freshQuote = await canvasConversationQuoteMessage({ workspaceId, sessionId: opened.sessionId, prompt, modelCode, references: savedReferences })
      if (!quote || savedQuoteKey !== quoteKey || Number(quote.preHoldCredits) !== Number(freshQuote.preHoldCredits)) {
        if (savedRefKey !== refKey) setReferenceRevision((value) => value + 1)
        setQuote({ key: savedQuoteKey, value: freshQuote })
        message.info('报价已更新，请再次发送以授权本轮对话积分')
        return
      }
      const key = requestKey.current ?? createRandomUuid()
      requestKey.current = key
      const next = await canvasConversationSend({ workspaceId, sessionId: opened.sessionId, prompt, modelCode, references: savedReferences, clientRequestId: key, authorizedCredits: Number(freshQuote.preHoldCredits) })
      requestKey.current = null
      setSession(next)
      setChat('')
      setQuote(null)
      setDiagnostic('')
    } catch (error) { failure(error) }
    finally { setBusy(false) }
  }

  const savePlan = async () => {
    if (!session || !latest || busy) return
    setBusy(true)
    try { setSession(await canvasConversationUpdatePlan({ workspaceId, sessionId: session.sessionId, planVersion: latest.planVersion, prompt: planPrompt.trim(), modelCode: planModel })); setPlanDraft(null) }
    catch (error) { failure(error) }
    finally { setBusy(false) }
  }

  const confirmPlan = async () => {
    if (!session || !latest) return
    if (planPrompt.trim() !== latest.planPrompt || planModel !== latest.planModel) { message.info('请先保存计划修改'); return }
    let quoted: CanvasConversationQuote
    try { quoted = await canvasConversationQuotePlan(session.sessionId, workspaceId) }
    catch (error) { failure(error); return }
    const quote = Number(quoted.preHoldCredits)
    Modal.confirm({
      title: mode === 'AUTO' ? '确认自动生成预算' : '确认创作计划',
      content: mode === 'AUTO' ? `本轮最多授权 ${quoteCredits(quote)} 积分。服务端仅在此上限内执行。` : `本轮计划预计 ${quoteCredits(quote)} 积分；确认后仍需逐项确认扣费任务。`,
      okText: '确认计划', cancelText: '返回编辑',
      onOk: async () => {
        try {
          setSession(await canvasConversationConfirmPlan({ workspaceId, sessionId: session.sessionId, planVersion: latest.planVersion, mode, quotedCredits: quote, budgetCap: quote }))
        } catch (error) { failure(error); throw error }
      }
    })
  }

  const confirmStep = () => {
    if (!session || !latest) return
    Modal.confirm({
      title: '确认本项生成任务',
      content: `${latest.planModel} 将按当前输入生成文本分镜，权威预估 ${quoteCredits(latest.planQuote)} 积分。`,
      okText: '确认并生成', cancelText: '暂不生成',
      onOk: async () => {
        try {
          setSession(await canvasConversationConfirmStep({ workspaceId, sessionId: session.sessionId, planVersion: latest.planVersion, quotedCredits: Number(latest.planQuote ?? 0) }))
        } catch (error) { failure(error); throw error }
      }
    })
  }

  const retryChat = async () => {
    if (!session || !latest || latest.status !== 'PAUSED_CHAT' || busy) return
    setBusy(true)
    try {
      const quoted = await canvasConversationRequoteMessage(session.sessionId, workspaceId)
      Modal.confirm({
        title: '重新授权本轮对话',
        content: `当前对话模型预估 ${quoteCredits(quoted.preHoldCredits)} 积分。确认后继续提交本轮对话。`,
        okText: '确认并继续', cancelText: '暂不继续',
        onOk: async () => {
          try {
            setSession(await canvasConversationRetryMessage({
              workspaceId, sessionId: session.sessionId, authorizedCredits: Number(quoted.preHoldCredits)
            }))
            requestKey.current = null
            setChat('')
            setQuote(null)
          } catch (error) { failure(error); throw error }
        }
      })
    } catch (error) { failure(error) }
    finally { setBusy(false) }
  }

  const cancelTurn = async () => {
    if (!session) return
    try {
      setSession(await canvasConversationCancel(session.sessionId, workspaceId))
      requestKey.current = null
    } catch (error) { failure(error) }
  }

  const locateResult = async (nodeId: string | null | undefined) => {
    if (!nodeId) return
    try {
      await refreshWorkspace?.()
      selectNodes([nodeId])
      window.requestAnimationFrame(() => window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId } })))
    } catch (error) { failure(error) }
  }

  const diagnose = () => {
    const count = nodes.length
    const blank = nodes.filter((node) => node.data.kind === 'text' ? !node.data.documentContent?.trim() : node.data.kind === 'image' || node.data.kind === 'video' ? !node.data.mediaUrl : false).length
    setDiagnostic(`当前画布有 ${count} 个节点，其中 ${blank} 个图片、视频或文本节点尚无内容。可以先补全素材，再从“一句话拆镜”开始。此检查不调用生成模型，不扣生成积分。`)
  }

  const turnCard = (turn: CanvasConversationTurn) => <div key={turn.id} className="canvas-agent-turn">
    <div className="canvas-agent-turn__user"><small>我</small><p>{turn.prompt}</p></div>
    <div className="canvas-agent-turn__reply"><small>画布助手</small><p>{turn.replyText || (turn.status.startsWith('CHAT_') ? turn.chatTaskStatus === 'QUEUED' ? '对话任务排队中…' : '正在理解目标…' : turn.errorMessage || '')}</p></div>
    {turn.status === 'PLAN_READY' || turn.status === 'AWAITING_STEP' || turn.status.startsWith('ACTION_') || turn.status === 'PAUSED_RESULT' || turn.status === 'DONE' ? <section className="canvas-agent-plan">
      <strong>创作计划 · 第 {turn.planVersion} 版</strong>
      {turn.references?.length ? <div className="canvas-agent-plan__meta">来源节点：{turn.references.map((ref) => ref.objectId).join('、')}</div> : <div className="canvas-agent-plan__meta">来源：本轮文字描述</div>}
      <label>任务输入<textarea value={turn.id === latest?.id ? planPrompt : turn.planPrompt || ''} disabled={turn.status !== 'PLAN_READY' || busy} onChange={(event) => setPlanDraft({ key: planKey, prompt: event.target.value, model: planModel })} /></label>
      <label>执行模型<select value={turn.id === latest?.id ? planModel : turn.planModel || ''} disabled={turn.status !== 'PLAN_READY' || busy} onChange={(event) => setPlanDraft({ key: planKey, prompt: planPrompt, model: event.target.value })}>{models.map((model) => <option key={model.modelCode} value={model.modelCode}>{model.modelName}</option>)}</select></label>
      <div className="canvas-agent-plan__meta">文本分镜节点 · 单项任务</div>
      {turn.errorMessage ? <div role="alert" className="canvas-agent-turn__error">{turn.errorMessage}</div> : null}
      {turn.status === 'PLAN_READY' && turn.id === latest?.id ? <div className="canvas-agent-plan__actions"><Button size="small" onClick={() => void savePlan()} disabled={busy || !planPrompt.trim()}>保存修改</Button><Button size="small" type="primary" onClick={() => void confirmPlan()}>确认计划</Button></div> : null}
      {turn.status === 'AWAITING_STEP' && turn.id === latest?.id ? <Button size="small" type="primary" onClick={confirmStep}>确认本项任务</Button> : null}
      {['ACTION_SUBMITTING', 'ACTION_RUNNING', 'PAUSED_RESULT'].includes(turn.status) ? <div role="status">{turn.status === 'PAUSED_RESULT' ? '结果待写回画布，正在恢复…' : turn.actionTaskStatus === 'QUEUED' ? '分镜任务排队中…' : '任务已提交，正在生成分镜…'}</div> : null}
      {turn.status === 'DONE' ? <button type="button" className="canvas-agent-plan__result" onClick={() => void locateResult(turn.resultNodeId)}>分镜已写入画布 · 定位节点 ↗</button> : null}
    </section> : null}
    {['FAILED', 'PAUSED', 'PAUSED_CHAT', 'CANCELLED'].includes(turn.status) ? <div role="alert" className="canvas-agent-turn__error">{turn.errorMessage || '任务已停止'}{turn.status === 'PAUSED' && turn.id === latest?.id ? <Button size="small" onClick={() => void confirmPlan()}>重新报价并授权</Button> : null}{turn.status === 'PAUSED_CHAT' && turn.id === latest?.id ? <Button size="small" onClick={() => void retryChat()}>重新报价并继续对话</Button> : null}</div> : null}
  </div>

  const slashToken = studioSkillSlashQuery(chat, slashCursor)
  const slashChoices = slashToken ? listStudioSkillSlashChoices(skillCatalog.items, skillCatalog.favorites, slashToken.query) : []
  const slashActive = Math.min(slashIndex, Math.max(slashChoices.length - 1, 0))
  const composerEnabled = Boolean(workspaceId) && !busy
  const typewriterActive = shouldPlayCanvasAgentTypewriter({
    introDone: composerIntroDone,
    value: chat,
    focused: composerFocused,
    enabled: composerEnabled,
    reducedMotion
  })
  const typewriterText = useCanvasAgentTypewriter(typewriterActive)
  const composerPlaceholder = workspaceId ? '描述创意需求，输入 / 选择 Skill，按 Enter 发送' : '请先保存项目画布，才能开启真实对话'
  const applySlashChoice = (item: { code: string }) => {
    if (!slashToken) return
    const next = applyStudioSkillSlashChoice(chat, slashToken)
    const cursor = Math.min(slashToken.start, next.length)
    setChat(next)
    setSelectedSkillCode(item.code)
    setSlashIndex(0)
    setSlashDismissed(true)
    setSlashCursor(cursor)
    setQuoteError('')
    requestKey.current = null
    requestAnimationFrame(() => {
      const field = chatRef.current
      if (!field) return
      field.focus()
      field.setSelectionRange(cursor, cursor)
    })
  }

  return <AgentConversationPanel
    className="canvas-agent-panel"
    title="画布助手" updateToken={`${session?.sessionId}:${latest?.id}:${latest?.status}`}
    scopeKey={session?.sessionId || 'new'} orbMode={latest && ['CHAT_RUNNING', 'ACTION_RUNNING'].includes(latest.status) ? 'thinking' : 'listening'}
    showBrandOrb={false}
    headerProps={headerProps}
    headerActions={<div className="canvas-agent-header">
      <StudioCanvasDropdownPopover
        items={sessions.map((item) => ({ key: item.sessionId, label: item.title, icon: <HistoryOutlined /> }))}
        activeKey={session?.sessionId}
        onSelect={(sessionId) => {
          setLoading(true)
          setQuoteError('')
          void canvasConversationDetail(sessionId, workspaceId).then(setSession).catch(failure).finally(() => setLoading(false))
        }}
        open={sessionMenuOpen}
        onOpenChange={setSessionMenuOpen}
        placement="bottomRight"
        ariaLabel="选择历史对话"
        getPopupContainer={getStudioComposerPopupContainer}
        trigger={(
          <StudioCanvasTooltip title="历史对话" placement="bottomRight">
            <button
              type="button"
              className="canvas-agent-header__action"
              aria-label="历史对话"
              aria-haspopup="menu"
              aria-expanded={sessionMenuOpen}
              disabled={!sessions.length}
            >
              <HistoryOutlined />
            </button>
          </StudioCanvasTooltip>
        )}
      />
      <StudioCanvasTooltip title="新对话" placement="bottomRight">
        <button type="button" className="canvas-agent-header__action" aria-label="新对话" onClick={() => {
          if (busy) return
          if (onStartNew) { onStartNew(); return }
          if (!workspaceId) return
          setLoading(true)
          void canvasConversationOpen(workspaceId).then((opened) => {
            setSession(opened)
            setSessions((current) => [{ sessionId: opened.sessionId, title: opened.title }, ...current])
            setChat('')
            setQuote(null)
            setQuoteError('')
            setMode('MANUAL')
            playComposerSheen()
          }).catch(failure).finally(() => setLoading(false))
        }}><PlusOutlined /></button>
      </StudioCanvasTooltip>
      {panelHeaderActions}
      <StudioCanvasTooltip title="关闭对话" placement="bottomRight">
        <button type="button" className="canvas-agent-header__action" aria-label="关闭对话" onClick={onClose}>
          <CloseOutlined />
        </button>
      </StudioCanvasTooltip>
    </div>}
    initialLoading={loading}
    emptySlot={!session?.turns.length ? <div className="canvas-agent-empty">
      <div className="canvas-agent-empty__intro">
        <StudioCanvasEmptyOrbAnchor />
        <div className="canvas-agent-empty__headline">{references.length ? '继续完善当前画布' : '今天想创作什么？'}</div>
        <p>{references.length ? '已带入可用的文本节点，也可以从一个新想法开始。' : '直接描述你的想法，或选择一个创作起点。'}</p>
      </div>
      <section className="canvas-agent-empty__actions" aria-label="创作起点">
        <div className="canvas-agent-empty__section-title">创作起点</div>
        <div className="canvas-agent-starters">{starters.map((starter) => <button
        key={starter.id}
        type="button"
        className={`canvas-agent-starter is-${starter.id}`}
        disabled={!starter.available}
        title={starter.reason}
        onClick={() => { setChat(starter.prompt); setDiagnostic('') }}
      >
        <span className="canvas-agent-starter__icon" aria-hidden>{starter.icon}</span>
        <span className="canvas-agent-starter__copy"><strong>{starter.title}</strong><small>{starter.available ? starter.detail : starter.reason}</small></span>
        <ArrowRightOutlined className="canvas-agent-starter__arrow" aria-hidden />
      </button>)}</div>
        <button type="button" className="canvas-agent-diagnostic" onClick={diagnose}>
          <span className="canvas-agent-starter__icon" aria-hidden><SearchOutlined /></span>
          <span className="canvas-agent-starter__copy"><strong>检查当前画布</strong><small>查看缺失内容，不调用生成模型</small></span>
          <ArrowRightOutlined className="canvas-agent-starter__arrow" aria-hidden />
        </button>
      </section>
      {diagnostic ? <p className="canvas-agent-empty__diagnostic" role="status">{diagnostic}</p> : null}
    </div> : null}
    composer={<div className={`canvas-agent-composer${composerSheen ? ' is-awakening' : ''}${typewriterActive ? ' is-typewriting' : ''}`} onMouseDown={(event) => {
      const target = event.target as HTMLElement
      if (target.closest('button, textarea, input, a, select')) return
      chatRef.current?.focus()
    }}>
      {composerSheen ? <span className="canvas-agent-composer__glow" aria-hidden>
        <span className="canvas-agent-composer__glow-halo" />
        <span className="canvas-agent-composer__glow-aura" />
        <span className="canvas-agent-composer__glow-shine" />
        <span className="canvas-agent-composer__glow-ring" />
      </span> : null}
      {slashToken && !slashDismissed && canStartTurn && !busy ? <StudioCanvasSkillSlashMenu query={slashToken.query} items={slashChoices} activeIndex={slashActive} onHover={setSlashIndex} onSelect={applySlashChoice} /> : null}
      {supportedReferencedNodes.length ? <div className="canvas-agent-composer__refs" aria-label="已引用节点">
        {supportedReferencedNodes.map((node) => <span key={node.id}>{node.data.title}</span>)}
      </div> : null}
      <div className="canvas-agent-composer__field">
        {typewriterActive ? <span className="canvas-agent-composer__typewriter" aria-hidden="true">{typewriterText}<i /></span> : null}
        <textarea ref={chatRef} value={chat} rows={3} placeholder={composerPlaceholder} disabled={!composerEnabled} onFocus={() => setComposerFocused(true)} onBlur={() => setComposerFocused(false)} onChange={(event) => { setChat(event.target.value); setSlashCursor(event.target.selectionStart ?? event.target.value.length); setSlashIndex(0); setSlashDismissed(false); setQuoteError(''); requestKey.current = null }} onClick={(event) => setSlashCursor(event.currentTarget.selectionStart ?? 0)} onKeyUp={(event) => setSlashCursor(event.currentTarget.selectionStart ?? 0)} onKeyDown={(event) => {
        if (event.nativeEvent.isComposing) return
        if (slashToken && !slashDismissed && slashChoices.length) {
          if (event.key === 'ArrowDown') { event.preventDefault(); setSlashIndex((index) => (index + 1) % slashChoices.length); return }
          if (event.key === 'ArrowUp') { event.preventDefault(); setSlashIndex((index) => (index - 1 + slashChoices.length) % slashChoices.length); return }
          if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); applySlashChoice(slashChoices[slashActive]); return }
          if (event.key === 'Escape') { event.preventDefault(); setSlashDismissed(true); return }
        }
        if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submit() }
      }} />
      </div>
      <div className="canvas-agent-composer__toolbar">
        <div className="canvas-agent-composer__tools">
          <CanvasAgentModelSelect
            lists={{ text: models, image: imageModels, video: videoModels }}
            loading={{ text: modelLoading, image: imageModelLoading, video: videoModelLoading }}
            value={modelCode}
            disabled={!workspaceId || busy}
            onSelect={setModelCode}
          />
          <StudioCanvasSkillPicker selectedCode={selectedSkillCode} defaultModelCode={modelCode} catalog={skillCatalog} onSelect={setSelectedSkillCode} />
          <CanvasAgentSelect
            items={[
              { key: 'MANUAL', label: '手动生成', description: '每次生成前询问，确认后再执行', icon: <GenerationModeIcon /> },
              { key: 'AUTO', label: '自动生成', description: '在授权额度内直接执行，并消耗积分', icon: <GenerationModeIcon auto /> }
            ]}
            value={mode}
            label={mode === 'AUTO' ? '自动生成' : '手动生成'}
            icon={mode === 'AUTO' ? <GenerationModeIcon auto /> : <GenerationModeIcon />}
            ariaLabel="选择生成模式"
            iconOnly
            title={`生成模式 · ${mode === 'AUTO' ? '自动生成' : '手动生成'}`}
            disabled={busy || Boolean(latest && !['PLAN_READY', 'DONE', 'FAILED', 'CANCELLED'].includes(latest.status))}
            onSelect={(key) => setMode(key as 'MANUAL' | 'AUTO')}
          />
        </div>
        <StudioCreditSendButton
          className="canvas-agent-composer__send-button"
          credits={Number(quote?.preHoldCredits ?? 0)}
          showCredits={false}
          authoritative
          hasContent={Boolean(chat.trim())}
          mode="agent"
          loading={busy}
          disabled={!canStartTurn || !quote || busy || Boolean(selectedSkillCode)}
          disabledReason={selectedSkillCode ? '画布对话暂未接入 Skill 执行，请切回通用创作' : quoteError || (!quote && chat.trim() ? '正在准备发送' : '')}
          onSend={() => void submit()}
        />
      </div>
      {quoteError ? <div className="canvas-agent-composer__error" role="alert">{quoteError}</div> : null}
      <span className="sr-only" aria-live="polite">{quoteError || (chat.trim() && !quote ? '正在准备发送' : '')}</span>
      {latest && ['CHAT_RUNNING', 'ACTION_RUNNING', 'AWAITING_STEP', 'PAUSED_CHAT', 'PAUSED'].includes(latest.status) ? <button type="button" className="canvas-agent-cancel" onClick={() => void cancelTurn()}>取消本轮</button> : null}
    </div>}
  >{session?.turns.map(turnCard)}</AgentConversationPanel>
}
