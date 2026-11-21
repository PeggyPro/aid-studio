import type { IClip } from '@webav/av-cliper'
import type { SmartEditTimeline, SmartEditTrack } from '@/types/studio'
import { smartEditScene, smartEditTextMotion } from './smartEditPlayback'

function contain(ctx: OffscreenCanvasRenderingContext2D, frame: VideoFrame | ImageBitmap, x: number, y: number, w: number, h: number) {
  const fw = 'displayWidth' in frame ? frame.displayWidth : frame.width
  const fh = 'displayHeight' in frame ? frame.displayHeight : frame.height
  const scale = Math.min(w / fw, h / fh)
  ctx.drawImage(frame, x + (w - fw * scale) / 2, y + (h - fh * scale) / 2, fw * scale, fh * scale)
}
function drawText(ctx: OffscreenCanvasRenderingContext2D, text: string, x: number, y: number, width: number, height: number, size: number, color: string, subtitle = false) {
  ctx.save()
  ctx.beginPath(); ctx.rect(x, y, width, height); ctx.clip()
  ctx.font = `500 ${size}px sans-serif`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  const lines: string[] = []
  for (const paragraph of text.split('\n')) {
    let line = ''
    for (const char of paragraph) { if (line && ctx.measureText(line + char).width > width * 0.96) { lines.push(line); line = '' } line += char }
    lines.push(line)
  }
  lines.forEach((line, index) => {
    const centerY = y + height / 2 + (index - (lines.length - 1) / 2) * size * 1.35
    if (subtitle) { ctx.strokeStyle = '#000'; ctx.lineWidth = size * 0.12; ctx.strokeText(line, x + width / 2, centerY) }
    ctx.fillStyle = color; ctx.fillText(line, x + width / 2, centerY)
  })
  ctx.restore()
}

