'use client'

import { useRef, useState } from 'react'
import type { DirectorSceneData } from '@/types/studio'
import type { PrevizProject } from '@/types/previz'
import type { CanvasSceneAdoption } from '@/types/canvas-api'
import type { PrevizEditorSession } from '@/components/previz/PrevizEditor'
import { applyCommands, uid } from '@/utils/previz/core'
import { applyThreePrevizProject, projectThreePreviz } from '@/utils/studio/threePrevizDocument'
import { sameThreePrevizRenderSource } from '@/utils/studio/threePrevizRenderSource'

/** Both editing surfaces share the host's full-document undo/redo and durable save. */
export function useThreePrevizSession(options: {
  scene: DirectorSceneData
  change: (scene: DirectorSceneData) => DirectorSceneData
  replace: (scene: DirectorSceneData) => DirectorSceneData
  undo: () => void
  redo: () => void
  canUndo: boolean
  canRedo: boolean
  save: (adoption?: Omit<CanvasSceneAdoption, 'sceneId'>) => Promise<void>
  cloud: boolean
}): PrevizEditorSession {
  const handlers = useRef(options)
  handlers.current = options
  const source = useRef(options.scene)
  const ref = useRef<PrevizProject>(null!)
  if (!ref.current || source.current !== options.scene) {
    const previous = source.current
    const metadataOnly = ref.current && sameThreePrevizRenderSource(previous, options.scene)
    source.current = options.scene
    ref.current = metadataOnly ? { ...ref.current, ...options.scene.modeling?.project, title: options.scene.name, scene: ref.current.scene } : projectThreePreviz(options.scene)
  }
  const generation = useRef(0)
  const [error, setError] = useState('')
  const [sessionId] = useState(uid)
  const update = (next: PrevizProject, history: boolean, base = handlers.current.scene) => {
    if (!next.scene.directorLayer && ref.current.scene.directorLayer) {
      const layer = ref.current.scene.directorLayer
      const ids = new Set(next.scene.nodes.map(node => node.id))
      next = { ...next, scene: { ...next.scene, directorLayer: layer, nodes: [...next.scene.nodes, ...ref.current.scene.nodes.filter(node => layer.bindings[node.id] && !ids.has(node.id))] } }
    }
    const canonical = applyThreePrevizProject(base, next)
    source.current = history ? handlers.current.change(canonical) : handlers.current.replace(canonical)
    if (source.current === handlers.current.scene) return
    ref.current = next
    generation.current++
    // Synchronous writes in one event must read the document just produced.
    handlers.current = { ...handlers.current, scene: source.current }
  }
  return {
    project: ref.current,
    ref,
    generation,
    mutate: updater => update(updater(ref.current), false),
    execute: (commands, patch = {}) => {
      const replacement = [...commands].reverse().find(command => command.type === 'scene.replace')
      const original = handlers.current.scene
      const historical = replacement?.type === 'scene.replace' ? replacement.scene.directorLayer?.scene : undefined
      update({ ...ref.current, ...patch, scene: applyCommands(ref.current.scene, commands) }, true, historical ? { ...historical, id: original.id, modeling: original.modeling } : original)
    },
    replaceWorkspace: patch => update({ ...ref.current, ...patch }, true),
    undo: options.undo,
    redo: options.redo,
    canUndo: options.canUndo,
    canRedo: options.canRedo,
    flush: async adoption => { await handlers.current.save(adoption) },
    canvasManaged: options.cloud,
    saveState: options.cloud ? '保存到当前画布' : '完整工程保存在本机',
    error,
    setError,
    conflict: false,
    recoveryKey: `three-previz:${options.scene.id}`,
    discardRecovery: async () => undefined,
    sessionId,
  }
}
