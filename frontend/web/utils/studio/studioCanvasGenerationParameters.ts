import type { UserModelListItem } from '@/types/business-api'
import type { StudioCanvasNode } from '@/types/studio'
import type { ModelParameter } from '@/types/modelParameters'
import { resolveCapabilityRecord } from '@/utils/modelCapability'
import { studioCameraMarkParameters, studioPromptForModel } from './studioCameraMark'

function optionalText(value: string | undefined): string | undefined {
  return value?.trim() || undefined
}

/** 只统一表示方式，不把非法显式参数夹到另一个收费规格；由同一服务端校验拒绝。 */
export function studioCanvasImageParameters(node: StudioCanvasNode, model?: UserModelListItem | null) {
  const options = node.data.generationOptions ?? {}
  const supportsSizePreset = model?.supportsSizePreset ?? resolveCapabilityRecord(model).supportsSizePreset
  const quality = optionalText(options.quality)
  const size = supportsSizePreset === false ? undefined
    : quality && /^\d+k$/i.test(quality) ? quality.toUpperCase() : quality
  return {
    size,
    expectedImageCount: options.count ?? 1,
    aspectRatio: optionalText(options.aspectRatio),
    styleId: optionalText(options.styleId)
  }
}

export function studioCanvasImagePrompt(node: StudioCanvasNode, prompt: string): string {
  const options = node.data.generationOptions
  const style = optionalText(options?.stylePrompt)
  if ((options?.styleId || options?.styleName) && !style) {
    throw new Error('请重新选择风格，补齐后台中文提示词')
  }
  const text = studioPromptForModel(prompt)
  return style ? [text, `风格：${style}`].filter(Boolean).join('\n') : text
}

function declaresVideoOption(parameters: ModelParameter[] | null | undefined, name: string): boolean {
  return Boolean(parameters?.some(parameter => parameter.name === name
    || ((parameter.name === 'options' || parameter.name === 'parameters')
      && parameter.properties?.some(option => option.name === name))))
}

/** 运镜标记保留在创作正文；仅为明确声明对应字段的模型附加结构参数。 */
export function studioCanvasVideoParameters(node: StudioCanvasNode, prompt: string, model?: UserModelListItem | null) {
  const options = node.data.generationOptions ?? {}
  const camera = studioCameraMarkParameters(prompt)
  const parameters = Object.fromEntries(Object.entries({
    resolution: optionalText(options.quality),
    motion: !model || declaresVideoOption(model.parameterSchema, 'motion')
      ? camera.motion || optionalText(options.motion) : undefined,
    shootingTechnique: !model || declaresVideoOption(model.parameterSchema, 'shootingTechnique')
      ? camera.shootingTechnique || optionalText(options.shootingTechnique) : undefined
  }).filter(([, value]) => value !== undefined))
  return {
    prompt: studioCanvasImagePrompt(node, prompt),
    // -1 等显式值原样交给当前能力的服务端校验；不可在报价时取整或静默删掉。
    durationSeconds: model?.supportsDuration === false ? undefined : options.duration,
    aspectRatio: optionalText(options.aspectRatio),
    ...(typeof options.audioEnabled === 'boolean' ? { audio: options.audioEnabled } : {}),
    parameters: Object.keys(parameters).length ? parameters : undefined
  }
}
