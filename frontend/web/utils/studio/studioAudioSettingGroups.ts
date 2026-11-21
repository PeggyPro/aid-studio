import type { AudioClientContract, AudioSchema } from '@/types/audio-model'
import { audioFieldContract, audioGet, audioSchema, audioSchemaType, isImplicitAudioField } from '@/utils/audioModelContract'
import type { ParameterConstraint } from '@/utils/modelParameterConstraints'

/** 接口没给可读标题时的中文名。有标题时以接口标题为准，这里不覆盖。 */
const LABELS: Record<string, string> = {
  prompt: '音乐描述', text_prompt: '音频描述', description: '配乐描述', lyrics: '歌词',
  language_code: '语种', audio_config: '音频输出', audio_setting: '音频输出',
  sample_rate: '采样率', audio_sample_rate: '采样率', format: '输出格式', output_format: '输出格式', bitrate: '码率',
  music_length_ms: '音乐时长', duration: '时长', n: '结果数量', variants_num: '版本数量',
  gender: '人声性别', vocal_gender: '人声性别', voice_id: '音色', vocal_id: '歌声音色',
  references: '参考素材', audio_url: '参考音频', image_url: '参考图片', videos: '配乐视频', video: '配乐视频', video_url: '配乐视频',
  reference_id: '曲风参考音频', melody_id: '旋律参考', instrumental_id: '伴奏参考', upload_audio_id: '编辑原音频',
  image_id: '配乐图片', video_id: '配乐视频', song_id: '本人历史歌曲', url: '待处理音频',
  text: '配音文本', inputs: '配音段落', messages: '流式消息', queryParameters: '输出设置',
  stream: '流式返回', mode: '生成模式', styles: '音乐风格', force_instrumental: '纯音乐', is_instrumental: '纯音乐',
  lyrics_optimizer: '歌词优化', stems: '同时输出分轨', ducking: '人声时降低背景音乐', preserve_speech: '保留视频人声',
  add_sfx: '添加音效', separate_tracks: '分别输出音轨', use_audio_as_reference: '参考视频原声',
  composition_plan: '结构化作曲计划', chunks: '音乐段落', generate_type: '单轨类型',
  extend_at: '续写位置（毫秒）', extend_type: '续写方式', edit_start: '编辑开始（毫秒）', edit_end: '编辑结束（毫秒）',
  generate_start: '生成开始（毫秒）', generate_end: '生成结束（毫秒）', watermark: '水印', aigc_watermark: 'AI 水印',
  close_socket: '结束流式连接', close_context: '结束上下文', voices: '注册音色', settings: '配音设置',
  segments: '音乐时间线', start: '开始时间（秒）', label: '段落标签',
  enable_subtitle: '字幕', enable: '启用', speech_rate: '语速', loudness_rate: '响度', pitch: '音高', pitch_rate: '音高', emotion: '情绪',
  speed: '语速', vol: '音量', channel: '声道', speaker: '说话人'
}

const SUMMARY = ['language_code', 'audio_sample_rate', 'sample_rate', 'format', 'output_format', 'duration', 'music_length_ms']
const RANK: Record<string, number> = {
  language_code: 0, voice_id: 1, vocal_id: 1, audio_sample_rate: 2, sample_rate: 2,
  format: 3, output_format: 3, bitrate: 4, duration: 5, music_length_ms: 5, lyrics: 6
}

export interface AudioSettingOption { value: unknown; label: string }

export interface AudioSettingGroup {
  path: string
  title: string
  required: boolean
  kind: 'segments' | 'switch' | 'number' | 'text' | 'menu' | 'slider' | 'list' | 'field'
  value: unknown
  schema: AudioSchema
  options?: AudioSettingOption[]
  minimum?: number
  maximum?: number
}

