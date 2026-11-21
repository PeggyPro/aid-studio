'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { UserModelListItem } from '@/types/business-api'
import { canvasUserModelList } from '@/utils/business/model'
import { coordinateAssistantRequest } from '@/utils/assistant/assistantRequestCoordinator'
import { useCanvasGatewayIdentity } from './useCanvasGatewayIdentity'

/** 按身份与类型读取目录；入口可预取，请求与刷新复用同一去重器。 */
export function useCanvasAssistantModels(kind: 'image' | 'video', enabled = true) {
  const { identity, connected } = useCanvasGatewayIdentity()
  const key = `assistant-model-picker:${identity}:${kind}`
  const [revision, setRevision] = useState(0)
  const forced = useRef<string | null>(null)
  const [state, setState] = useState<{ key: string; data: UserModelListItem[]; loading: boolean; error: string }>({ key: '', data: [], loading: false, error: '' })
  useEffect(() => {
    if (!enabled || !connected) return
    let active = true
    const force = forced.current === key
    forced.current = null
    void Promise.resolve().then(() => {
      if (!active) return
      setState(current => ({ key, data: current.key === key ? current.data : [], loading: true, error: '' }))
      return coordinateAssistantRequest({ key, body: { modelType: kind }, cacheMs: 30000, force,
        execute: () => canvasUserModelList({ modelType: kind }, force) }).then(data => {
        if (active) setState({ key, data, loading: false, error: '' })
      }, () => { if (active) setState({ key, data: [], loading: false, error: '模型目录读取失败，请重新读取' }) })
    })
    return () => { active = false }
  }, [key, kind, enabled, connected, revision])
  const reload = useCallback(() => { forced.current = key; setRevision(value => value + 1) }, [key])
  if (!connected) return { data: [], loading: false, error: '', reload }
  return { ...(state.key === key ? state : { data: [], loading: enabled, error: '' }), reload }
}
