'use client'
import { useEffect, useState, type RefObject } from 'react'
import type { Group } from 'three'
import { App } from 'antd'
import type { DirectorSceneData } from '@/types/studio'
import type { DirectorViewportHandle } from './DirectorViewport'
import { loadDirectorPackage, findDirectorPart, cloneDirectorScenery } from './directorScenePackageLoader'
import { directorScenePackages } from '@/utils/studio/directorScenePackages'
import { disposeDirectorObjectTree } from './directorRuntimeResources'

export function DirectorSceneryRuntime({
  viewport,
  scene,
  ready,
  selectedId,
  onError,
}: {
  viewport: RefObject<DirectorViewportHandle | null>
  scene: DirectorSceneData
  ready: boolean
  selectedId: string | null
  onError?: (error: Error) => void
}) {
  return <>{directorScenePackages(scene).map(pkg => <SceneryPackage key={pkg.mediaId} viewport={viewport} scene={scene} ready={ready} selectedId={selectedId} mediaId={pkg.mediaId} url={pkg.url} bundle={pkg.bundle} onError={onError} />)}</>
}
function SceneryPackage({ viewport, scene, ready, selectedId, mediaId, url, bundle, onError }: {
  viewport: RefObject<DirectorViewportHandle | null>; scene: DirectorSceneData; ready: boolean; selectedId: string | null; mediaId: string; url: string; bundle?: import('@/types/canvas-api').CanvasModelBundle; onError?: (error: Error) => void
}) {
  const { message } = App.useApp()
  const [loaded, setLoaded] = useState<{ url: string; root: Group }>()
  useEffect(() => {
    if (!url || !ready) return
    let disposed = false
    let source: Group | undefined
    void loadDirectorPackage(url, bundle)
      .then((root) => {
        source = root
        if (disposed) disposeDirectorObjectTree(root)
        else setLoaded({ url, root })
      })
      .catch((error) => {
        if (!disposed) { message.error(error instanceof Error ? error.message : '场景包加载失败'); onError?.(error instanceof Error ? error : new Error('场景包加载失败')) }
      })
    return () => {
      disposed = true
      if (source) disposeDirectorObjectTree(source)
    }
  }, [url, ready, bundle, onError, message])
  useEffect(() => {
    const runtime = viewport.current?.getRuntime()
    if (!runtime || !ready || !loaded || loaded.url !== url) return
    const groups: ReturnType<typeof cloneDirectorScenery>[] = []
    for (const object of scene.objects.filter((o) => o.kind === 'scenery' && o.visible !== false && o.scenery?.scenePackageMediaId === mediaId)) {
      const part = findDirectorPart(loaded.root, object.scenery!.meshPath)
      if (!part) { onError?.(new Error(`场景包缺少部件：${object.scenery!.meshPath}`)); continue }
      const group = cloneDirectorScenery(part, object, scene.viewportPrefs?.shading === 'xray')
      group.userData.directorScenery = true
      groups.push(group)
      runtime.objectRoot.add(group)
      if (object.id === selectedId && !object.locked) runtime.transformControls.attach(group)
    }
    runtime.render()
    return () => {
      groups.forEach((g) => {
        if (runtime.transformControls.object === g) runtime.transformControls.detach()
        runtime.objectRoot.remove(g)
        disposeDirectorObjectTree(g)
      })
    }
  }, [viewport, loaded, url, mediaId, scene.objects, scene.viewportPrefs?.shading, ready, selectedId, onError])
  return null
}
