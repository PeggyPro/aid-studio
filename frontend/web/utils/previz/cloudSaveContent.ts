import type { PrevizProject, PrevizScene } from '@/types/previz'
import { previzNetworkScene } from './sceneProjection'

export type PrevizCloudContent = Pick<PrevizProject, 'scene' | 'title' | 'annotations' | 'thumbnail' | 'adoptedBuildSteps' | 'versions'>

export const previzCloudContent = (project: PrevizProject): PrevizCloudContent => ({
  scene: project.scene, title: project.title, annotations: project.annotations,
  thumbnail: project.thumbnail, adoptedBuildSteps: project.adoptedBuildSteps, versions: project.versions,
})

// Saving the host document may rehydrate equal objects. Reference changes alone
// are not edits. Compare the same projection that is sent to the document API;
// do not stringify embedded GLB/image bytes on every React render.
function equalValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, index) => equalValue(value, b[index]))
  }
  const left = a as Record<string, unknown>, right = b as Record<string, unknown>
  const keys = Object.keys(left).filter(key => left[key] !== undefined)
  return keys.length === Object.keys(right).filter(key => right[key] !== undefined).length
    && keys.every(key => equalValue(left[key], right[key]))
}

const sameScene = (a: PrevizScene, b: PrevizScene) => a === b || equalValue(previzNetworkScene(a), previzNetworkScene(b))

export function samePrevizCloudContent(a: PrevizCloudContent, b: PrevizCloudContent): boolean {
  return a.title === b.title && a.thumbnail === b.thumbnail && sameScene(a.scene, b.scene)
    && equalValue(a.annotations, b.annotations) && equalValue(a.adoptedBuildSteps ?? [], b.adoptedBuildSteps ?? [])
    && (a.versions === b.versions || a.versions.length === b.versions.length && a.versions.every((version, index) => {
      const { scene, ...metadata } = version
      const { scene: other, ...otherMetadata } = b.versions[index]
      return equalValue(metadata, otherMetadata) && sameScene(scene, other)
    }))
}
