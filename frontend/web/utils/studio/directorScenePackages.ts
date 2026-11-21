import type { DirectorSceneData, DirectorScenePackageRef } from '@/types/studio'

export function directorScenePackages(scene: DirectorSceneData): DirectorScenePackageRef[] {
  return [...new Map([...(scene.scenePackages ?? []), ...(scene.scenePackage ? [scene.scenePackage] : [])].map(pkg => [pkg.mediaId, pkg])).values()]
}