/** WebAV 的自定义 clip；预览与导出共用场景求值，解码素材已裁切至源 range。 */
export class SmartEditRenderClip implements IClip {
  readonly meta: { width: number; height: number; duration: number }
  readonly ready: IClip['ready']
  private canvas: OffscreenCanvas
  private destroyed = false
  private previousTime = 0
  private frames = new Map<string, VideoFrame | ImageBitmap>()
  constructor(private timeline: SmartEditTimeline, private sources: Map<string, IClip>, width: number, height: number, private hidden: SmartEditTrack[], private signal: AbortSignal) {
    this.meta = { width, height, duration: Math.round(timeline.durationSeconds * 1e6) }
    this.ready = Promise.resolve(this.meta)
    this.canvas = new OffscreenCanvas(width, height)
  }
  async tick(time: number): ReturnType<IClip['tick']> {
    this.signal.throwIfAborted()
    if (this.destroyed || time >= this.meta.duration) return { state: 'done' }
    const scene = smartEditScene(this.timeline, time / 1e6, this.hidden)
    const ctx = this.canvas.getContext('2d')!
    const { width, height } = this.meta
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, width, height)
    for (const [id, frame] of this.frames) {
      if (scene.layers.some((layer) => layer.clip.id === id)) continue
      frame.close()
      this.frames.delete(id)
    }
    const decodedAudio = new Map<string, Float32Array[]>()
    const sampleCount = Math.max(0, Math.round(time * 48000 / 1e6) - Math.round(this.previousTime * 48000 / 1e6))
    const audio = [new Float32Array(sampleCount), new Float32Array(sampleCount)]
    this.previousTime = time
    for (const layer of scene.layers) {
      const id = layer.clip.id
      const source = this.sources.get(id)
      if (!source) throw new Error(`缺少视频素材：${id}`)
      let frame = this.frames.get(id)
      if (!layer.frozen || !frame) {
        const sourceTime = Math.max(0, Math.round((layer.sourceTime - layer.clip.range.startSeconds) * 1e6))
        const result = await source.tick(sourceTime)
        decodedAudio.set(id, result.audio || [])
        // WebAV 每张源帧只交付一次。输出帧率较高时，没有新帧的时间点仍应显示上一帧。
        const next = result.video ?? (frame ? undefined : await this.findFirstFrame(source, sourceTime))
        if (this.destroyed || this.signal.aborted) {
          next?.close()
          this.signal.throwIfAborted()
          return { state: 'done' }
        }
        if (next) {
          frame?.close()
          frame = next
          this.frames.set(id, next)
        }
      }
      if (!frame) throw new Error('视频素材在剪辑入点附近没有可用画面，请调整入点后重试')
      ctx.save(); ctx.globalAlpha = layer.opacity
      ctx.beginPath(); ctx.rect(layer.rect.x * width, layer.rect.y * height, layer.rect.w * width * layer.reveal, layer.rect.h * height); ctx.clip()
      contain(ctx, frame, layer.rect.x * width, layer.rect.y * height, layer.rect.w * width, layer.rect.h * height)
      ctx.restore()
    }
    for (const clip of scene.audio) {
      const source = this.sources.get(clip.id)
      if (!source) throw new Error(`缺少音频素材：${clip.id}`)
      let pcm = decodedAudio.get(clip.id)
      if (!pcm) {
        const result = await source.tick(Math.max(0, time - Math.round(clip.timelineStartSeconds * 1e6)))
        result.video?.close()
        this.signal.throwIfAborted()
        pcm = result.audio || []
      }
      for (let channel = 0; channel < 2; channel++) {
        const samples = pcm[channel] || pcm[0]
        if (!samples) continue
        for (let i = 0; i < Math.min(samples.length, sampleCount); i++) audio[channel][i] += samples[i] * clip.volume
      }
    }
    for (const channel of audio) for (let i = 0; i < channel.length; i++) channel[i] = Math.max(-1, Math.min(1, channel[i]))
    const subtitleSize = width / 40
    scene.subtitles.forEach((cue, index) => drawText(ctx, cue.text, width * 0.08, height * 0.78 - index * subtitleSize * 2, width * 0.84, height * 0.16, subtitleSize, '#fff', true))
    for (const item of scene.texts) {
      const motion = smartEditTextMotion(item, time / 1e6)
      ctx.save(); ctx.globalAlpha = motion.opacity
      drawText(ctx, item.text, item.rect.x * width, (item.rect.y + motion.translateY * item.rect.h) * height, item.rect.w * width, item.rect.h * height, item.fontSize * width / 1280, item.color)
      ctx.restore()
    }
    return { video: new VideoFrame(this.canvas, { timestamp: Math.round(time) }), audio, state: 'success' }
  }
  private async findFirstFrame(source: IClip, time: number) {
    this.signal.throwIfAborted()
    // 仅在入点落于首个可解码帧之前时短距探测。独立解码器避免移动主解码器的音频游标。
    const probe = await source.clone()
    try {
      for (const delta of [16667, 33334, 66667, 100000, 133334]) {
        this.signal.throwIfAborted()
        const target = Math.min(time + delta, probe.meta.duration - 1)
        if (target <= time) break
        const { video } = await probe.tick(target)
        if (video) return video
        if (target === probe.meta.duration - 1) break
      }
    } finally { probe.destroy() }
  }
  async clone(): Promise<this> {
    const sources = new Map<string, IClip>()
    try {
      for (const [id, source] of this.sources) sources.set(id, await source.clone())
      return new SmartEditRenderClip(this.timeline, sources, this.meta.width, this.meta.height, this.hidden, this.signal) as this
    } catch (error) { sources.forEach((source) => source.destroy()); throw error }
  }
  destroy() {
    if (this.destroyed) return
    this.destroyed = true
    this.frames.forEach((frame) => frame.close())
    this.frames.clear()
    this.sources.forEach((source) => source.destroy())
    this.sources.clear()
    this.canvas.width = 1; this.canvas.height = 1
  }
}
