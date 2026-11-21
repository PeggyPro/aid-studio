import type { DirectorSceneData } from '@/types/studio'
import { cameraChoices, PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { shotAt } from '@/utils/previz/core'
import { listDirectorAuthoredCameras } from '@/components/studio/director/directorCameraState'

/** One resolver for generated camera preview and both video export paths. */
export function directorModelingCameraResolver(scene: DirectorSceneData, view?: string | null): (time: number) => string | undefined {
  const graph = scene.modeling?.project.scene
  if (view && view !== '@sequence') return () => view
  if (!graph) return () => undefined
  if (view === '@sequence') return time => shotAt(graph, Math.min(graph.timeline.frameCount - 1, Math.max(0, Math.round(time * 30)))).cameraId
  if (listDirectorAuthoredCameras(scene).length) return () => undefined
  const cameras = cameraChoices(graph).filter(camera => camera.id !== PREVIZ_DIRECTOR_VIEW_ID)
  if (!cameras.length) return () => undefined
  return time => {
    const shot = shotAt(graph, Math.min(graph.timeline.frameCount - 1, Math.max(0, Math.round(time * 30))))
    return cameras.some(camera => camera.id === shot.cameraId) ? shot.cameraId : cameras[0].id
  }
}
