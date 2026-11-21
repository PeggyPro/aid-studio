import * as THREE from 'three'
import type { DirectorSceneData } from '@/types/studio'
import {
  createDirectorEnvironmentLights,
  DIRECTOR_ENVIRONMENT_COLORS,
  type DirectorEnvironment,
  type DirectorWeather,
} from '@/utils/studio/directorEnvironment'
import { precipitationFragment, precipitationVertex, skyFragment, skyVertex } from './directorAtmosphereShaders'
import { disposeDirectorObjectTree } from './directorRuntimeResources'

const palettes = {
  day: { horizon: '#c7e0ef', floor: '#626d67', ground: '#818b77', clouds: '#eff7ff', sun: '#fff1d6' },
  night: { horizon: '#283854', floor: '#1a2230', ground: '#606975', clouds: '#65748e', sun: '#cbdbff' },
  studio: { horizon: '#4a515d', floor: '#252930', ground: '#858791', clouds: '#ffffff', sun: '#ffffff' },
} satisfies Record<DirectorEnvironment, Record<string, string>>

interface WeatherProfile {
  cover: number
  cloudOpacity: number
  haze: number
  hazeColor: string
  hazeMix: number
  direct: number
  bounce: number
  precipitation?: 'rain' | 'snow'
  opacity?: number
  wind?: number
}

const weatherProfiles: Record<DirectorWeather, WeatherProfile> = {
  clear: { cover: 0, cloudOpacity: 0, haze: .003, hazeColor: '#c7e0ef', hazeMix: 0, direct: 1, bounce: 1 },
  'partly-cloudy': { cover: .48, cloudOpacity: .85, haze: .006, hazeColor: '#d3dee6', hazeMix: .13, direct: .82, bounce: 1 },
  overcast: { cover: .87, cloudOpacity: .94, haze: .013, hazeColor: '#a9b7c0', hazeMix: .59, direct: .29, bounce: .94 },
  rain: { cover: .93, cloudOpacity: .99, haze: .033, hazeColor: '#8395a5', hazeMix: .72, direct: .23, bounce: .85, precipitation: 'rain', opacity: .66, wind: 1.5 },
  thunderstorm: { cover: .98, cloudOpacity: 1, haze: .049, hazeColor: '#566078', hazeMix: .9, direct: .12, bounce: .67, precipitation: 'rain', opacity: .88, wind: 3.8 },
  snow: { cover: .86, cloudOpacity: .9, haze: .027, hazeColor: '#c9d7e0', hazeMix: .64, direct: .41, bounce: 1.15, precipitation: 'snow', opacity: .92, wind: .58 },
  fog: { cover: .64, cloudOpacity: .7, haze: .104, hazeColor: '#c9d3d7', hazeMix: .95, direct: .25, bounce: 1 },
}

const colorUniform = () => ({ value: new THREE.Color() })
const floatUniform = (value = 0) => ({ value })

function precipitationGeometry(count: number, streaks: boolean) {
  const positions: number[] = []
  const seeds: number[] = []
  const tips: number[] = []
  // Stable spatial distribution across views and deterministic recording frames.
  let state = 137
  const random = () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state / 0x100000000
  }
  for (let index = 0; index < count; index += 1) {
    const x = random(), y = random(), z = random(), seed = random()
    positions.push(x, y, z)
    seeds.push(seed)
    tips.push(0)
    if (streaks) {
      positions.push(x, y, z)
      seeds.push(seed)
      tips.push(1)
    }
  }
  return new THREE.BufferGeometry()
    .setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
    .setAttribute('aSeed', new THREE.Float32BufferAttribute(seeds, 1))
    .setAttribute('aTip', new THREE.Float32BufferAttribute(tips, 1))
}

export function directorWeatherLightIntensity(scene: DirectorSceneData, id: string, intensity: number): number {
  if (scene.environmentPreset === 'studio') return intensity
  const profile = weatherProfiles[scene.weather ?? 'clear'] ?? weatherProfiles.clear
  if (id === 'key') return intensity * profile.direct
  if (id === 'ambient') return intensity * profile.bounce
  return intensity
}

