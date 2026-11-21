import type { UserModelListItem } from '@/types/business-api'
import type { StudioImageHdScale } from '@/types/studio'
import { findModelCapability, modelForCapability } from '@/utils/modelCapability'
import { allowedParameterOptions, modelParameterConstraints } from '@/utils/modelParameterConstraints'
import { STUDIO_HD_SCALES } from './studioImageAiTools'

const FACTOR_PATHS = ['options.parameters.upscale_factor', 'options.upscale_factor']

function upscaleContract(model: UserModelListItem) {
  const projected = modelForCapability(model, 'image_upscale')!
  const constraints = modelParameterConstraints(projected)
  const factorPath = FACTOR_PATHS.find(path => constraints.has(path))
  return { projected, constraints, factorPath }
}

export function canvasImageUpscaleScales(model: UserModelListItem): StudioImageHdScale[] {
  const { constraints, factorPath } = upscaleContract(model)
  return allowedParameterOptions(STUDIO_HD_SCALES, factorPath ? constraints.get(factorPath) : undefined)
}

/** 报价和提交共用能力声明；倍数模型不误用仅像素模型的空提示词与宽高请求。 */
export function canvasImageUpscaleSettings(model: UserModelListItem, width: number, height: number, scale: StudioImageHdScale) {
  if (!canvasImageUpscaleScales(model).includes(scale)) throw new Error('所选模型不支持当前放大倍数')
  const { projected, factorPath } = upscaleContract(model)
  const presentation = findModelCapability(model, 'image_upscale')?.presentation
  const supportsText = presentation?.supportsTextInput ?? projected.supportsTextInput
  const prompt = supportsText === false || projected.capability?.promptOptional === true
    ? '' : `将原图放大${scale}倍，增强清晰度并保持原图内容、构图和比例。`
  const parameters = factorPath === 'options.parameters.upscale_factor' ? { parameters: { upscale_factor: scale } }
    : factorPath ? { upscale_factor: scale } : undefined
  const hasSize = projected.parameterSchema?.some(parameter => parameter.name === 'size')
  return {
    prompt,
    ...(!factorPath || hasSize ? { size: `${width * scale}x${height * scale}` } : {}),
    ...(parameters ? { parameters } : {})
  }
}
