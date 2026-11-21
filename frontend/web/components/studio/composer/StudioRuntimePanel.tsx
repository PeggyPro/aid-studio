'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { AppstoreOutlined, CloseOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import type { StudioCanvasNode } from '@/types/studio'
import type { BillingQuoteRequest, UserModelListItem } from '@/types/business-api'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import type { CanvasRuntimeGroup, CanvasRuntimeMember, CanvasRuntimeRun } from '@/types/canvas-runtime-api'
import { canvasRuntimeCancel, canvasRuntimeGroups, canvasRuntimeMembers, canvasRuntimeModels, canvasRuntimeRespond, canvasRuntimeRunDetail, canvasRuntimeRunHistory } from '@/utils/business/canvasRuntime'
import { canvasUserModelList } from '@/utils/business/model'
import { useCanvasBillingQuote } from '@/hooks/studio/useCanvasBillingQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { canvasErrorMessage } from '@/utils/canvas/canvasErrorMessage'
import { canvasOperationId } from '@/utils/canvas/canvasId'
import { canvasGenerationCapability } from '@/utils/studio/canvasGenerationModels'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import {
  compatibleCanvasRuntimeMembers,
  studioRuntimeMemberTypeCopy,
  studioRuntimeNodeKindLabel,
  studioRuntimeOutputKind,
  studioRuntimeRunLabel,
  studioRuntimeStatusCopy,
  type StudioRuntimeWorkspaceView
} from '@/utils/studio/studioRuntimeWorkspace'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioCreatorPanel } from './StudioCreatorPanel'
import './studio-runtime-workspace.css'
import { useStudioSkillWorkspace } from '@/components/studio/skills/StudioSkillWorkspaceProvider'

const FINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELED'])

function runError(run: CanvasRuntimeRun): string {
  const details = run.errorDetails
  return [run.errorMessage, details?.reason, details?.field ? `字段：${details.field}` : '',
    details?.allowedValues?.length ? `可选值：${details.allowedValues.join('、')}` : '']
    .filter(Boolean).join('；') || '运行失败'
}

