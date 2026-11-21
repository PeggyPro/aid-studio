import type { StudioLayerSplitRect, StudioVideoClipRange } from '@/types/studio'
import { fetchMediaBlob } from '@/utils/mediaFetch'
import { videoCropPixels } from './studioVideoEditing'

export class StudioVideoExportCompatibilityError extends Error {}

function wavFile(chunks: Uint8Array<ArrayBuffer>[], byteLength: number, channels: number, sampleRate: number, name: string): File {
  const header = new ArrayBuffer(44)
  const view = new DataView(header)
  const word = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i))
  }
  word(0, 'RIFF')
  view.setUint32(4, byteLength + 36, true)
  word(8, 'WAVE')
  word(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, channels, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * channels * 2, true)
  view.setUint16(32, channels * 2, true)
  view.setUint16(34, 16, true)
  word(36, 'data')
  view.setUint32(40, byteLength, true)
  return new File([header, ...chunks], `${name}.wav`, { type: 'audio/wav' })
}

async function extractWavAudio(
  clip: import('@webav/av-cliper').MP4Clip,
  name: string,
  durationLimit: number,
  signal: AbortSignal,
  onProgress: (percent: number) => void
): Promise<File> {
  const { audioChanCount, audioSampleRate } = clip.meta
  const duration = Math.min(clip.meta.duration, durationLimit)
  if (!audioChanCount || audioChanCount > 2 || !audioSampleRate || clip.meta.duration > 300_000_000) {
    throw new StudioVideoExportCompatibilityError('源视频没有可提取音轨，或长度超过浏览器五分钟处理上限')
  }
  // WebAV duplicates mono decoded samples to two channels before tick returns them.
  const channels = 2
  const chunks: Uint8Array<ArrayBuffer>[] = []
  let byteLength = 0
  await clip.tick(0)
  // WebAV resets its decoder when tick jumps more than 100 ms, producing empty audio.
  for (let time = Math.min(100_000, duration); time <= duration; time = Math.min(time + 100_000, duration)) {
    signal.throwIfAborted()
    const result = await clip.tick(time)
    const samples = result.audio
    const count = samples[0]?.length ?? 0
    if ((samples.length !== 0 && samples.length !== channels) || samples.some((channel) => channel.length !== count)) {
      throw new StudioVideoExportCompatibilityError('源视频音轨解码结果不完整')
    }
    const bytes = new Uint8Array(count * channels * 2)
    const view = new DataView(bytes.buffer)
    for (let sample = 0; sample < count; sample++) {
      for (let channel = 0; channel < channels; channel++) {
        const value = Math.max(-1, Math.min(1, samples[channel][sample]))
        view.setInt16((sample * channels + channel) * 2, value < 0 ? Math.round(value * 32768) : Math.round(value * 32767), true)
      }
    }
    byteLength += bytes.length
    chunks.push(bytes)
    onProgress(3 + Math.round(20 * time / duration))
    if (time === duration) break
  }
  if (!byteLength || byteLength > 0x7fffffff) throw new StudioVideoExportCompatibilityError('未能提取有效音轨')
  // MP4Clip.tick resamples decoded audio to WebAV's fixed 48 kHz output rate.
  return wavFile(chunks, byteLength, channels, 48_000, name)
}

