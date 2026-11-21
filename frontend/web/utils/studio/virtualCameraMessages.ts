import { isStudioFreeTheme, type StudioFreeTheme } from './studioFreeTheme'
import type { DirectorCamera, DirectorLensSpec, DirectorSceneData } from '@/types/studio'
import type { VirtualCameraControl } from './virtualCameraPose'
import { clampVirtualCameraControl } from './virtualCameraPose'

export interface VirtualCameraSnapshot {
  cameraId: string
  duration: number
  camera: DirectorCamera
  lens?: DirectorLensSpec
  objects: DirectorSceneData['objects']
  modelAssets?: DirectorSceneData['modelAssets']
  lights: DirectorSceneData['lights']
  scenePackage?: DirectorSceneData['scenePackage']
  cameraBookmarks?: DirectorSceneData['cameraBookmarks']
}

export type VirtualCameraMessage =
  | { type: 'hello'; role: 'host' | 'guest' }
  | { type: 'theme'; theme: StudioFreeTheme }
  | { type: 'pose'; control: VirtualCameraControl }
  | { type: 'camera'; cameraId: string; camera: DirectorCamera; lens?: DirectorLensSpec; recording: boolean; playhead?: number }
  | { type: 'record'; recording: 'start' | 'stop' }
  | { type: 'snapshot'; snapshot: VirtualCameraSnapshot }
  | { type: 'camera:switch'; cameraId: string; snapshot?: VirtualCameraSnapshot }
  | { type: 'kick' | 'reset' }
  | { type: 'error'; code: 'EXPIRED' | 'FULL' | 'SCENE_LOAD' | string; message?: string }

export function parseVirtualCameraMessage(raw: unknown): VirtualCameraMessage | null {
  let value: unknown = raw
  if (typeof raw === 'string') {
    try { value = JSON.parse(raw) } catch { return null }
  }
  if (!value || typeof value !== 'object') return null
  const message = value as Record<string, unknown>
  if (message.type === 'pose') {
    const control = clampVirtualCameraControl(message.control)
    return control ? { type: 'pose', control } : null
  }
  if (message.type === 'hello' && ['host', 'guest'].includes(String(message.role))) return message as unknown as VirtualCameraMessage
  if (message.type === 'theme' && typeof message.theme === 'string' && isStudioFreeTheme(message.theme)) return { type: 'theme', theme: message.theme }
  if (['kick', 'reset'].includes(String(message.type))) return { type: message.type as 'kick' | 'reset' }
  if (message.type === 'record' && ['start', 'stop'].includes(String(message.recording))) return message as unknown as VirtualCameraMessage
  if (message.type === 'camera:switch' && typeof message.cameraId === 'string') return message as unknown as VirtualCameraMessage
  if (message.type === 'error' && typeof message.code === 'string') return message as unknown as VirtualCameraMessage
  if (message.type === 'snapshot' && message.snapshot && typeof message.snapshot === 'object') return message as unknown as VirtualCameraMessage
  if (message.type === 'camera' && typeof message.cameraId === 'string' && message.camera && typeof message.camera === 'object') return message as unknown as VirtualCameraMessage
  return null
}