export function createDirectorEnvironmentRuntime(scene: THREE.Scene, ground: THREE.MeshStandardMaterial, reducedMotion: boolean) {
  const root = new THREE.Group()
  root.name = 'director-atmosphere'
  const background = new THREE.Color()
  const fog = new THREE.FogExp2('#ffffff')
  const sky = {
    uZenith: colorUniform(), uHorizon: colorUniform(), uFloor: colorUniform(),
    uCloud: colorUniform(), uSun: colorUniform(), uLightDirection: { value: new THREE.Vector3() },
    uCover: floatUniform(), uCloudOpacity: floatUniform(), uNight: floatUniform(), uOutdoor: floatUniform(),
    uFlash: floatUniform(), uTime: floatUniform(),
  }
  const dome = new THREE.Mesh(new THREE.SphereGeometry(200, 28, 16), new THREE.ShaderMaterial({
    uniforms: sky, vertexShader: skyVertex, fragmentShader: skyFragment,
    side: THREE.BackSide, depthTest: false, depthWrite: false,
  }))
  dome.frustumCulled = false
  dome.renderOrder = -20
  root.add(dome)

  const particleTime = floatUniform()
  const particleFog = floatUniform()
  const particleWind = floatUniform()
  const particleOpacity = floatUniform()
  const particleColor = colorUniform()
  const particleMaterial = (snow: boolean) => new THREE.ShaderMaterial({
    uniforms: { uTime: particleTime, uFog: particleFog, uWind: particleWind, uOpacity: particleOpacity, uColor: particleColor, uSnow: floatUniform(Number(snow)) },
    vertexShader: precipitationVertex, fragmentShader: precipitationFragment,
    transparent: true, depthWrite: false, toneMapped: false,
  })
  const rain = new THREE.LineSegments(precipitationGeometry(9200, true), particleMaterial(false))
  const snow = new THREE.Points(precipitationGeometry(5400, false), particleMaterial(true))
  rain.frustumCulled = snow.frustumCulled = false
  rain.renderOrder = snow.renderOrder = 2
  rain.visible = snow.visible = false
  root.add(rain, snow)
  scene.add(root)

  let weather: DirectorWeather = 'clear'
  let time = 0
  const setTime = (nextTime: number) => {
    time = nextTime
    sky.uTime.value = particleTime.value = nextTime
    const flashAge = (nextTime + 2.1) % 11.2
    sky.uFlash.value = weather === 'thunderstorm' && !reducedMotion
      ? .65 * Math.exp(-18 * flashAge) * Math.min(flashAge * 85, 1)
      : 0
  }

  return {
    get time() { return time },
    get animated() { return !reducedMotion && (rain.visible || snow.visible) },
    setTime,
    sync(value: DirectorSceneData) {
      const preset = value.environmentPreset ?? 'studio'
      const palette = palettes[preset] ?? palettes.studio
      weather = preset === 'studio' ? 'clear' : value.weather ?? 'clear'
      const profile = weatherProfiles[weather] ?? weatherProfiles.clear
      const daylight = preset === 'day'
      const outdoor = preset !== 'studio'
      const night = preset === 'night'
      const haze = new THREE.Color(profile.hazeColor)
      if (night) haze.multiplyScalar(.17)
      background.set(value.environmentColor || DIRECTOR_ENVIRONMENT_COLORS[preset])
      sky.uHorizon.value.set(palette.horizon).lerp(haze, profile.hazeMix)
      sky.uZenith.value.copy(background).lerp(haze, profile.hazeMix * .82)
      sky.uFloor.value.set(palette.floor).lerp(haze, profile.hazeMix * .54)
      sky.uCloud.value.set(palette.clouds).lerp(haze, profile.hazeMix * .49)
      sky.uSun.value.set(palette.sun).multiplyScalar(profile.direct)
      sky.uOutdoor.value = Number(outdoor)
      sky.uNight.value = Number(night)
      sky.uCover.value = profile.cover
      sky.uCloudOpacity.value = profile.cloudOpacity
      const key = value.lights.find(light => light.id === 'key') ?? createDirectorEnvironmentLights(preset)[1]
      sky.uLightDirection.value.fromArray(key.position).sub(new THREE.Vector3(0, 1.1, 0)).normalize()
      fog.color.copy(sky.uHorizon.value)
      fog.density = outdoor ? profile.haze : .012
      dome.visible = outdoor
      scene.fog = outdoor ? fog : null
      scene.background = background

      const wet = profile.precipitation === 'rain'
      ground.color.set(palette.ground)
      if (wet) ground.color.multiplyScalar(.68)
      if (profile.precipitation === 'snow') ground.color.set('#d7e3ea')
      ground.roughness = wet ? .38 : .94
      rain.visible = wet
      snow.visible = profile.precipitation === 'snow'
      rain.geometry.setDrawRange(0, weather === 'thunderstorm' ? 18400 : 12200)
      particleColor.value.set(daylight ? (wet ? '#c6d8eb' : '#ffffff') : '#adbedb')
      particleFog.value = fog.density
      particleWind.value = profile.wind ?? 0
      particleOpacity.value = profile.opacity ?? 0
      setTime(time)
    },
    dispose() {
      root.removeFromParent()
      disposeDirectorObjectTree(root)
    },
  }
}

export type DirectorEnvironmentRuntime = ReturnType<typeof createDirectorEnvironmentRuntime>
