import type { AssistantAdoptionAdapter, AssistantAdoptionReceipt, AssistantAdoptionTarget } from '@/types/assistant-artifact'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasAssistantSelectionFingerprint, resolveCanvasAssistantContext } from './canvasAssistantContext'

/** 只写入已选择的真实文本目标。协作session/锁来自既有runtime，不冒充Agent运行租约。 */
export function createCanvasAssistantAdoption(getRuntime: () => CanvasProjectWorkspaceRuntime | null, scopeKey: string,
  onReceipt: (receipt: AssistantAdoptionReceipt) => void): AssistantAdoptionAdapter {
  const supports = (target: AssistantAdoptionTarget) => {
    const runtime = getRuntime(), state = useStudioUiStore.getState()
    return target.targetKind === 'FREE_CANVAS' && target.requiredContext.runtimeLeaseKind === 'CANVAS_EDIT_LOCK'
      && state.scopeKey === scopeKey && String(target.requiredContext.workspaceId) === String(runtime?.project?.workspace.workspaceId)
      && state.chatContextNodeIds.length === 1 && state.chatContextNodeIds[0] === target.targetObjectId
      && state.nodes.some((node) => node.id === target.targetObjectId && node.data.kind === 'text' && !node.data.canvasTransient && !node.data.flowBinding)
  }
  return {
    supports,
    capabilityContext: () => {
      const state = useStudioUiStore.getState(), runtime = getRuntime()
      const id = state.scopeKey === scopeKey && state.chatContextNodeIds.length === 1 ? state.chatContextNodeIds[0] : undefined
      return { targetKind: 'FREE_CANVAS', workspaceId: runtime?.project?.workspace.workspaceId ? String(runtime.project.workspace.workspaceId) : undefined, targetObjectId: id }
    },
    prepare: async (target) => {
      const runtime = getRuntime()
      if (!runtime || !supports(target)) throw new Error('请选中一个可编辑的文本节点作为采用目标')
      const id = target.targetObjectId, fingerprint = canvasAssistantSelectionFingerprint([id])
      await resolveCanvasAssistantContext(runtime, scopeKey, [id])
      const held = await runtime.acquireNodeMutationLease(id)
      let timer: ReturnType<typeof setInterval> | undefined
      let leaseError = false, renewing = false, released = false
      const release = async () => { if (released) return; released = true; clearInterval(timer); await held.lease.release() }
      try {
        const token = held.lease.locks.find((lock) => lock.objectId === id)?.lockToken
        if (!token) throw new Error('未取得采用目标的编辑租约')
        const dirtyRevision = useStudioUiStore.getState().dirtyRevision
        const assertUnchanged = () => {
          const current = getRuntime()
          if (released || leaseError || !supports(target) || !current?.canMutate || current.isNodeBusy(id)
            || useStudioUiStore.getState().dirtyRevision !== dirtyRevision
            || current.getNodeVersion(id) !== held.objectVersion || canvasAssistantSelectionFingerprint([id]) !== fingerprint) throw new Error('目标节点、选区或编辑租约已变化，请重新检查后采用')
        }
        assertUnchanged()
        timer = setInterval(() => { if (renewing) return; renewing = true; void held.lease.renew().catch(() => { leaseError = true; clearInterval(timer) }).finally(() => { renewing = false }) }, 5000)
        return { context: { workspaceId: String(runtime.project!.workspace.workspaceId), editSessionId: held.editSessionId, runtimeLeaseId: token },
          targetRevision: String(held.objectVersion), assertUnchanged, release }
      } catch (cause) { await release().catch(() => undefined); throw cause }
    },
    applied: async (result, command) => {
      const runtime = getRuntime(), state = useStudioUiStore.getState(), receipt = result.receipt
      if (result.status !== 'SUCCEEDED' || result.targetKind !== 'FREE_CANVAS' || state.scopeKey !== scopeKey
        || String(receipt.workspaceId) !== String(runtime?.project?.workspace.workspaceId)) return '采用已保存，当前画布已切换；请从目标画布查看。'
      if (result.targetObjectId !== command.targetObjectId || receipt.objectId !== command.targetObjectId
        || receipt.artifactId !== command.artifactId || receipt.contentHash !== command.contentHash
        || receipt.previousVersion !== command.expectedTargetRevision || receipt.newVersion !== result.targetRevision) throw new Error('采用回执与目标不一致，请核对服务器状态')
      onReceipt(result)
      return state.dirtyRevision > 0 ? '采用已保存；当前手改已保留，保存并核对后再刷新画布。' : '采用已保存，正在读取画布最新版本。'
    }
  }
}
