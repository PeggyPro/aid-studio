import type { StudioNodeData } from '@/types/studio'

export type StudioAudioPurpose = NonNullable<StudioNodeData['generationOptions']>['audioPurpose'] & string
export type StudioAudioLanguage = NonNullable<StudioNodeData['generationOptions']>['audioLanguage'] & string
export type StudioAudioSampleRate = NonNullable<NonNullable<StudioNodeData['generationOptions']>['sampleRate']>
export type StudioAudioFormat = NonNullable<StudioNodeData['generationOptions']>['audioFormat'] & string
export type StudioAudioLyricsMode = NonNullable<StudioNodeData['generationOptions']>['lyricsMode'] & string

export interface StudioAudioGenerationSettings {
  audioPurpose: StudioAudioPurpose
  audioLanguage: StudioAudioLanguage
  sampleRate: StudioAudioSampleRate
  audioFormat: StudioAudioFormat
  lyricsMode: StudioAudioLyricsMode
  lyrics: string
}

type AudioOption<T extends string | number> = { value: T; label: string }

export const STUDIO_AUDIO_PURPOSE_OPTIONS: AudioOption<StudioAudioPurpose>[] = [
  { value: 'speech', label: '配音' },
  { value: 'music', label: '音乐' }
]

export const STUDIO_AUDIO_LANGUAGE_OPTIONS: AudioOption<StudioAudioLanguage>[] = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: '英文' }
]

export const STUDIO_AUDIO_SAMPLE_RATE_OPTIONS: AudioOption<StudioAudioSampleRate>[] = [
  { value: 8000, label: '8k' },
  { value: 16000, label: '16k' },
  { value: 24000, label: '24k' },
  { value: 48000, label: '48k' }
]

export const STUDIO_AUDIO_FORMAT_OPTIONS: AudioOption<StudioAudioFormat>[] = [
  { value: 'wav', label: 'wav' },
  { value: 'mp3', label: 'mp3' },
  { value: 'pcm', label: 'pcm' },
  { value: 'ogg_opus', label: 'ogg_opus' }
]

export const STUDIO_AUDIO_LYRICS_MODE_OPTIONS: AudioOption<StudioAudioLyricsMode>[] = [
  { value: 'adaptive', label: '自适应' },
  { value: 'custom', label: '自定义' }
]

const DEFAULTS: StudioAudioGenerationSettings = {
  audioPurpose: 'speech',
  audioLanguage: 'zh',
  sampleRate: 24000,
  audioFormat: 'wav',
  lyricsMode: 'adaptive',
  lyrics: ''
}

function pick<T extends string | number>(value: unknown, options: AudioOption<T>[], fallback: T): T {
  return options.some((option) => option.value === value) ? value as T : fallback
}

/** 把节点上可能残缺或过期的音频参数收敛成面板可直接渲染的一组值。 */
export function resolveStudioAudioGenerationOptions(
  value: StudioNodeData['generationOptions'] | undefined
): StudioAudioGenerationSettings {
  return {
    audioPurpose: pick(value?.audioPurpose, STUDIO_AUDIO_PURPOSE_OPTIONS, DEFAULTS.audioPurpose),
    audioLanguage: pick(value?.audioLanguage, STUDIO_AUDIO_LANGUAGE_OPTIONS, DEFAULTS.audioLanguage),
    sampleRate: pick(value?.sampleRate, STUDIO_AUDIO_SAMPLE_RATE_OPTIONS, DEFAULTS.sampleRate),
    audioFormat: pick(value?.audioFormat, STUDIO_AUDIO_FORMAT_OPTIONS, DEFAULTS.audioFormat),
    lyricsMode: pick(value?.lyricsMode, STUDIO_AUDIO_LYRICS_MODE_OPTIONS, DEFAULTS.lyricsMode),
    lyrics: typeof value?.lyrics === 'string' ? value.lyrics : DEFAULTS.lyrics
  }
}

export function studioAudioParamsChipLabel(value: StudioNodeData['generationOptions'] | undefined): string {
  const resolved = resolveStudioAudioGenerationOptions(value)
  const purpose = STUDIO_AUDIO_PURPOSE_OPTIONS.find((option) => option.value === resolved.audioPurpose)?.label
  const language = STUDIO_AUDIO_LANGUAGE_OPTIONS.find((option) => option.value === resolved.audioLanguage)?.label
  const sampleRate = STUDIO_AUDIO_SAMPLE_RATE_OPTIONS.find((option) => option.value === resolved.sampleRate)?.label
  const format = STUDIO_AUDIO_FORMAT_OPTIONS.find((option) => option.value === resolved.audioFormat)?.label
  return [purpose, language, sampleRate, format].filter(Boolean).join(' · ')
}

/** 歌词输入只属于音乐的自定义模式；配音和自适应都不占输入区。 */
export function studioAudioLyricsInputVisible(value: StudioNodeData['generationOptions'] | undefined): boolean {
  const resolved = resolveStudioAudioGenerationOptions(value)
  return resolved.audioPurpose === 'music' && resolved.lyricsMode === 'custom'
}

/**
 * 写回时带上收敛后的整组音频参数，避免只改采样率时把尚未落库的歌词清掉，
 * 也避免旧的非法值继续留在节点上。
 */
export function patchStudioAudioGenerationOptions(
  current: StudioNodeData['generationOptions'] | undefined,
  patch: Partial<StudioAudioGenerationSettings>
): NonNullable<StudioNodeData['generationOptions']> {
  const resolved = resolveStudioAudioGenerationOptions({ ...current, ...patch })
  return {
    ...current,
    ...resolved
  }
}
