'use client'

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Alert, Modal, Radio, Spin, message } from 'antd'
import type { DirectorSceneData } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useUserStore } from '@/stores/user'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { usePrevizAssistantIdentity } from '@/components/previz/usePrevizAssistantIdentity'
import { readThreePrevizLocal, threePrevizStorageKey, type ThreePrevizLocalRecord } from '@/utils/studio/threePrevizStorage'
import { resolveThreePrevizRecovery } from '@/utils/studio/threePrevizRecovery'

export interface DirectorLoadedDocument {
  key: string
  owner: string
  revision: number
  canvasBase?: string
  recovered?: DirectorSceneData
}

export function DirectorDocumentLoader({ nodeId, children }: {
  nodeId: string
  children: (document: DirectorLoadedDocument) => ReactNode
}) {
  const runtime = useCanvasProjectRuntime()
  const scope = useStudioUiStore(state => state.scopeKey)
  const hydrated = useStudioUiStore(state => state.hydrated)
  const node = useStudioUiStore(state => state.nodes.find(node => node.id === nodeId))
  const sceneId = node?.data.directorSceneId
  const source = useStudioUiStore(state => sceneId ? state.directorScenes[sceneId] : undefined)
  const userId = useUserStore(state => state.user?.id)
  const identity = usePrevizAssistantIdentity()
  const actor = userId ?? identity.actorId
  const owner = actor ? `user:${String(actor)}` : ''
  const key = owner && scope && sceneId ? threePrevizStorageKey(owner, scope, sceneId) : ''
  const sourceRef = useRef(source)
  sourceRef.current = source
  const [loaded, setLoaded] = useState<({ key: string; record?: ThreePrevizLocalRecord } & Awaited<ReturnType<typeof resolveThreePrevizRecovery>>)>()
  const [choice, setChoice] = useState<'local' | 'canvas'>('local')
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!key) return
    let current = true
    setError('')
    setChoice('local')
    void readThreePrevizLocal(key).then(async record => {
      const decision = await resolveThreePrevizRecovery(record, sourceRef.current)
      if (current) setLoaded({ key, record, ...decision })
    }).catch(error => { if (current) setError(error instanceof Error ? error.message : '3D预演本机恢复失败') })
    return () => { current = false }
  }, [key, retry])
  const close = () => useStudioUiStore.getState().closeDirector()
  const active = loaded?.key === key ? loaded : undefined
  const missing = hydrated && Boolean(node) && (!sceneId || Boolean(runtime?.project && active && !source && !active.recovered))
  useEffect(() => {
    if (!missing) return
    const current = useStudioUiStore.getState()
    if (current.scopeKey !== scope || current.directorOpenNodeId !== nodeId) return
    if (sceneId && current.directorScenes[sceneId]) return
    current.closeDirector()
    void message.warning({ key: 'director-missing-scene', content: '此 3D 预演节点没有可用场景，请删除后重新创建。' })
  }, [missing, nodeId, sceneId, scope])
  if (missing) return null
  if (error || identity.error && !owner) return <div className="director-stage">
    <Modal open centered title="无法读取 3D预演" getContainer={false} onCancel={close} cancelText="返回画布" okText="重新读取"
      onOk={() => { identity.retry(); setRetry(value => value + 1) }}>
      <Alert type="error" showIcon message={error || identity.error} />
    </Modal>
  </div>
  if (!key || !active) return <div className="director-stage director-document-state" role="status"><Spin /><p>正在读取3D预演工程…</p></div>
  if (active.conflict && active.record) return <div className="director-stage">
    <Modal open centered title="选择要继续编辑的场景" width={480} getContainer={false} onCancel={close}
      cancelText="返回画布" okText="继续编辑" onOk={() => {
        const recovered = choice === 'canvas' ? active.canvasWithModeling : active.record?.scene
        if (recovered) setLoaded({ ...active, recovered, conflict: false })
      }}>
      <p className="director-recovery-intro">本机和画布的场景内容不同。本机副本保存于 {active.record.savedAt.replace('T', ' ').slice(0, 19)}。</p>
      <Radio.Group className="director-recovery-options" aria-label="场景恢复来源" value={choice} onChange={event => setChoice(event.target.value)}>
        <Radio value="local"><strong>本机完整工程</strong><small>继续使用本机的场景、建模与分镜。</small></Radio>
        {active.canvasWithModeling ? <Radio value="canvas"><strong>画布场景</strong><small>{source?.modeling ? '使用画布保存的完整场景、建模与分镜。' : '使用画布的场景，同时保留可兼容的本机建模与分镜。'}</small></Radio> : null}
      </Radio.Group>
    </Modal>
  </div>
  return children({ key, owner, revision: active.record?.revision ?? 0, canvasBase: active.canvasBase, recovered: active.recovered })
}
