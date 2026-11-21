import type { DirectorLight } from '@/types/studio'

export const DIRECTOR_ENVIRONMENTS = ['day', 'night', 'studio'] as const
export type DirectorEnvironment = typeof DIRECTOR_ENVIRONMENTS[number]

export const DIRECTOR_WEATHERS = ['clear', 'partly-cloudy', 'overcast', 'rain', 'thunderstorm', 'snow', 'fog'] as const
export type DirectorWeather = typeof DIRECTOR_WEATHERS[number]

export const DIRECTOR_ENVIRONMENT_COLORS: Record<DirectorEnvironment, string> = {
  day: '#4182c6',
  night: '#101e3d',
  studio: '#090b0e',
}

const lightNames = { ambient: '环境光', key: '主光', fill: '补光', rim: '轮廓光' }

function light(
  id: keyof typeof lightNames, color: string, intensity: number, position: DirectorLight['position'], enabled = true,
): DirectorLight {
  return { id, name: lightNames[id], kind: id === 'ambient' ? 'ambient' : id === 'rim' ? 'spot' : 'directional', color, intensity, position, enabled }
}

export function createDirectorEnvironmentLights(preset: DirectorEnvironment): DirectorLight[] {
  if (preset === 'day') return [
    light('ambient', '#cce3ff', 1.45, [0, 4, 0]),
    light('key', '#fff1d7', 2.65, [-12, 5.5, -12]),
    light('fill', '#d1e5ff', 0.32, [4, 4, 7]),
    light('rim', '#ffffff', 2.4, [1, 5, -5], false),
  ]
  if (preset === 'night') return [
    light('ambient', '#8aa5ed', 0.52, [0, 4, 0]),
    light('key', '#c5d7ff', 0.84, [-12, 4.8, -10]),
    light('fill', '#a9bcdf', 0.18, [4, 3, 7]),
    light('rim', '#ffffff', 2.4, [1, 5, -5], false),
  ]
  return [
    light('ambient', '#e4eafa', 0.92, [0, 4, 0]),
    light('key', '#fff0dc', 2.35, [-5, 8, 7]),
    light('fill', '#dce8ff', 0.78, [5, 4, 3]),
    light('rim', '#e1eeff', 4.6, [1, 4, -4]),
  ]
}

/** The canvas API accepts a scalar environment preset. Native modeling keeps its three existing preset codes. */
export function encodeDirectorEnvironment(preset: DirectorEnvironment, weather: DirectorWeather): string {
  return weather === 'clear' ? preset : `${preset}:${weather}`
}

export function decodeDirectorEnvironment(value: unknown): { preset: DirectorEnvironment; weather: DirectorWeather } {
  const [environment, weather, extra] = typeof value === 'string' ? value.split(':') : []
  const preset = DIRECTOR_ENVIRONMENTS.find(item => item === environment) ?? 'studio'
  return { preset, weather: extra === undefined && preset !== 'studio'
    ? DIRECTOR_WEATHERS.find(item => item === weather) ?? 'clear'
    : 'clear' }
}
