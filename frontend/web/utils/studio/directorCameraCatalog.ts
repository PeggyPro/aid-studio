import type {
  DirectorCameraBodyId,
  DirectorLensSeriesId,
  DirectorLensSpec,
  DirectorSensorFormat,
} from '@/types/studio'

export interface DirectorCameraBodyOption {
  id: DirectorCameraBodyId
  label: string
  summary: string
  maxResolution: string
  frameRate: string
  nativeIso: string
  mount: string
  sensorFormats: DirectorSensorFormat[]
  defaultSensorFormat: DirectorSensorFormat
}

export interface DirectorLensSeriesOption {
  id: DirectorLensSeriesId
  label: string
  summary: string
  lensType: DirectorLensSpec['lensType']
  focalLengths: number[]
  apertures: number[]
  defaultFocalLength: number
  defaultAperture: number
}

export const DIRECTOR_CAMERA_BODIES: DirectorCameraBodyOption[] = [
  {
    id: 'virtual-production',
    label: '虚拟制片机',
    summary: '高动态范围 · LED 虚拟棚与实时合成',
    maxResolution: '8K Open Gate',
    frameRate: '4K 120fps',
    nativeIso: '双原生 ISO 800 / 3200',
    mount: 'PL / LPL',
    sensorFormats: ['full-frame', 'super-35'],
    defaultSensorFormat: 'full-frame',
  },
  {
    id: 'lightweight-handheld',
    label: '轻量手持机',
    summary: '机动拍摄 · 肩扛、稳定器与纪录片',
    maxResolution: '6K Open Gate',
    frameRate: '4K 120fps',
    nativeIso: '双原生 ISO 800 / 2500',
    mount: 'E / RF',
    sensorFormats: ['super-35', 'aps-c'],
    defaultSensorFormat: 'super-35',
  },
  {
    id: 'digital-cinema',
    label: '数字电影机',
    summary: '标准电影流程 · 高宽容度与专业附件',
    maxResolution: '6K DCI',
    frameRate: '4K 120fps',
    nativeIso: '双原生 ISO 800 / 3200',
    mount: 'PL',
    sensorFormats: ['super-35', 'full-frame'],
    defaultSensorFormat: 'full-frame',
  },
]

export const DIRECTOR_LENS_SERIES: DirectorLensSeriesOption[] = [
  {
    id: 'cine-prime',
    label: '电影定焦组',
    summary: '低呼吸效应 · 统一色彩与齿位',
    lensType: 'prime',
    focalLengths: [18, 24, 28, 35, 50, 65, 85, 100, 135],
    apertures: [1.4, 2, 2.8, 4, 5.6, 8, 11, 16],
    defaultFocalLength: 50,
    defaultAperture: 2.8,
  },
  {
    id: 'cine-zoom',
    label: '电影变焦组',
    summary: '恒定光圈 · 适合推拉与快速改构图',
    lensType: 'zoom',
    focalLengths: [18, 24, 28, 35, 50, 70, 85, 100, 135, 200],
    apertures: [2.8, 4, 5.6, 8, 11, 16],
    defaultFocalLength: 50,
    defaultAperture: 2.8,
  },
  {
    id: 'anamorphic-prime',
    label: '变形宽银幕组',
    summary: '2× 横向压缩 · 椭圆焦外与横向眩光',
    lensType: 'prime',
    focalLengths: [32, 40, 50, 75, 100, 135],
    apertures: [2, 2.8, 4, 5.6, 8, 11],
    defaultFocalLength: 50,
    defaultAperture: 2.8,
  },
  {
    id: 'documentary-zoom',
    label: '轻量纪录变焦组',
    summary: '轻量内变焦 · 手持与长时间跟拍',
    lensType: 'zoom',
    focalLengths: [18, 24, 35, 50, 70, 105, 135],
    apertures: [2.8, 4, 5.6, 8, 11, 16],
    defaultFocalLength: 35,
    defaultAperture: 4,
  },
]

