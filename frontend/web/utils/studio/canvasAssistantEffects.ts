import type { AssistantTargetRef } from '@/types/assistant-effects'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { useStudioUiStore } from '@/stores/studioUi'

/** 只消费 effect/resolve 刚鉴权的当前身份；聚焦不写节点，不转换版本。 */
export function createCanvasAssistantEffectLocator(getRuntime: () => CanvasProjectWorkspaceRuntime | null,
  scopeKey: string, isCurrent: () => boolean) {
  let refreshInFlight: Promise<void> | null = null
  const inFlight = new Map<string, Promise<void>>()
  const locate = async (ref: AssistantTargetRef, isRequestCurrent: () => boolean): Promise<void> => {
    const requireScope = () => {
      const runtime = getRuntime(), state = useStudioUiStore.getState()
      if (!isCurrent() || !isRequestCurrent() || !runtime || ref.kind !== 'FREE_CANVAS' || state.scopeKey !== scopeKey
        || ref.projectId !== String(runtime.project?.projectId) || ref.workspaceId !== String(runtime.project?.workspace.workspaceId)
        || typeof ref.version !== 'string' || !ref.version.trim()) throw new Error('关联对象不属于当前画布或登录状态已变化')
      return { runtime, state }
    }
    let { runtime, state } = requireScope()
    if (ref.kind !== 'FREE_CANVAS') return
    if (!state.nodes.some((node) => node.id === ref.objectId)) {
      if (state.dirtyRevision > 0) throw new Error('画布有未保存修改，请先保存再定位关联对象')
      const selection = JSON.stringify(state.selectedNodeIds)
      if (!refreshInFlight) refreshInFlight = runtime.refreshWorkspace().finally(() => { refreshInFlight = null })
      await refreshInFlight
      ;({ runtime, state } = requireScope())
      if (state.dirtyRevision > 0 || JSON.stringify(state.selectedNodeIds) !== selection) throw new Error('读取期间画布或选区已修改，已保留当前内容，请重新定位')
    }
    if (!state.nodes.some((node) => node.id === ref.objectId)) throw new Error('关联节点已删除或尚未同步，请稍后重试')
    state.selectNodes([ref.objectId])
    window.requestAnimationFrame(() => {
      try {
        const current = requireScope().state
        if (current.nodes.some((node) => node.id === ref.objectId) && current.selectedNodeIds.includes(ref.objectId)) window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: ref.objectId } }))
      } catch { /* 切画布/身份后的迟到聚焦作废。 */ }
    })
  }
  return (ref: AssistantTargetRef, isRequestCurrent = () => true): Promise<void> => {
    const key = JSON.stringify(ref), existing = inFlight.get(key)
    if (existing) return existing
    const pending = locate(ref, isRequestCurrent).finally(() => { if (inFlight.get(key) === pending) inFlight.delete(key) })
    inFlight.set(key, pending)
    return pending
  }
}
