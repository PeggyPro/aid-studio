import type { DirectorCamera } from '@/types/studio'
import { DEFAULT_DIRECTOR_OBSERVER_CAMERA } from '@/utils/studio/directorCameraPose'

/** storyai-3d-director-desk 导演台视口色调与默认值。 */
export const DIRECTOR_VIEWPORT_DEFAULT_CAMERA: DirectorCamera = {
  ...DEFAULT_DIRECTOR_OBSERVER_CAMERA,
  position: [...DEFAULT_DIRECTOR_OBSERVER_CAMERA.position],
  target: [...DEFAULT_DIRECTOR_OBSERVER_CAMERA.target]
}

export const DIRECTOR_VIEWPORT_BACKGROUND = '#000000'
export const DIRECTOR_VIEWPORT_GROUND_SIZE = 200
export const DIRECTOR_VIEWPORT_GROUND_OPACITY = 0.4
export const DIRECTOR_VIEWPORT_GRID_SIZE = 200
export const DIRECTOR_VIEWPORT_GRID_DIVISIONS = 80
export const DIRECTOR_VIEWPORT_GRID_SECTION_COLOR = '#35404d'
export const DIRECTOR_VIEWPORT_GRID_CELL_COLOR = '#252e38'
export const DIRECTOR_VIEWPORT_CAMERA_NEAR = 0.1
export const DIRECTOR_VIEWPORT_CAMERA_FAR = 1000
export const DIRECTOR_VIEWPORT_CAMERA_LINE = '#A9D8FF'
export const DIRECTOR_VIEWPORT_GEOMETRY_COLOR = '#d7e7ff'

export const DIRECTOR_CHARACTER_COLOR_PALETTE = [
  '#4F8EF7',
  '#E0524D',
  '#E91E63',
  '#F2A900',
  '#9C4DCC',
  '#12B886',
  '#00B8D9',
  '#FF7A45'
] as const

export function resolveDirectorCharacterColor(index: number): string {
  const palette = DIRECTOR_CHARACTER_COLOR_PALETTE
  return palette[(Math.max(index, 1) - 1) % palette.length]
}
