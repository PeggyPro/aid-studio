import type { DirectorCamera, DirectorLensSpec } from '@/types/studio'
import { DEFAULT_DIRECTOR_LENS_SPEC, findDirectorSensorFormat, normalizeDirectorLensSpec } from './directorCameraCatalog'

export function directorLensFov(lens: DirectorLensSpec, aspect = 16 / 9): number {
  const normalized = normalizeDirectorLensSpec(lens)
  const width = normalized.sensorWidthMm ?? findDirectorSensorFormat(normalized.bodyName).sensorWidthMm
  const height = aspect < 1 ? width : (width * 2) / 3
  return Number(((2 * Math.atan(height / (2 * Math.max(1, normalized.focalLengthMm))) * 180) / Math.PI).toFixed(4))
}

export function directorFocalLengthForFov(
  fov: number,
  sensorWidthMm = 36,
  aspect = 16 / 9,
): number {
  const height = aspect < 1 ? sensorWidthMm : (sensorWidthMm * 2) / 3
  const radians = Math.min(179, Math.max(1, fov)) * Math.PI / 180
  return Number((height / (2 * Math.tan(radians / 2))).toFixed(2))
}

export function createDirectorLensSpecForCamera(camera: DirectorCamera, aspect = 16 / 9): DirectorLensSpec {
  return normalizeDirectorLensSpec({
    ...DEFAULT_DIRECTOR_LENS_SPEC,
    focalLengthMm: directorFocalLengthForFov(camera.fov, DEFAULT_DIRECTOR_LENS_SPEC.sensorWidthMm, aspect),
  })
}

export function syncDirectorLensToCameraFov(
  lens: DirectorLensSpec,
  camera: DirectorCamera,
  aspect = 16 / 9,
): DirectorLensSpec {
  const normalized = normalizeDirectorLensSpec(lens)
  return {
    ...normalized,
    focalLengthMm: directorFocalLengthForFov(
      camera.fov,
      normalized.sensorWidthMm ?? findDirectorSensorFormat(normalized.bodyName).sensorWidthMm,
      aspect,
    ),
  }
}
