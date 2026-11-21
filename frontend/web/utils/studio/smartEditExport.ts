import type { IClip } from '@webav/av-cliper'
import type { SmartEditTimeline, SmartEditTrack } from '@/types/studio'
import { fetchMediaBlob } from '@/utils/mediaFetch'
import { smartEditMediaClips, timelineDurationSeconds } from './studioSmartEdit'
import { StudioVideoExportCompatibilityError } from './studioVideoExport'
import { smartEditDecodedClips } from './smartEditComposeRoute'
import { SmartEditRenderClip } from './smartEditRender'

export function validateSmartEditExport(timeline: SmartEditTimeline) {
  const clips = smartEditMediaClips(timeline)
  if (!clips.length && !timeline.texts.length && !timeline.subtitles.length) throw new Error('时间轴为空，请先添加素材')
  if (clips.some((clip) => clip.invalid || !clip.sourceUrl)) throw new Error('存在失效素材，请修复或删除后再导出')
  if (clips.some((clip) => !Number.isFinite(clip.timelineStartSeconds) || clip.timelineStartSeconds < 0 || !Number.isFinite(clip.range.startSeconds) || clip.range.startSeconds < 0 || !Number.isFinite(clip.range.endSeconds) || clip.range.endSeconds <= clip.range.startSeconds || clip.range.endSeconds > clip.sourceDurationSeconds)) throw new Error('素材裁切区间无效')
}
export function smartEditOutputSize(aspectRatio = '16:9') {
  const sizes: Record<string, [number, number]> = { '16:9': [1280, 720], '9:16': [720, 1280], '1:1': [1080, 1080], '4:3': [960, 720] }
  const [width, height] = sizes[aspectRatio] || sizes['16:9']
  return { width, height }
}

async function finalizeSmartEditMp4(rendered: Blob, signal: AbortSignal) {
  signal.throwIfAborted()
  const { BlobSource, BufferTarget, Conversion, Input, MP4, Mp4OutputFormat, Output } = await import('mediabunny')
  signal.throwIfAborted()
  const input = new Input({ formats: [MP4], source: new BlobSource(rendered) })
  const target = new BufferTarget()
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target })
  try {
    // WebAV writes fragments. The downloadable and publishable result needs a complete index at the start.
    const conversion = await Conversion.init({ input, output, copy: { mode: 'forced' } })
    signal.throwIfAborted()
    if (!conversion.isValid || conversion.discardedTracks.length || !conversion.utilizedTracks.some((track) => track.isVideoTrack())) {
      throw new Error('成片音视频轨道无法完整封装')
    }
    const cancel = () => { void conversion.cancel().catch(() => undefined) }
    signal.addEventListener('abort', cancel, { once: true })
    try {
      await conversion.execute()
      signal.throwIfAborted()
      if (!target.buffer?.byteLength) throw new Error('成片封装结果为空')
      return target.buffer
    } finally {
      signal.removeEventListener('abort', cancel)
    }
  } catch (cause) {
    signal.throwIfAborted()
    throw new Error('成片 MP4 封装失败，请重试导出', { cause })
  } finally {
    input.dispose()
  }
}

export async function exportSmartEditTimeline(timeline: SmartEditTimeline, options: { signal: AbortSignal; onProgress: (percent: number) => void; name?: string; width?: number; height?: number; hiddenTracks?: SmartEditTrack[] }) {
  validateSmartEditExport(timeline)
  const { signal, onProgress, width = 1280, height = 720 } = options
  signal.throwIfAborted()
  const unsupported = () => new StudioVideoExportCompatibilityError('当前浏览器需要可编码环境才能导出成片')
  if (!globalThis.VideoEncoder || !globalThis.VideoDecoder || !globalThis.AudioEncoder || !globalThis.OffscreenCanvas) throw unsupported()
  const { MP4Clip, AudioClip, Combinator, OffscreenSprite } = await import('@webav/av-cliper')
  if (!(await Combinator.isSupported({ width, height }))) throw unsupported()
  const owned = new Set<IClip>()
  const sources = new Map<string, IClip>()
  let compositor: InstanceType<typeof Combinator> | undefined
  let sprite: InstanceType<typeof OffscreenSprite> | undefined
  let rendered: Blob
  const abort = () => { compositor?.destroy(); owned.forEach((clip) => clip.destroy()) }
  signal.addEventListener('abort', abort, { once: true })
  try {
    const media = smartEditDecodedClips(timeline, options.hiddenTracks || [])
    for (let index = 0; index < media.length; index++) {
      const item = media[index]
      signal.throwIfAborted()
      const blob = await fetchMediaBlob(item.sourceUrl, signal)
      if (!blob) throw new Error('素材下载失败，请检查权限与网络后重试')
      if (blob.size > 512 * 1024 * 1024) throw new Error('单段素材超过 512 MB，请裁短后导出，或使用 FCPXML 在桌面剪辑软件中处理')
      const video = 'track' in item
      if (video) {
        const header = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
        if (String.fromCharCode(...header.slice(4, 8)) !== 'ftyp') throw new Error('本地合成仅支持 MP4 视频源，请转换素材或导出 FCPXML')
      }
      let source: IClip = video ? new MP4Clip(blob.stream()) : new AudioClip(blob.stream())
      owned.add(source)
      await source.ready
      signal.throwIfAborted()
      const start = Math.round(item.range.startSeconds * 1e6), end = Math.round(item.range.endSeconds * 1e6)
      if (end > source.meta.duration + 100000) throw new Error('素材实际时长已变化，请重新添加素材')
      if (start > 0) { const parts = await source.split!(start); parts.forEach((part) => owned.add(part)); source = parts[1] }
      if (end - start < source.meta.duration - 1) { const parts = await source.split!(end - start); parts.forEach((part) => owned.add(part)); source = parts[0] }
      sources.set(item.id, source)
      onProgress(5 + (index + 1) / Math.max(1, media.length) * 20)
    }
    const durationSeconds = timelineDurationSeconds(timeline)
    const renderer = new SmartEditRenderClip({ ...timeline, durationSeconds }, sources, width, height, options.hiddenTracks || [], signal)
    owned.add(renderer)
    sprite = new OffscreenSprite(renderer)
    await sprite.ready
    sprite.time = { offset: 0, duration: Math.round(durationSeconds * 1e6) }
    compositor = new Combinator({ width, height, fps: 30, bitrate: 6_000_000, bgColor: '#000' })
    compositor.on('OutputProgress', (progress) => onProgress(25 + Math.min(1, Math.max(0, progress)) * 70))
    await compositor.addSprite(sprite, { main: true })
    signal.throwIfAborted()
    rendered = await new Response(compositor.output({ maxTime: Math.round(durationSeconds * 1e6) })).blob()
  } finally {
    signal.removeEventListener('abort', abort)
    compositor?.destroy()
    sprite?.destroy()
    owned.forEach((clip) => clip.destroy())
    owned.clear()
  }
  signal.throwIfAborted()
  if (!rendered.size) throw new Error('导出结果为空')
  onProgress(95)
  const output = await finalizeSmartEditMp4(rendered, signal)
  onProgress(100)
  return new File([output], `${(options.name || '剪辑成片').replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')}.mp4`, { type: 'video/mp4' })
}