export async function exportStudioVideo(input: {
  url: string
  name: string
  range?: StudioVideoClipRange
  cropRect?: StudioLayerSplitRect
  splitAudio?: boolean
  signal: AbortSignal
  onProgress: (percent: number) => void
}) {
  const { signal, onProgress } = input
  signal.throwIfAborted()
  if (!globalThis.VideoEncoder || !globalThis.VideoDecoder || !globalThis.OffscreenCanvas) {
    throw new StudioVideoExportCompatibilityError('当前浏览器需要云端视频处理支持')
  }
  const { MP4Clip, OffscreenSprite, Combinator, Rect } = await import('@webav/av-cliper')
  const clips = new Set<InstanceType<typeof MP4Clip>>()
  let compositor: InstanceType<typeof Combinator> | undefined
  let sprite: InstanceType<typeof OffscreenSprite> | undefined
  const abort = () => compositor?.destroy()
  signal.addEventListener('abort', abort, { once: true })
  try {
    onProgress(2)
    const blob = await fetchMediaBlob(input.url, signal)
    signal.throwIfAborted()
    if (!blob) throw new StudioVideoExportCompatibilityError('视频读取失败，需要云端处理支持')
    const header = new Uint8Array(await blob.slice(0, 32).arrayBuffer())
    if (String.fromCharCode(...header.slice(4, 8)) !== 'ftyp' || blob.size > 256 * 1024 * 1024) {
      throw new StudioVideoExportCompatibilityError('该格式或大小的视频需要云端处理支持')
    }
    let clip = new MP4Clip(blob.stream())
    clips.add(clip)
    await clip.ready
    signal.throwIfAborted()
    const original = { ...clip.meta }
    if (!(original.duration > 0 && original.width >= 2 && original.height >= 2)) throw new Error('无有效视频轨道')
    const crop = videoCropPixels(input.cropRect ?? { x: 0, y: 0, w: 1, h: 1 }, original.width, original.height)
    if (!(await Combinator.isSupported({ width: crop.width, height: crop.height }))) {
      throw new StudioVideoExportCompatibilityError('当前设备无法编码此分辨率，需要云端处理支持')
    }
    const start = input.range ? Math.round(input.range.startSeconds * 1e6) : 0
    const end = input.range ? Math.min(original.duration, Math.round(input.range.endSeconds * 1e6)) : original.duration
    if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start) throw new Error('截取区间无效')
    if (start > 0) {
      const parts = await clip.split(start)
      parts.forEach((part) => clips.add(part))
      clip = parts[1]
    }
    // The MP4 duration can extend beyond its final video sample (for example when
    // the audio track is longer). WebAV split(end) then throws even for a valid
    // selection. The compositor already limits both the sprite and output time.
    signal.throwIfAborted()
    let audioFile: File | undefined
    if (input.splitAudio) {
      const tracks = await clip.splitTrack()
      tracks.forEach((track) => clips.add(track))
      const videoTrack = tracks.find((track) => track.meta.width > 0 && track.meta.height > 0)
      const audioTrack = tracks.find((track) => track.meta.audioChanCount > 0 && track.meta.width === 0)
      if (!videoTrack || !audioTrack) throw new StudioVideoExportCompatibilityError('源视频必须同时包含画面和音轨')
      audioFile = await extractWavAudio(audioTrack, `${input.name}-音轨`, end - start, signal, onProgress)
      clip = videoTrack
    }
    sprite = new OffscreenSprite(clip)
    await sprite.ready
    // 保持原始像素比例，将选择区域平移到输出画布原点，画布边界裁去其余内容。
    sprite.rect = new Rect(-crop.x, -crop.y, original.width, original.height)
    sprite.time = { offset: 0, duration: end - start }
    compositor = new Combinator({ width: crop.width, height: crop.height, fps: 30,
      bitrate: Math.min(20_000_000, Math.max(2_000_000, crop.width * crop.height * 4)),
      ...(original.audioChanCount && !input.splitAudio ? {} : { audio: false as const }) })
    compositor.on('OutputProgress', (progress) => onProgress((input.splitAudio ? 25 : 10) + Math.min(1, Math.max(0, progress)) * (input.splitAudio ? 75 : 90)))
    await compositor.addSprite(sprite, { main: true })
    signal.throwIfAborted()
    const output = await new Response(compositor.output({ maxTime: end - start })).blob()
    signal.throwIfAborted()
    if (!output.size) throw new Error('导出的视频为空')
    onProgress(100)
    return { file: new File([output], `${input.name}.mp4`, { type: 'video/mp4' }), audioFile, width: crop.width, height: crop.height }
  } catch (error) {
    signal.throwIfAborted()
    if (error instanceof StudioVideoExportCompatibilityError) throw error
    throw new StudioVideoExportCompatibilityError('此视频无法在当前设备导出，需要云端处理支持', { cause: error })
  } finally {
    signal.removeEventListener('abort', abort)
    compositor?.destroy()
    sprite?.destroy()
    clips.forEach((clip) => clip.destroy())
  }
}
