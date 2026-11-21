import type { PrevizProject } from './previz'
import type { DirectorSceneData } from './studio'

/** Authored modeling entities live here exactly once. Editor projections are never saved. */
export interface ThreePrevizModeling {
  schemaVersion: 1
  project: PrevizProject
  directorBindings?: ThreePrevizDirectorLayer['bindings']
}

export interface ThreePrevizProjectPackage {
  format: 'AID-3D-PREVIZ'
  packageVersion: 1
  scene: DirectorSceneData
  assetPool?: string[]
}

/** Transient rendering projection; excluded from PREVIZ network documents and local project slices. */
export interface ThreePrevizDirectorLayer {
  scene: DirectorSceneData
  bindings: Record<string, { kind: 'object' | 'camera' | 'light'; id: string }>
}
