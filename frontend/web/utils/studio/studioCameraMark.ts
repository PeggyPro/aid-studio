export type StudioCameraMarkKind = 'motion' | 'technique' | 'shot_size' | 'camera_angle'

export interface StudioCameraMark {
  id: string
  kind: StudioCameraMarkKind
  optionKey: string
  label: string
  description?: string
}

export interface StudioCameraMarkDraft {
  kind: StudioCameraMarkKind
  optionKey: string
  label: string
  description?: string
}

const CAMERA_MARK_PATTERN = /^\{\{camera:([^|{}]+)\|(motion|technique|shot_size|camera_angle)\|([^|{}]*)\|([^|{}]*)(?:\|([^{}]*))?\}\}/

let cameraMarkSerial = 0

export function createStudioCameraMarkId(): string {
  cameraMarkSerial += 1
  return `camera_${Date.now().toString(36)}_${cameraMarkSerial.toString(36)}`
}

export function studioCameraMarkToken(mark: StudioCameraMark): string {
  const description = mark.description ? `|${encodeURIComponent(mark.description)}` : ''
  return `{{camera:${encodeURIComponent(mark.id)}|${mark.kind}|${encodeURIComponent(mark.optionKey)}|${encodeURIComponent(mark.label)}${description}}}`
}

export function readStudioCameraMarkAt(
  prompt: string,
  index: number
): { mark: StudioCameraMark; length: number } | { raw: string } | null {
  if (!prompt.startsWith('{{camera:', index)) return null
  const match = CAMERA_MARK_PATTERN.exec(prompt.slice(index))
  if (!match) return null
  try {
    const mark: StudioCameraMark = {
      id: decodeURIComponent(match[1]),
      kind: match[2] as StudioCameraMarkKind,
      optionKey: decodeURIComponent(match[3]),
      label: decodeURIComponent(match[4]),
      ...(match[5] ? { description: decodeURIComponent(match[5]) } : {})
    }
    if (!mark.id || !mark.label) return { raw: match[0] }
    return { mark, length: match[0].length }
  } catch {
    return { raw: match[0] }
  }
}

export function listStudioCameraMarks(prompt: string): StudioCameraMark[] {
  const marks: StudioCameraMark[] = []
  let index = 0
  while (index < prompt.length) {
    const token = readStudioCameraMarkAt(prompt, index)
    if (token && 'mark' in token) {
      marks.push(token.mark)
      index += token.length
      continue
    }
    index += 1
  }
  return marks
}

/** 画布只使用后台中文提示词；旧标记需重新选择以补齐描述。 */
export function studioPromptForModel(prompt: string): string {
  let result = ''
  let index = 0
  while (index < prompt.length) {
    const token = readStudioCameraMarkAt(prompt, index)
    if (token && 'mark' in token) {
      const description = token.mark.description?.trim()
      if (!description) throw new Error(`请重新选择“${token.mark.label}”，补齐后台中文提示词`)
      result += description
      index += token.length
      continue
    }
    if (token && 'raw' in token) {
      result += token.raw
      index += token.raw.length
      continue
    }
    result += prompt[index]
    index += 1
  }
  return result
}

export function studioCameraMarkParameters(prompt: string): { motion?: string; shootingTechnique?: string } {
  const marks = listStudioCameraMarks(prompt)
  const motion = marks.filter((mark) => mark.kind === 'motion').map((mark) => mark.label).join('、')
  const shootingTechnique = marks.filter((mark) => mark.kind === 'technique').map((mark) => mark.label).join('、')
  return {
    ...(motion ? { motion } : {}),
    ...(shootingTechnique ? { shootingTechnique } : {})
  }
}
