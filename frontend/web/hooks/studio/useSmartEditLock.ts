'use client'

import { useEffect, useState } from 'react'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import type { CanvasNodeEditLease } from '@/utils/canvas/canvasEditLeaseManager'
export { shouldLockSmartEdit } from '@/utils/studio/smartEditLock'

export function useSmartEditLock(nodeId: string, enabled: boolean) {
  const runtime = useCanvasProjectRuntime()
  const acquire = runtime?.acquireNodeEditLease
  const available = !runtime || runtime.canMutate
  const workspaceId = runtime?.project?.workspace.workspaceId
  const [state, setState] = useState({ key: '', ready: false, error: '' })
  const key = `${workspaceId || 'local'}:${nodeId}`
  useEffect(() => {
    if (!enabled || !available) return
    if (!acquire) return
    let active = true, renewing = false
    let lease: CanvasNodeEditLease | null = null
    let interval: ReturnType<typeof setInterval> | undefined
    // 外部租约会在断线后失效，重连必须先禁用写入，不能沿用上一租约的 ready。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState({ key, ready: false, error: '' })
    void acquire(nodeId).then((result) => {
      if (!active) { void result?.release().catch(() => undefined); return }
      lease = result
      setState({ key, ready: true, error: '' })
      if (lease) interval = setInterval(() => {
        if (renewing || !lease) return
        renewing = true
        void lease.renew().catch(() => {
          if (active) setState({ key, ready: false, error: '编辑租约已失效，请关闭后重新打开精修台' })
          clearInterval(interval)
        }).finally(() => { renewing = false })
      }, 5000)
    }).catch((error: unknown) => { if (active) setState({ key, ready: false, error: error instanceof Error ? error.message : '未取得节点编辑租约' }) })
    return () => { active = false; clearInterval(interval); void lease?.release().catch(() => undefined) }
  }, [acquire, available, enabled, key, nodeId])
  const lock = runtime?.collaboration?.locks.find((item) => String(item.objectId) === nodeId)
  return {
    readOnly: !available || (Boolean(acquire) && (state.key !== key || !state.ready)),
    message: !available ? '只读预览：当前画布不可提交修改' : !acquire ? '' : state.key === key && state.error ? `${lock?.nickName ? `${lock.nickName} 正在剪辑。` : ''}${state.error}` : state.key !== key || !state.ready ? '正在确认编辑权限…' : ''
  }
}
