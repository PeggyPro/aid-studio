'use client'

import { useEffect, useRef, type RefObject } from 'react'
import * as THREE from 'three'
import type { DirectorSceneData } from '@/types/studio'
import { buildRuntime, type PrevizRuntime } from '@/utils/previz/runtime'
import { projectThreePreviz } from '@/utils/studio/threePrevizDocument'
import { sameThreePrevizRenderSource } from '@/utils/studio/threePrevizRenderSource'
import { PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import type { DirectorViewportHandle, DirectorRuntime } from './DirectorViewport'

/** Mount native modeling entities into the same Three scene used by capture and recording.
 * Director entities are borrowed by identity; they are neither copied nor disposed here.
 */
export function DirectorModelingRuntime({ viewport, scene, ready, onReady, onError }: {
  viewport: RefObject<DirectorViewportHandle | null>
  scene: DirectorSceneData
  ready: boolean
  onReady?: (ready: boolean) => void
  onError?: (error: Error) => void
}) {
  const latest = useRef({ scene, onReady, onError })
  latest.current = { scene, onReady, onError }
  const native = useRef<PrevizRuntime | null>(null)
  const host = useRef<DirectorRuntime | null>(null)
  const initialized = useRef(false)
  const external = useRef(new Map<string, THREE.Object3D>())
  const syncing = useRef(false)
  const projection = useRef<{ source: DirectorSceneData; project: ReturnType<typeof projectThreePreviz> } | null>(null)
  const appliedProjection = useRef<ReturnType<typeof projectThreePreviz> | null>(null)
  const registerModeling = (runtime: DirectorRuntime, modeling: PrevizRuntime) => {
    const owned = latest.current.scene.modeling?.project.scene.nodes ?? []
    runtime.modelingObjects = new Map(owned.flatMap(node => {
      const object = modeling.objects.get(node.id)
      return object ? [[node.id, object] as const] : []
    }))
    runtime.modelingCameras = modeling.cameras
    viewport.current?.syncSelection()
  }
  const refreshBindings = (runtime: DirectorRuntime) => {
    if (!projection.current || !sameThreePrevizRenderSource(projection.current.source, latest.current.scene)) projection.current = { source: latest.current.scene, project: projectThreePreviz(latest.current.scene) }
    const projected = projection.current.project
    const bindings = projected.scene.directorLayer!.bindings
    const objects = new Map(runtime.objectRoot.children.map(child => [child.userData.objectId, child]))
    const lights = new Map(runtime.lightRoot.children.map(child => [child.userData.lightId, child]))
    let changed = false
    for (const [id, binding] of Object.entries(bindings)) {
      let object: THREE.Object3D | undefined
      if (binding.kind === 'object') object = objects.get(binding.id)
      if (binding.kind === 'light') {
        const root = lights.get(binding.id)
        root?.traverse(child => { if (child instanceof THREE.Light) object = child })
      }
      if (binding.kind === 'camera') continue
      object ??= external.current.get(id) ?? new THREE.Group()
      if (external.current.get(id) !== object) { external.current.set(id, object); changed = true }
    }
    for (const id of external.current.keys()) if (!bindings[id]) { external.current.delete(id); changed = true }
    return { projected, changed }
  }
  useEffect(() => {
    if (!ready) return
    if (!latest.current.scene.modeling) { latest.current.onReady?.(true); return }
    const runtime = viewport.current?.getRuntime()
    if (!runtime) return
    let disposed = false
    host.current = runtime
    latest.current.onReady?.(false)
    const { projected } = refreshBindings(runtime)
    let lastBindingCheck = 0
    const frame = (time: number) => {
      if (!native.current || !initialized.current) return
      // The observation camera follows live orbit controls without rebuilding the animation graph.
      const observer = native.current.cameras.get(PREVIZ_DIRECTOR_VIEW_ID)
      const view = latest.current.scene.directorCamera ?? latest.current.scene.camera
      if (observer instanceof THREE.PerspectiveCamera) {
        observer.position.fromArray(view.position); observer.lookAt(...view.target)
        observer.rotateZ((view.roll ?? 0) * Math.PI / 180)
        observer.fov = view.fov; observer.updateProjectionMatrix(); observer.updateMatrixWorld()
      }
      native.current.frame(Math.round(time * 30))
      // Model loads may replace a pending mannequin after the host's first ready frame.
      const now = performance.now()
      if (now - lastBindingCheck < 120) return
      lastBindingCheck = now
      const current = refreshBindings(runtime)
      if (current.changed && !syncing.current) {
        syncing.current = true
        void native.current.sync(current.projected.scene).catch(error => latest.current.onError?.(error instanceof Error ? error : new Error(String(error)))).finally(() => { syncing.current = false })
      }
    }
    runtime.objectRoot.userData.threePrevizFrame = frame
    const update = (time = useDirectorPlaybackStore.getState().playhead) => frame(time)
    runtime.scene.userData.threePrevizFrame = update
    void buildRuntime(projected.scene, { externalObjects: external.current, skipEnvironment: true }).then(async value => {
      if (disposed) { value.dispose(); return }
      native.current = value
      runtime.scene.add(value.scene)
      let pending = refreshBindings(runtime)
      while (true) {
        await value.sync(pending.projected.scene)
        appliedProjection.current = pending.projected
        if (disposed) return
        const next = refreshBindings(runtime)
        if (next.projected === pending.projected && !next.changed) break
        pending = next
      }
      registerModeling(runtime, value)
      initialized.current = true
      update()
      latest.current.onReady?.(true)
      runtime.render()
    }).catch(error => { if (!disposed) latest.current.onError?.(error instanceof Error ? error : new Error(String(error))) })
    return () => {
      disposed = true
      initialized.current = false
      delete runtime.objectRoot.userData.threePrevizFrame
      delete runtime.scene.userData.threePrevizFrame
      runtime.modelingObjects = new Map()
      runtime.modelingCameras = new Map()
      viewport.current?.syncSelection()
      const value = native.current
      native.current = null
      if (value) { runtime.scene.remove(value.scene); value.dispose() }
      host.current = null
      projection.current = null
      appliedProjection.current = null
      syncing.current = false
      external.current.clear()
    }
    // Host runtime identity changes with ready; callbacks and authored data use latest.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewport, ready, !!scene.modeling])
  useEffect(() => {
    const runtime = host.current
    const value = native.current
    if (!runtime || !value || !initialized.current || !scene.modeling) return
    let current = true
    const { projected } = refreshBindings(runtime)
    if (appliedProjection.current === projected) return
    void value.sync(projected.scene).then(() => {
      if (!current) return
      appliedProjection.current = projected
      registerModeling(runtime, value)
      value.frame(Math.round(useDirectorPlaybackStore.getState().playhead * 30))
      runtime.render()
    }).catch(error => { if (current) latest.current.onError?.(error instanceof Error ? error : new Error(String(error))) })
    return () => { current = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scene])
  return null
}
