import type { PrevizCommand, PrevizScene } from '@/types/previz'
import { cameraChoices, PREVIZ_DIRECTOR_VIEW_ID } from './nativeCameras'

export function generatedCameraChoices(scene: PrevizScene) {
  return cameraChoices(scene).filter(camera => camera.id !== PREVIZ_DIRECTOR_VIEW_ID && !scene.directorLayer?.bindings[camera.id])
}

/** Bind only the untouched first-shot placeholder. Authored cameras/edits keep their identities and cuts. */
export function initialGeneratedCameraShot(before: PrevizScene, adopted: PrevizScene, assetId: string): PrevizCommand | null {
  if (before.shots.length !== 1 || before.shots[0].cameraId !== PREVIZ_DIRECTOR_VIEW_ID
    || cameraChoices(before).some(camera => camera.id !== PREVIZ_DIRECTOR_VIEW_ID)) return null
  const owners = new Set(adopted.nodes.filter(node => node.assetId === assetId).map(node => node.id))
  const camera = generatedCameraChoices(adopted).find(camera => camera.sourceNodeId && owners.has(camera.sourceNodeId))
  if (!camera) return null
  return { type: 'timeline.replace', timeline: adopted.timeline, shots: adopted.shots.map(shot => ({ ...shot, cameraId: camera.id })) }
}
