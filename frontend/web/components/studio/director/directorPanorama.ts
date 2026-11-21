import { resolveClientApiUrl } from '@/utils/api'

export const DIRECTOR_PANORAMA_DEFAULT_RADIUS = 60
export const DIRECTOR_PANORAMA_MIN_RADIUS = 12
export const DIRECTOR_PANORAMA_MAX_RADIUS = 200

export function isDirectorInlineMediaUrl(url: string): boolean {
  return url.startsWith('blob:') || url.startsWith('data:')
}

export function resolveDirectorPanoramaLoadUrl(url: string, origin = typeof window !== 'undefined' ? window.location.origin : ''): string {
  const trimmed = url.trim()
  if (!trimmed || isDirectorInlineMediaUrl(trimmed) || trimmed.startsWith('/')) return trimmed
  try {
    const parsed = new URL(trimmed, origin || undefined)
    if (origin && parsed.origin === origin) return trimmed
  } catch {
    return trimmed
  }
  const params = new URLSearchParams({ url: trimmed })
  return `${resolveClientApiUrl('/api/image-proxy')}?${params.toString()}`
}

export function resolveDirectorPanoramaRadius(radius?: number): number {
  if (!Number.isFinite(radius) || !radius) return DIRECTOR_PANORAMA_DEFAULT_RADIUS
  return Math.min(DIRECTOR_PANORAMA_MAX_RADIUS, Math.max(DIRECTOR_PANORAMA_MIN_RADIUS, radius))
}

export function resolveDirectorPanoramaYaw(yaw?: number): number {
  return Number.isFinite(yaw) ? Number(yaw) : 0
}

export function isDirectorPanoramaFile(file: File): boolean {
  return file.type.startsWith('image/') || /\.(jpe?g|png|webp|gif|bmp|hdr|exr)$/i.test(file.name)
}

export function createDirectorPanoramaObjectUrl(file: File): string {
  if (!isDirectorPanoramaFile(file)) throw new Error('请选择图片文件作为全景图')
  return URL.createObjectURL(file)
}

export function isDirectorOrbitCameraEcho(
  live: { position: [number, number, number]; target: [number, number, number]; fov: number },
  stored?: { position: [number, number, number]; target: [number, number, number]; fov: number } | null,
  epsilon = 0.0008
): boolean {
  if (!stored) return false
  const close = (left: number, right: number) => Math.abs(left - right) <= epsilon
  return close(live.fov, stored.fov)
    && live.position.every((value, index) => close(value, stored.position[index]))
    && live.target.every((value, index) => close(value, stored.target[index]))
}

export function applyDirectorPanoramaTransform(
  target: { scale: { set: (x: number, y: number, z: number) => void }; rotation: { y: number } },
  radius?: number,
  yawDegrees?: number
) {
  const size = resolveDirectorPanoramaRadius(radius)
  target.scale.set(size, size, size)
  target.rotation.y = resolveDirectorPanoramaYaw(yawDegrees) * Math.PI / 180
}