const SLIDER_FIELDS = new Set(['speech_rate', 'loudness_rate', 'speed', 'vol', 'pitch', 'pitch_rate'])
const SLIDER_FALLBACK: Record<string, { min: number; max: number }> = {
  speech_rate: { min: -50, max: 100 }, loudness_rate: { min: -50, max: 100 }, speed: { min: -50, max: 100 }, vol: { min: -50, max: 100 },
  pitch: { min: -12, max: 12 }, pitch_rate: { min: -12, max: 12 }
}

function sliderBounds(name: string, schema: AudioSchema) {
  if (!SLIDER_FIELDS.has(name)) return null
  const fallback = SLIDER_FALLBACK[name]
  const min = schema.minimum ?? fallback?.min
  const max = schema.maximum ?? fallback?.max
  if (min == null || max == null || min >= max) return null
  return { min, max }
}

function hasVisibleControl(contract: AudioClientContract, schema: AudioSchema, path: string, required: boolean, depth: number): boolean {
  if (depth > 12 || path === contract.promptPath || contract.managedFields.includes(path)) return false
  const resolved = audioSchema(schema, contract.schema)
  const name = path.split('.').filter(part => !/^\d+$/.test(part)).at(-1) ?? ''
  if (name && (isImplicitAudioField(name, required) || name.endsWith('_data') || name.endsWith('_base64'))) return false
  if (audioFieldContract(contract, path)) return false
  if (audioSchemaType(resolved) === 'object' && resolved.properties) {
    return Object.entries(resolved.properties).some(([key, child]) => hasVisibleControl(contract, child, `${path}.${key}`, resolved.required?.includes(key) ?? false, depth + 1))
  }
  if (audioSchemaType(resolved) === 'array') return hasVisibleControl(contract, resolved.items ?? {}, `${path}.0`, true, depth + 1)
  return true
}

const machineName = (value: string) => /^[A-Za-z0-9_.]+$/.test(value)

export function audioSettingLabel(name: string, schema?: AudioSchema) {
  const title = schema?.title?.trim()
  if (title && !machineName(title)) return title
  return LABELS[name] ?? title ?? name
}

function choiceLabel(contract: AudioClientContract, name: string, item: unknown) {
  const custom = contract.choiceLabels?.[name]?.[String(item)]
  if (custom) return custom
  if ((name === 'sample_rate' || name === 'audio_sample_rate') && typeof item === 'number' && item >= 1000 && item % 100 === 0) {
    const khz = item / 1000
    return `${Number.isInteger(khz) ? khz : Math.round(khz * 10) / 10}k`
  }
  return String(item)
}

function pushGroup(
  groups: AudioSettingGroup[],
  contract: AudioClientContract,
  schema: AudioSchema,
  path: string,
  required: boolean,
  current: unknown
) {
  const name = path.split('.').at(-1) ?? path
  const label = audioSettingLabel(name, schema)
  const title = label === '采样率' && !schema.enum?.length && !contract.sharedChoices[name]?.length ? '采样率（Hz）' : label
  const file = audioFieldContract(contract, path)
  if (file || isImplicitAudioField(name, required)) return
  if (schema.oneOf?.length || schema.anyOf?.length || name === 'segments' && audioSchemaType(schema) === 'string') {
    groups.push({ path, title, required, kind: 'field', value: current, schema })
    return
  }
  const type = audioSchemaType(schema)
  if (type === 'array') {
    if (!hasVisibleControl(contract, schema.items ?? {}, `${path}.0`, true, 0)) return
    groups.push({ path, title, required, kind: 'list', value: current, schema })
    return
  }
  const bounds = type === 'number' || type === 'integer' ? sliderBounds(name, schema) : null
  if (bounds) {
    groups.push({ path, title, required, kind: 'slider', value: current, schema, minimum: bounds.min, maximum: bounds.max })
    return
  }
  const choices = schema.enum ?? contract.sharedChoices[name]
  if (choices?.length && choices.length <= 8) {
    groups.push({
      path, title, required, kind: 'segments', value: current, schema,
      options: choices.map(item => ({ value: item, label: choiceLabel(contract, name, item) }))
    })
    return
  }
  if (choices?.length) {
    groups.push({
      path, title, required, kind: 'menu', value: current, schema,
      options: choices.map(item => ({ value: item, label: choiceLabel(contract, name, item) }))
    })
    return
  }
  groups.push({
    path, title, required,
    kind: type === 'boolean' ? 'switch' : type === 'number' || type === 'integer' ? 'number' : 'text',
    value: current, schema
  })
}