export function StudioRuntimePanel({ node, runtime }: { node: StudioCanvasNode; runtime: CanvasProjectWorkspaceRuntime }) {
  const skillWorkspace = useStudioSkillWorkspace()
  const workspaceId = String(runtime.project?.workspace.workspaceId || '')
  const [open, setOpen] = useState(false)
  const [view, setView] = useState<StudioRuntimeWorkspaceView>('run')
  const [loading, setLoading] = useState(false)
  const [groups, setGroups] = useState<CanvasRuntimeGroup[]>([])
  const [groupCode, setGroupCode] = useState('')
  const [members, setMembers] = useState<CanvasRuntimeMember[]>([])
  const [groupModelCount, setGroupModelCount] = useState<number | null>(null)
  const [memberCode, setMemberCode] = useState('')
  const [modelSelection, setModelSelection] = useState<{ memberCode: string; modelCode: string } | null>(null)
  const [prompt, setPrompt] = useState(node.data.prompt || '')
  const [runSelection, setRunSelection] = useState<{ workspaceId: string; nodeId: string; value: CanvasRuntimeRun } | null>(null)
  const [answer, setAnswer] = useState('')
  const [historySelection, setHistorySelection] = useState<{ workspaceId: string; nodeId: string; items: CanvasRuntimeRun[] } | null>(null)
  const invokingRef = useRef(false)
  const run = runSelection?.workspaceId === workspaceId && runSelection.nodeId === node.id ? runSelection.value : null
  const history = historySelection?.workspaceId === workspaceId && historySelection.nodeId === node.id
    ? historySelection.items : []
  const setRun = useCallback((value: CanvasRuntimeRun | null) => {
    setRunSelection(value ? { workspaceId, nodeId: node.id, value } : null)
  }, [workspaceId, node.id])
  const setHistory = useCallback((items: CanvasRuntimeRun[]) => {
    setHistorySelection({ workspaceId, nodeId: node.id, items })
  }, [workspaceId, node.id])
  const prependHistory = useCallback((value: CanvasRuntimeRun) => {
    setHistorySelection((current) => {
      const items = current?.workspaceId === workspaceId && current.nodeId === node.id ? current.items : []
      return { workspaceId, nodeId: node.id,
        items: [value, ...items.filter((item) => String(item.runId) !== String(value.runId))] }
    })
  }, [workspaceId, node.id])
  const selectedRunId = String(run?.runId || '')
  const selectedRunStatus = run?.status
  const observeRun = runtime.observeRun
  const member = members.find((item) => item.memberCode === memberCode)
  const modelCode = modelSelection?.memberCode === memberCode && member?.models.some((item) => item.modelCode === modelSelection.modelCode)
    ? modelSelection.modelCode : member?.defaultModelCode || member?.models[0]?.modelCode || ''
  const model = member?.models.find((item) => item.modelCode === modelCode)
  const expectedKind = studioRuntimeOutputKind(node.data.kind) ?? 'VIDEO'
  const [catalogSelection, setCatalogSelection] = useState<{ key: string; item: UserModelListItem | null }>({ key: '', item: null })
  const catalogKey = `${expectedKind}:${modelCode}`
  useEffect(() => {
    if (!open || !modelCode) return
    let active = true
    const modelType = expectedKind.toLowerCase() as 'text' | 'image' | 'video'
    void canvasUserModelList({ modelType,
      ...(modelType === 'text' ? {} : { generateMode: canvasGenerationCapability(modelType, false) }) })
      .then((items) => { if (active) setCatalogSelection({ key: catalogKey,
        item: items.find((item) => item.modelCode === modelCode) ?? null }) })
      .catch(() => { if (active) setCatalogSelection({ key: catalogKey, item: null }) })
    return () => { active = false }
  }, [open, catalogKey, expectedKind, modelCode])
  const projection = catalogSelection.key === catalogKey ? catalogSelection.item : null
  const billingRequest: BillingQuoteRequest | null = !open || !member || !model || !projection || !prompt.trim()
    ? null : expectedKind === 'TEXT'
      ? { quoteType: 'MEDIA_TEXT', payload: { modelName: model.modelCode, prompt: prompt.trim() } }
      : expectedKind === 'IMAGE'
        ? { quoteType: 'MEDIA_IMAGE_PRICING', payload: { modelCode: model.modelCode,
            generateMode: 'TEXT_TO_IMAGE', imageCount: 1, expectedImageCount: 1,
            ...(model.capability?.defaultSize ? { size: model.capability.defaultSize, resolution: model.capability.defaultSize } : {}) } }
        : { quoteType: 'MEDIA_VIDEO', payload: { modelName: model.modelCode,
            capabilityCode: projection.capabilityCode, prompt: prompt.trim(),
            ...(model.capability?.defaultDurationSeconds ? { durationSeconds: model.capability.defaultDurationSeconds } : {}),
            ...(model.capability?.defaultAspectRatio ? { aspectRatio: model.capability.defaultAspectRatio } : {}) } }
  const billing = useCanvasBillingQuote(billingRequest)
  const quotedCredits = studioCanvasQuoteCredits(billing.quote)
  const quoteError = billing.error || (billing.quote?.matched === false ? '当前参数没有可用计费规则' : '')
  const nodeLabel = studioRuntimeNodeKindLabel(node.data.kind)
  const invokeDisabled = loading || !runtime.canEdit || !member || !model || runtime.isNodeBusy(node.id)
    || quotedCredits == null
  const invokeReason = loading
    ? '正在提交运行'
    : !runtime.canEdit
      ? '当前账号只有查看权限'
      : runtime.isNodeBusy(node.id)
        ? '该节点正在生成中'
        : !member
          ? '请选择一个能力'
          : !model
            ? '当前能力没有可用模型'
            : ''

  useEffect(() => {
    if (!open) return
    let active = true
    canvasRuntimeGroups({ pageSize: 100 }).then((result) => {
      if (!active) return
      setGroups(result.data)
      if (!groupCode && result.data.length) setGroupCode(result.data[0].groupCode)
    }).catch((error) => message.error(canvasErrorMessage(error, '能力目录加载失败')))
    return () => { active = false }
  }, [open, groupCode])

  useEffect(() => {
    if (!open || !groupCode) return
    let active = true
    canvasRuntimeMembers({ groupCode, pageSize: 100 }).then((result) => {
      if (!active) return
      const compatible = compatibleCanvasRuntimeMembers(result.data, expectedKind)
      setMembers(compatible)
      setMemberCode((current) => compatible.some((item) => item.memberCode === current) ? current : compatible[0]?.memberCode || '')
    }).catch((error) => message.error(canvasErrorMessage(error, '能力成员加载失败')))
    void canvasRuntimeModels({ groupCode, pageSize: 100 }).then((result) => {
      if (active) setGroupModelCount(result.total)
    }).catch(() => { if (active) setGroupModelCount(null) })
    return () => { active = false }
  }, [open, groupCode, expectedKind])

  useEffect(() => {
    if (!open || !workspaceId) return
    let active = true
    void canvasRuntimeRunHistory({ workspaceId, nodeObjectId: node.id, pageSize: 20 })
      .then((result) => { if (active) setHistory(result.items) })
      .catch((error) => { if (active) message.error(canvasErrorMessage(error, '运行历史加载失败')) })
    return () => { active = false }
  }, [open, node.id, workspaceId, setHistory])

  useEffect(() => {
    if (!open || !workspaceId || !selectedRunId || !selectedRunStatus || FINAL.has(selectedRunStatus)) return
    let active = true
    let inFlight = false
    let trailing = false
    let errorShown = false
    const refresh = () => {
      if (inFlight) { trailing = true; return }
      inFlight = true
      void (async () => {
        do {
          trailing = false
          try {
            const detail = await canvasRuntimeRunDetail(selectedRunId)
            if (active && String(detail.runId) === selectedRunId
              && (detail.workspaceId == null || String(detail.workspaceId) === workspaceId)
              && (detail.nodeObjectId == null || detail.nodeObjectId === node.id)) {
              errorShown = false
              setRun(detail)
            }
          } catch (error) {
            if (active && !errorShown) {
              errorShown = true
              message.error(canvasErrorMessage(error, '运行状态加载失败'))
            }
          }
        } while (active && trailing)
        inFlight = false
      })()
    }
    const unsubscribe = observeRun(selectedRunId, node.id, refresh)
    refresh()
    return () => { active = false; unsubscribe() }
  }, [open, workspaceId, selectedRunId, selectedRunStatus, node.id, observeRun, setRun])

  const invoke = async () => {
    if (invokingRef.current || !member || !model) return
    invokingRef.current = true
    setLoading(true)
    try {
      if (quotedCredits == null) throw new Error('请等待服务端报价后再运行')
      if (!projection?.capabilityCode) throw new Error('该模型未返回可提交的能力编码，请刷新模型目录')
      const result = await runtime.invokeMember(node.id, {
        memberType: member.memberType,
        memberCode: member.memberCode,
        prompt,
        modelCode: model.modelCode,
        capabilityCode: projection.capabilityCode,
        ...(expectedKind === 'IMAGE' && model.capability?.defaultSize ? { size: model.capability.defaultSize } : {}),
        ...(expectedKind === 'IMAGE' ? { expectedImageCount: 1 } : {}),
        ...(expectedKind === 'VIDEO' && model.capability?.defaultDurationSeconds
          ? { durationSeconds: model.capability.defaultDurationSeconds } : {}),
        ...(expectedKind === 'VIDEO' && model.capability?.defaultAspectRatio
          ? { aspectRatio: model.capability.defaultAspectRatio } : {})
      })
      setRun(result)
      prependHistory(result)
      if (result.status === 'FAILED') message.error(runError(result))
    } catch (error) {
      message.error(canvasErrorMessage(error, '能力运行失败'))
    } finally {
      invokingRef.current = false
      setLoading(false)
    }
  }

  const workspace = (
    <div className="studio-runtime-workspace" role="dialog" aria-label="画布能力工作台" aria-busy={loading}>
      <header className="studio-runtime-workspace__header">
        <div className="studio-runtime-workspace__tabs" role="tablist" aria-label="能力工作台视图">
          <button type="button" role="tab" aria-label="打开能力运行" aria-selected={view === 'run'} className={view === 'run' ? 'is-active' : ''} onClick={() => setView('run')}>能力运行</button>
          <button type="button" role="tab" aria-label="打开我的定义" aria-selected={view === 'library'} className={view === 'library' ? 'is-active' : ''} onClick={() => { if (skillWorkspace) { setOpen(false); skillWorkspace.openManager() } else setView('library') }}>我的定义</button>
        </div>
        <p className="studio-runtime-workspace__binding">绑定 {nodeLabel}</p>
        <button type="button" className="studio-runtime-workspace__close" aria-label="关闭能力工作台" onClick={() => setOpen(false)}>
          <CloseOutlined />
        </button>
      </header>
      {view === 'library' && !skillWorkspace ? <StudioCreatorPanel /> : (
        <div className="studio-runtime-workspace__body">
          <aside className="studio-runtime-workspace__rail" aria-label="能力分组">
            {groups.length ? groups.map((item) => (
              <button
                key={item.groupCode}
                type="button"
                className={groupCode === item.groupCode ? 'is-active' : ''}
                onClick={() => { setGroupCode(item.groupCode); setMemberCode(''); setRun(null) }}
              >
                <strong>{item.name}</strong>
                {item.description ? <small>{item.description}</small> : null}
              </button>
            )) : <p className="studio-runtime-workspace__empty">暂无能力分组</p>}
          </aside>
          <section className="studio-runtime-workspace__catalog" aria-label="Agent / Skill">
            <header>
              <strong>选择能力</strong>
              <span>只显示能写入{nodeLabel}的成员</span>
            </header>
            <div className="studio-runtime-workspace__cards">
              {members.length ? members.map((item) => (
                <button
                  key={item.memberCode}
                  type="button"
                  className={`studio-runtime-workspace__card${memberCode === item.memberCode ? ' is-active' : ''}`}
                  onClick={() => { setMemberCode(item.memberCode); setRun(null) }}
                >
                  <span className="studio-runtime-workspace__card-kicker">{studioRuntimeMemberTypeCopy(item.memberType)}</span>
                  <strong>{item.name}</strong>
                  <small>{item.description || item.memberCode}</small>
                </button>
              )) : <p className="studio-runtime-workspace__empty">该分组没有匹配当前节点的能力</p>}
            </div>
          </section>
          <section className="studio-runtime-workspace__bench" aria-label="运行台">
            <header>
              <strong>{member?.name || '运行台'}</strong>
              {groupModelCount !== null ? <span>本组 {groupModelCount} 个模型，当前能力只显示允许的型号</span> : <span>结果会写入当前节点历史</span>}
            </header>
            <div className="studio-runtime-workspace__models" role="list" aria-label="模型">
              {(member?.models || []).map((item) => (
                <button
                  key={item.modelCode}
                  type="button"
                  role="listitem"
                  className={modelCode === item.modelCode ? 'is-active' : ''}
                  onClick={() => setModelSelection({ memberCode, modelCode: item.modelCode })}
                >{item.modelName || item.modelCode}</button>
              ))}
              {!member?.models.length ? <p className="studio-runtime-workspace__empty">请先选择能力</p> : null}
            </div>
            <label className="studio-runtime-workspace__prompt">
              输入内容
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                rows={5}
                maxLength={100000}
                placeholder="输入本次运行的要求"
              />
            </label>
            {member && model ? <p className="studio-runtime-workspace__quote" role="status">
              {quotedCredits != null && !quoteError
                ? `${member.memberType === 'AGENT' ? '当前模型单次调用预估' : '本次模型调用预估'}：${billing.quote?.isFree ? '免费' : `${quotedCredits} 积分`}${member.memberType === 'AGENT' ? '；多步骤运行可能产生额外费用' : ''}` : '\u00a0'}
            </p> : null}
            <button
              type="button"
              className="studio-runtime-workspace__primary"
              disabled={invokeDisabled}
              title={invokeReason || undefined}
              onClick={() => void invoke()}
            >运行能力</button>
            {run ? (
              <section className="studio-runtime-workspace__result" aria-live="polite">
                <div className="studio-runtime-workspace__result-head">
                  <strong>运行结果</strong>
                  <span className={`studio-runtime-workspace__status is-${run.status.toLowerCase()}`}>{studioRuntimeStatusCopy(run.status)}</span>
                </div>
                {run.status === 'FAILED' ? <p className="studio-runtime-workspace__error" role="alert">{runError(run)}</p> : null}
                {run.resultPreview?.textContent ? <pre>{run.resultPreview.textContent}</pre> : null}
                {run.resultPreview?.resultUrl ? <a href={run.resultPreview.resultUrl} target="_blank" rel="noreferrer">查看结果</a> : null}
                {run.status === 'SUCCEEDED' && expectedKind === 'TEXT' ? (
                  <div className="studio-runtime-workspace__actions">
                    <button type="button" onClick={() => void runtime.adoptRun(node.id, String(run.runId), 'REPLACE').then(() => message.success('已替换节点正文')).catch((error) => message.error(canvasErrorMessage(error, '采纳失败')))}>替换正文</button>
                    <button type="button" onClick={() => void runtime.adoptRun(node.id, String(run.runId), 'APPEND').then(() => message.success('已追加到节点正文')).catch((error) => message.error(canvasErrorMessage(error, '采纳失败')))}>追加正文</button>
                    <button type="button" onClick={() => void runtime.adoptRun(node.id, String(run.runId), 'CREATE_NODE').then(() => message.success('已创建文本节点')).catch((error) => message.error(canvasErrorMessage(error, '采纳失败')))}>新建文本节点</button>
                  </div>
                ) : null}
                {run.status === 'WAITING_INPUT' ? (
                  <div className="studio-runtime-workspace__reply">
                    <input value={answer} onChange={(event) => setAnswer(event.target.value)} placeholder="回复运行中的问题" aria-label="回复内容" />
                    <button type="button" className="studio-runtime-workspace__primary" disabled={!answer.trim()} onClick={() => void canvasRuntimeRespond(run.runId, answer, canvasOperationId('runtime_answer')).then((next) => { setRun(next); setAnswer('') }).catch((error) => message.error(canvasErrorMessage(error, '回复失败')))}>回复</button>
                  </div>
                ) : null}
                {!FINAL.has(run.status) ? (
                  <button type="button" className="studio-runtime-workspace__danger" onClick={() => void canvasRuntimeCancel(run.runId).then(setRun).catch((error) => message.error(canvasErrorMessage(error, '取消失败')))}>取消运行</button>
                ) : null}
              </section>
            ) : null}
            {history.length ? (
              <section className="studio-runtime-workspace__history">
                <strong>最近运行</strong>
                {history.map((item) => (
                  <button
                    key={String(item.runId)}
                    type="button"
                    onClick={() => void canvasRuntimeRunDetail(item.runId).then(setRun).catch((error) => message.error(canvasErrorMessage(error, '读取运行失败')))}
                  >{studioRuntimeRunLabel(item)}</button>
                ))}
              </section>
            ) : null}
          </section>
        </div>
      )}
    </div>
  )

  return (
    <StudioCanvasPopover
      trigger="click"
      arrow={false}
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) {
          setPrompt(node.data.prompt || '')
          setView('run')
        }
      }}
      nodePlacement
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-runtime-workspace-popover' }}
      styles={{ container: { padding: 0, background: 'transparent' }, content: { padding: 0 } }}
      content={workspace}
    >
      <StudioComposerChip icon={<AppstoreOutlined />} label="能力" arrow active={open} />
    </StudioCanvasPopover>
  )
}