export const DIRECTOR_SENSOR_FORMATS: Array<{
  id: DirectorSensorFormat
  label: string
  sensorWidthMm: number
  summary: string
}> = [
  { id: 'full-frame', label: '全画幅', sensorWidthMm: 36, summary: '36.0 mm · 更广视角' },
  { id: 'super-35', label: 'Super 35', sensorWidthMm: 24.89, summary: '24.89 mm · 电影标准' },
  { id: 'aps-c', label: 'APS-C', sensorWidthMm: 23.6, summary: '23.6 mm · 轻量机身' },
]

export const DEFAULT_DIRECTOR_LENS_SPEC: DirectorLensSpec = {
  cameraBody: 'digital-cinema',
  lensSeries: 'cine-prime',
  bodyName: 'full-frame',
  lensType: 'prime',
  focalLengthMm: 50,
  apertureF: 2.8,
  sensorWidthMm: 36,
}

export function findDirectorCameraBody(id: DirectorCameraBodyId | undefined): DirectorCameraBodyOption {
  return DIRECTOR_CAMERA_BODIES.find((item) => item.id === id) ?? DIRECTOR_CAMERA_BODIES[2]
}

export function findDirectorLensSeries(id: DirectorLensSeriesId | undefined): DirectorLensSeriesOption {
  return DIRECTOR_LENS_SERIES.find((item) => item.id === id) ?? DIRECTOR_LENS_SERIES[0]
}

export function findDirectorSensorFormat(id: DirectorSensorFormat): (typeof DIRECTOR_SENSOR_FORMATS)[number] {
  return DIRECTOR_SENSOR_FORMATS.find((item) => item.id === id) ?? DIRECTOR_SENSOR_FORMATS[1]
}

export function normalizeDirectorLensSpec(input?: Partial<DirectorLensSpec>): DirectorLensSpec {
  const inferredBody = input?.cameraBody ?? (input?.bodyName === 'aps-c' ? 'lightweight-handheld' : 'digital-cinema')
  const cameraBody = findDirectorCameraBody(inferredBody)
  const inferredSeries = input?.lensSeries ?? (input?.lensType === 'zoom' ? 'cine-zoom' : 'cine-prime')
  const lensSeries = findDirectorLensSeries(inferredSeries)
  const requestedFormat = input?.bodyName
  const bodyName = requestedFormat && cameraBody.sensorFormats.includes(requestedFormat)
    ? requestedFormat
    : cameraBody.defaultSensorFormat
  const sensor = findDirectorSensorFormat(bodyName)
  return {
    cameraBody: cameraBody.id,
    lensSeries: lensSeries.id,
    bodyName,
    lensType: lensSeries.lensType,
    focalLengthMm: clampNumber(input?.focalLengthMm, 8, 1000, lensSeries.defaultFocalLength),
    apertureF: clampNumber(input?.apertureF, 0.7, 32, lensSeries.defaultAperture),
    sensorWidthMm: sensor.sensorWidthMm,
  }
}

export function changeDirectorCameraBody(spec: DirectorLensSpec, id: DirectorCameraBodyId): DirectorLensSpec {
  const body = findDirectorCameraBody(id)
  const format = body.sensorFormats.includes(spec.bodyName) ? spec.bodyName : body.defaultSensorFormat
  return normalizeDirectorLensSpec({ ...spec, cameraBody: id, bodyName: format })
}

export function changeDirectorLensSeries(spec: DirectorLensSpec, id: DirectorLensSeriesId): DirectorLensSpec {
  const series = findDirectorLensSeries(id)
  return normalizeDirectorLensSpec({
    ...spec,
    lensSeries: id,
    lensType: series.lensType,
    focalLengthMm: series.focalLengths.includes(spec.focalLengthMm) ? spec.focalLengthMm : series.defaultFocalLength,
    apertureF: series.apertures.includes(spec.apertureF ?? 0) ? spec.apertureF : series.defaultAperture,
  })
}

export function changeDirectorSensorFormat(spec: DirectorLensSpec, id: DirectorSensorFormat): DirectorLensSpec {
  return normalizeDirectorLensSpec({ ...spec, bodyName: id, sensorWidthMm: findDirectorSensorFormat(id).sensorWidthMm })
}

function clampNumber(value: number | undefined, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback
  return Math.min(max, Math.max(min, Number(value)))
}
