'use client'

import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'

export function DirectorNodeEntry({ nodeId, title }: { nodeId: string; title: string }) {
  const openDirector = useStudioUiStore(state => state.openDirector)
  const runtime = useCanvasProjectRuntime()
  const hasScene = useStudioUiStore(state => {
    const sceneId = state.nodes.find(node => node.id === nodeId)?.data.directorSceneId
    return Boolean(sceneId && Object.hasOwn(state.directorScenes, sceneId))
  })
  const missing = Boolean(runtime?.project) && !hasScene
  return <button type="button" className="studio-node-card__director-open nodrag" data-scene-missing={missing || undefined}
    aria-label={missing ? '查看3D预演恢复状态' : title && title !== '3D预演' ? `打开3D预演：${title}` : '打开3D预演'}
    onClick={event => { event.stopPropagation(); openDirector(nodeId) }}>
    {missing ? '场景待恢复' : '打开3D预演'}
  </button>
}
