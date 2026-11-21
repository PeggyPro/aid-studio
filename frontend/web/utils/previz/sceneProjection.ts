import type { PrevizScene } from '@/types/previz'
import type { DirectorCamera } from '@/types/studio'
import { createNode } from './core'
import { PREVIZ_DIRECTOR_VIEW_ID } from './nativeCameras'

/** Standalone graphs require a camera record for every shot, including the canvas observer. */
export function materializePrevizDirectorView(scene: PrevizScene, observer?: DirectorCamera): PrevizScene {
  if (!observer || !scene.shots.some(shot => shot.cameraId === PREVIZ_DIRECTOR_VIEW_ID) ||
      scene.nodes.some(node => node.id === PREVIZ_DIRECTOR_VIEW_ID)) return scene
  const node = createNode('CAMERA', '导演视角')
  node.id = PREVIZ_DIRECTOR_VIEW_ID
  node.visible = false
  node.locked = true
  node.localTransform.position = [...observer.position]
  node.camera = { ...node.camera!, target: [...observer.target], fov: observer.fov, roll: (observer.roll ?? 0) * Math.PI / 180 }
  return { ...scene, nodes: [...scene.nodes, node] }
}

/** Existing PREVIZ contracts receive the observed graph, never canvas runtime components. */
export function previzNetworkScene(scene: PrevizScene): PrevizScene {
  const director = scene.directorLayer?.scene
  const { directorLayer: _runtime, ...persistent } = materializePrevizDirectorView(scene, director?.directorCamera ?? director?.camera)
  return { ...persistent, nodes: persistent.nodes.map(node => { const { directorBinding: _binding, ...value } = node; return value }) }
}
