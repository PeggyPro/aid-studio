'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Modal } from 'antd'
import type { AssistantAdoptionAdapter, AssistantAdoptionTarget, AssistantArtifactDetail } from '~/types/assistant-artifact'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantCommandId, assistantDigest, assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'

const actionLabel = (target: AssistantAdoptionTarget) => target.displayName.startsWith('采用') ? target.displayName : '采用到' + target.displayName

export function AssistantArtifactAdoption({ artifact, store, adapter, complete }: {
  artifact: AssistantArtifactDetail; store: AssistantSessionStore; adapter: AssistantAdoptionAdapter; complete: boolean
}) {
  const [targets, setTargets] = useState<AssistantAdoptionTarget[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const alive = useRef(true)
  const locked = useRef(false)
  const releaseLease = useRef<(() => Promise<void>) | undefined>(undefined)
  const release = useCallback(async () => { const fn = releaseLease.current; releaseLease.current = undefined; await fn?.().catch(() => undefined) }, [])
  const sessionId = store.getSnapshot().snapshot?.sessionId
  const contextKey = assistantDigest(adapter.capabilityContext?.() || {})
  useEffect(() => {
    alive.current = true
    if (!sessionId) return
    let active = true
    void store.api.adoptionCapabilities(sessionId, artifact.artifactId, JSON.parse(contextKey)).then((value) => {
      if (!active) return
      if (value.artifactId !== artifact.artifactId) throw new Error('采用产物不一致，请重新读取')
      setTargets(value.targets); setError(null)
    }).catch((cause) => { if (active) setError(assistantErrorMessage(cause)) })
    return () => { active = false; alive.current = false; void release() }
  }, [store, sessionId, artifact.artifactId, reload, adapter, release, contextKey])
  const begin = async (target: AssistantAdoptionTarget) => {
    if (!sessionId || locked.current || !complete || !store.getSnapshot().writable || store.getSnapshot().busy || store.getSnapshot().pendingCommand) return
    locked.current = true; setBusy(true); setError(null)
    try {
      const prepared = await adapter.prepare(target)
      if (!alive.current) { await prepared.release?.(); return }
      releaseLease.current = prepared.release
      const fresh = await store.api.adoptionCapabilities(sessionId, artifact.artifactId, adapter.capabilityContext?.())
      if (!alive.current || store.getSnapshot().snapshot?.sessionId !== sessionId) { await release(); return }
      prepared.assertUnchanged()
      const current = fresh.targets.find((item) => item.targetKind === target.targetKind && item.targetObjectId === target.targetObjectId)
      if (fresh.artifactId !== artifact.artifactId || !current?.allowed || !current.currentTargetRevision || !adapter.supports(current)) throw new Error('当前目标已不可采用，请重新读取采用范围')
      if (prepared.targetRevision && current.currentTargetRevision !== prepared.targetRevision) throw new Error('服务器目标已变化，请先核对最新版本')
      setTargets(fresh.targets)
      Modal.confirm({ title: actionLabel(current) + '？', content: '这会将此完整版本保存为目标的新版本。确认时再次核对编辑状态；已有内容请先检查。',
        okText: '确认采用', cancelText: '取消', onCancel: release, onOk: async () => {
          try {
          if (!alive.current || store.getSnapshot().snapshot?.sessionId !== sessionId) throw new Error('会话已切换，请重新操作')
          prepared.assertUnchanged()
          const accepted = await store.execute('adopt', { ...prepared.context, sessionId, artifactId: artifact.artifactId,
            contentHash: artifact.contentHash, targetKind: current.targetKind, targetObjectId: current.targetObjectId,
            expectedTargetRevision: current.currentTargetRevision, clientRequestId: assistantCommandId() })
          if (!accepted && alive.current) setError(store.getSnapshot().error || '采用尚未确认，请核实原操作')
          if (alive.current) setReload((value) => value + 1)
          } catch (cause) { if (alive.current) setError(assistantErrorMessage(cause)) }
          finally { await release() }
        } })
    } catch (cause) { await release(); if (alive.current) setError(assistantErrorMessage(cause)) }
    finally { locked.current = false; if (alive.current) setBusy(false) }
  }
  const pending = store.getSnapshot().pendingCommand
  const available = targets.filter((target) => adapter.supports(target))
  if (!available.length && !error && pending?.action !== 'adopt') return null
  return <section className="flex flex-col gap-2" aria-label="采用产物">
    <h3 className="text-sm font-medium">采用到编辑器</h3>
    {available.map((target) => <Button key={target.targetKind + ':' + target.targetObjectId} disabled={!target.allowed || !complete || busy || !store.getSnapshot().writable || store.getSnapshot().busy || !!pending} loading={busy} onClick={() => void begin(target)}>{actionLabel(target)}</Button>)}
    {available.some((target) => !target.allowed) ? <p className="text-xs">{available.some((target) => target.reasonCode === 'SCRIPT_CONTENT_TOO_LARGE') ? '此版本超过单剧本字数上限，请先生成或选择单集剧本版本。' : '当前目标暂不可采用，请检查产物与目标状态。'}</p> : null}
    {!complete && available.length ? <p className="text-xs">完整读取并校验正文后可采用此版本。</p> : null}
    {pending?.action === 'adopt' ? <><p className="text-xs">采用结果待核实，核实会使用原操作标识。</p><Button disabled={!store.getSnapshot().writable || store.getSnapshot().busy} onClick={() => void store.retryPending()}>核实采用结果</Button></> : null}
    {error ? <p role="alert" className="text-xs">{error}</p> : null}
  </section>
}
