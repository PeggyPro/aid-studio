import type { AudioClientContract, AudioFileContract, AudioSchema } from '@/types/audio-model'
import { audioConstraints, audioGet, audioSchema } from '@/utils/audioModelContract'

export interface StudioAudioUploadTarget { path: string; label: string; rule: AudioFileContract }
const FILE_LABELS: Record<string, string> = { reference_id: '曲风参考音频', melody_id: '旋律参考', instrumental_id: '伴奏参考',
  upload_audio_id: '编辑原音频', audio_url: '参考音频', url: '原音频', files: '音频素材', audio: '音频素材',
  image_url: '参考图片', image: '参考图片', image_id: '配乐图片', video_url: '配乐视频', video: '配乐视频', video_id: '配乐视频' }

function schemaAt(contract: AudioClientContract, parts: string[]): AudioSchema {
  let schema = contract.schema
  for (const part of parts) {
    schema = audioSchema(schema, contract.schema)
    schema = /^\d+$/.test(part) ? schema.items ?? {} : schema.properties?.[part] ?? {}
  }
  return audioSchema(schema, contract.schema)
}

/** Resolve declared slots, including existing array items and one legal append position. */
export function studioAudioUploadTargets(contract: AudioClientContract, values: Record<string, unknown>): StudioAudioUploadTarget[] {
  const result: StudioAudioUploadTarget[] = [], constraints = audioConstraints(contract, values)
  for (const [pattern, rule] of Object.entries(contract.files)) {
    if (contract.managedFields.some(field => pattern === field || pattern.startsWith(`${field}.`))) continue
    const parts = pattern.split('.')
    const visit = (index: number, path: string[]) => {
      if (index >= parts.length) {
        const field = path.join('.')
        if (constraints.get(`options.${field}`)?.forbidden) return
        const name = path.filter(part => !/^\d+$/.test(part)).at(-1) ?? field
        const position = path.filter(part => /^\d+$/.test(part)).map(part => Number(part) + 1).join(' · ')
        result.push({ path: field, rule, label: `${FILE_LABELS[name] ?? schemaAt(contract, path).title ?? '参考音频'}${position ? ` ${position}` : ''}` })
        return
      }
      if (parts[index] !== '*') { visit(index + 1, [...path, parts[index]]); return }
      const current = audioGet(values, path.join('.')), count = Array.isArray(current) ? current.length : 0
      const max = Math.max(0, Math.min(100, schemaAt(contract, path).maxItems ?? rule.maxItems ?? 100))
      for (let item = 0; item < Math.min(count + 1, max); item++) visit(index + 1, [...path, String(item)])
    }
    visit(0, [])
  }
  return result
}

export function validateStudioAudioAttachment(file: File, rule: AudioFileContract) {
  const extension = file.name.split('.').at(-1)?.toLowerCase() ?? ''
  const formats = rule.formats?.map(format => format.replace(/^\./, '').toLowerCase())
  if (rule.mediaType === 'AUDIO') {
    const audioFormats = formats ?? ['mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'opus', 'aiff', 'aif', 'wma']
    if (!audioFormats.includes(extension)) throw new Error(`支持音频格式：${audioFormats.join('、')}`)
  } else if (formats?.length && !formats.includes(extension)) throw new Error(`支持格式：${formats.join('、')}`)
  if (rule.maxFileSizeMb != null && file.size > rule.maxFileSizeMb * 1024 * 1024) throw new Error(`文件不能超过 ${rule.maxFileSizeMb} MB`)
}
