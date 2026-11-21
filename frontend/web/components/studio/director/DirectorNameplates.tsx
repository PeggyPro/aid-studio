'use client'

import { directorActorHeight } from '@/utils/studio/directorSceneUnits'
import { useEffect, useRef, type RefObject } from 'react'
import { Vector3 } from 'three'
import type { DirectorSceneData } from '@/types/studio'
import type { DirectorViewportHandle } from './DirectorViewport'

export function DirectorNameplates({
  viewport,
  scene,
}: {
  viewport: RefObject<DirectorViewportHandle | null>
  scene: DirectorSceneData
}) {
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let frame = 0
    const tick = () => {
      const runtime = viewport.current?.getRuntime(),
        root = host.current
      if (runtime && root)
        for (const label of root.children) {
          const el = label as HTMLElement
          const object = runtime.objectRoot.children.find((o) => o.userData.objectId === el.dataset.object)
          const actor = scene.objects.find((o) => o.id === el.dataset.object)
          if (!object || !actor) continue
          const p = object.position
            .clone()
            .add(new Vector3(0, directorActorHeight(actor) + 0.2, 0))
            .project(runtime.activeCamera())
          el.style.display = p.z >= -1 && p.z <= 1 ? '' : 'none'
          el.style.left = `${(p.x + 1) * 50}%`
          el.style.top = `${(1 - p.y) * 50}%`
        }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [viewport, scene.objects])
  return (
    <div ref={host} className="pointer-events-none absolute inset-0 overflow-hidden">
      {scene.objects
        .filter((o) => o.kind === 'actor' && o.visible !== false)
        .map((o) => (
          <span
            key={o.id}
            data-object={o.id}
            className="absolute rounded bg-black/70 px-2 py-1 text-xs text-white"
            style={{ borderLeft: `3px solid ${o.actorMeta?.identifyColor ?? o.color}` }}
          >
            {o.name}
          </span>
        ))}
    </div>
  )
}
