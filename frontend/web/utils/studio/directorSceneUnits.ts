import type { DirectorObject, DirectorPropShape } from '@/types/studio'
import { PREVIZ_BOX_SIZE, PREVIZ_CONE_HEIGHT, PREVIZ_SPHERE_RADIUS, PREVIZ_HUMAN_HEIGHT_METERS } from '@/utils/previz/sceneUnits'

export const DIRECTOR_PROP_HEIGHT: Record<DirectorPropShape, number> = {
  box: PREVIZ_BOX_SIZE,
  sphere: PREVIZ_SPHERE_RADIUS * 2,
  cylinder: 1.2,
  cone: PREVIZ_CONE_HEIGHT,
  pyramid: 1.1,
  torus: 0.28,
  capsule: 1.59
}

/** The rig's world scale is the single size multiplier for both geometry and aim. */
export function directorActorHeight(object: Pick<DirectorObject, 'transform'>): number {
  return PREVIZ_HUMAN_HEIGHT_METERS * Math.max(0.01, Math.abs(object.transform.scale[1]))
}