function visit(
  groups: AudioSettingGroup[],
  contract: AudioClientContract,
  value: Record<string, unknown>,
  constraints: Map<string, ParameterConstraint>,
  raw: AudioSchema,
  path: string,
  required: boolean,
  depth: number
) {
  if (depth > 12 || !path || path === contract.promptPath || contract.managedFields.includes(path)) return
  const name = path.split('.').at(-1) ?? path
  if (name.endsWith('_data') || name.endsWith('_base64') || audioFieldContract(contract, path)) return
  const current = audioGet(value, path)
  const constraint = constraints.get(`options.${path}`)
  if (constraint?.forbidden && current == null) return
  required ||= constraint?.required === true
  if (isImplicitAudioField(name, required)) return
  const base = audioSchema(raw, contract.schema)
  const schema = constraint?.choices
    ? { ...base, enum: constraint.choices.filter(option => !base.enum || base.enum.includes(option)) }
    : base
  if (audioSchemaType(schema) === 'object' && schema.properties && !schema.oneOf?.length && !schema.anyOf?.length && !audioFieldContract(contract, path)) {
    for (const [key, child] of Object.entries(schema.properties)) {
      visit(groups, contract, value, constraints, child, `${path}.${key}`, schema.required?.includes(key) ?? false, depth + 1)
    }
    return
  }
  pushGroup(groups, contract, schema, path, required, current)
}

function sortGroups(groups: AudioSettingGroup[]) {
  return groups
    .map((group, index) => ({ group, index }))
    .sort((left, right) => {
      const rank = (group: AudioSettingGroup) => RANK[group.path.split('.').at(-1) ?? ''] ?? (group.kind === 'segments' || group.kind === 'switch' ? 20 : 40)
      return rank(left.group) - rank(right.group) || left.index - right.index
    })
    .map(item => item.group)
}

/** 把当前能力的协议展开成一组设置。容器对象本身不是设置项。 */
export function projectAudioSettingGroups(
  contract: AudioClientContract,
  value: Record<string, unknown>,
  constraints: Map<string, ParameterConstraint>
) {
  const groups: AudioSettingGroup[] = []
  const root = audioSchema(contract.schema, contract.schema)
  for (const [key, child] of Object.entries(root.properties ?? {})) {
    visit(groups, contract, value, constraints, child, key, root.required?.includes(key) ?? false, 0)
  }
  return sortGroups(groups)
}

/** 数组里的一项继续用同一套设置，不再套一层表单框。 */
export function projectAudioSettingSlice(
  contract: AudioClientContract,
  value: Record<string, unknown>,
  schema: AudioSchema,
  path: string,
  constraints: Map<string, ParameterConstraint>
) {
  const groups: AudioSettingGroup[] = []
  visit(groups, contract, value, constraints, schema, path, true, 0)
  return sortGroups(groups)
}

export function studioAudioSettingChipLabel(contract: AudioClientContract, groups: AudioSettingGroup[]) {
  const parts: string[] = []
  for (const name of SUMMARY) {
    const group = groups.find(item => item.path.split('.').at(-1) === name && item.value != null && item.value !== '')
    if (!group) continue
    const matched = group.options?.find(option => option.value === group.value)?.label
    parts.push(matched ?? (name === 'duration' && typeof group.value === 'number' ? `${group.value}秒`
      : name === 'music_length_ms' && typeof group.value === 'number' && group.value % 1000 === 0 ? `${group.value / 1000}秒`
        : choiceLabel(contract, name, group.value)))
    if (parts.length === 4) break
  }
  return parts.length ? parts.join(' · ') : '音频设置'
}
