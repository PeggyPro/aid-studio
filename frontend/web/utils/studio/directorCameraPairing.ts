import { withAppBasePath } from '@/utils/appBasePath'
import { VIRTUAL_CAMERA_PATH } from '@/utils/mobileOnlyNavigation'
import { isStudioFreeTheme, type StudioFreeTheme } from './studioFreeTheme'
import type { DirectorCameraRoom } from './directorCameraApi'

export function readDirectorCameraPairing(fragment: string) {
  const params = new URLSearchParams(fragment.replace(/^#/, ''))
  const theme = params.get('theme')
  return { sid: params.get('s') ?? params.get('sid') ?? '', code: params.get('t') ?? params.get('pair') ?? '', theme: isStudioFreeTheme(theme) ? theme : undefined }
}

export function directorCameraPairingUrl(room: DirectorCameraRoom, pageOrigin: string, theme?: StudioFreeTheme): string {
  if (!room.pairingCode) return ''
  try {
    const developmentTarget = process.env.NODE_ENV === 'development'
      ? process.env.AID_DEV_DIRECTOR_ORIGIN || process.env.AID_DEV_PROXY_TARGET
      : undefined
    const origin = developmentTarget ? new URL(developmentTarget).origin : pageOrigin
    const entry = room.pairingUrl ? new URL(room.pairingUrl) : new URL(withAppBasePath(VIRTUAL_CAMERA_PATH), origin)
    if (entry.protocol !== 'https:' || entry.username || entry.password || /^(localhost|127\.|\[::1\])/.test(entry.hostname)) return ''
    const provided = readDirectorCameraPairing(entry.hash)
    if (provided.sid !== room.sessionId || provided.code !== room.pairingCode) {
      entry.hash = new URLSearchParams({ sid: room.sessionId, pair: room.pairingCode }).toString()
    }
    if (theme) {
      const params = new URLSearchParams(entry.hash.slice(1))
      params.set('theme', theme)
      entry.hash = params.toString()
    }
    return entry.href
  } catch {
    return ''
  }
}
