import { resolveStudioVideoFilmstripSource } from '@/utils/mediaFetch'
import { clampVideoFrameTime } from '@/utils/videoFrameCapture'
import { diagnosticFetch } from '@/utils/errorDiagnostics'
import { isRejectedMediaContentType, isUsableMediaBlob } from '@/utils/mediaBlobGuard'

export type StudioFrameCapturePhase = 'reading' | 'seeking' | 'encoding'

/** The decoder can seek directly when the source supports Range; tail metadata may require a full read. */
export async function captureStudioCanvasVideoFrame(sourceUrl: string, targetSeconds: number, fileName: string,
  onPhase?: (phase: StudioFrameCapturePhase, progress?: number) => void): Promise<File> {
  const source = resolveStudioVideoFilmstripSource(sourceUrl)
  if (!source.src) throw new Error('视频地址无效')
  const deadline = Date.now() + 180_000
  const prepared = await prepareFrameSource(source.src, source.crossOrigin, targetSeconds <= 0, deadline - Date.now(), onPhase)
  try {
    if (Date.now() >= deadline) throw new Error('截帧超时，请检查视频是否可以读取后重试')
    return await captureDecodedFrame(prepared.url, prepared.crossOrigin,
      targetSeconds, fileName, deadline - Date.now(), onPhase)
  } finally {
    prepared.release?.()
  }
}

/** A 200 response to a Range probe cannot be safely random-seeked by the browser. Read it once, then decode a local URL. */
async function prepareFrameSource(url: string, crossOrigin: 'anonymous' | undefined, firstFrame: boolean, timeoutMs: number,
  onPhase?: (phase: StudioFrameCapturePhase, progress?: number) => void): Promise<{url:string;crossOrigin?:'anonymous';release?:()=>void}> {
  if (url.startsWith('blob:') || url.startsWith('data:')) return { url, crossOrigin }
  const controller = new AbortController()
  const abort = (detail: string) => controller.abort(new Error(detail))
  const timer = setTimeout(() => abort('截帧超时，请检查视频是否可以读取后重试'), Math.max(1, timeoutMs))
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  const keepAlive = () => {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(() => abort('视频读取长时间没有进展，请重试'), 30_000)
  }
  try {
    keepAlive()
    onPhase?.('reading')
    const response = await diagnosticFetch(url, { headers: { Range: 'bytes=0-65535' }, signal: controller.signal,
      credentials: crossOrigin ? 'omit' : 'same-origin' })
    keepAlive()
    if (!response.ok) throw new Error('视频读取失败，请检查源视频后重试')
    if (response.status === 206) {
      await response.body?.cancel()
      return { url, crossOrigin }
    }
    if (isRejectedMediaContentType(response.headers.get('content-type')) || !response.body) {
      throw new Error('视频服务未返回可读取的视频')
    }
    reader = response.body.getReader()
    const chunks: BlobPart[] = []
    const total = Number(response.headers.get('content-length')) || 0
    let received = 0
    let reportedPercent = -1
    const prefix = firstFrame ? new Uint8Array(65_536) : null
    let prefixLength = 0
    while (true) {
      const result = await reader.read()
      controller.signal.throwIfAborted()
      if (result.done) break
      if (prefix && prefixLength < prefix.length) {
        const part = result.value.subarray(0, prefix.length - prefixLength)
        prefix.set(part, prefixLength)
        prefixLength += part.length
        if (hasFrontMovieMetadata(prefix.subarray(0, prefixLength))) {
          await reader.cancel()
          return { url, crossOrigin }
        }
      }
      chunks.push(result.value as BlobPart)
      received += result.value.byteLength
      keepAlive()
      const percent = total > 0 ? Math.min(100, Math.floor(received * 100 / total)) : -1
      if (percent >= 0 && percent !== reportedPercent) {
        reportedPercent = percent
        onPhase?.('reading', percent / 100)
      }
    }
    const blob = new Blob(chunks, { type: response.headers.get('content-type') || 'video/mp4' })
    if (!(await isUsableMediaBlob(blob))) throw new Error('视频内容无效，请检查源文件')
    controller.signal.throwIfAborted()
    const localUrl = URL.createObjectURL(blob)
    return { url: localUrl, release: () => URL.revokeObjectURL(localUrl) }
  } catch (error) {
    if (controller.signal.aborted) throw controller.signal.reason
    throw error
  } finally {
    clearTimeout(timer)
    clearTimeout(idleTimer)
    reader?.releaseLock()
    controller.abort()
  }
}

/** A first frame with front MP4 metadata can be decoded sequentially even if Range is unavailable. */
function hasFrontMovieMetadata(prefix: Uint8Array): boolean {
  const view = new DataView(prefix.buffer, prefix.byteOffset, prefix.byteLength)
  for (let offset = 0; offset + 8 <= prefix.length;) {
    const size = view.getUint32(offset)
    const type = String.fromCharCode(...prefix.subarray(offset + 4, offset + 8))
    if (size < 8) return false
    if (type === 'moov') return true
    if (type === 'mdat' || offset + size > prefix.length) return false
    offset += size
  }
  return false
}

function captureDecodedFrame(url: string, crossOrigin: 'anonymous' | undefined,
  targetSeconds: number, fileName: string, timeoutMs: number,
  onPhase?: (phase: StudioFrameCapturePhase) => void): Promise<File> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    video.preload = 'auto'
    video.muted = true
    video.playsInline = true
    if (crossOrigin) video.crossOrigin = crossOrigin
    let settled = false
    let positioned = false
    const timer = setTimeout(() => finish(new Error('截帧超时，请检查视频是否可以读取后重试')), Math.max(1, timeoutMs))
    let idleTimer: ReturnType<typeof setTimeout> | undefined
    const keepAlive = () => {
      clearTimeout(idleTimer)
      idleTimer = setTimeout(() => finish(new Error('视频读取长时间没有进展，请重试')), 30_000)
    }
    const finish = (error?: Error, file?: File) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearTimeout(idleTimer)
      video.onloadedmetadata = null
      video.onloadeddata = null
      video.onseeked = null
      video.onerror = null
      video.onprogress = null
      video.pause()
      video.removeAttribute('src')
      video.load()
      if (error) reject(error)
      else if (file) resolve(file)
      else reject(new Error('截帧没有返回图像'))
    }
    const capture = () => {
      if (settled || !positioned || video.readyState < 2) return
      if (!video.videoWidth || !video.videoHeight) { finish(new Error('视频画面尺寸无效')); return }
      const canvas = document.createElement('canvas')
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      try {
        const context = canvas.getContext('2d')
        if (!context) throw new Error('当前浏览器无法读取视频画面')
        context.drawImage(video, 0, 0)
        // PNG encoding has one authoritative completion; duplicate media events are ignored.
        positioned = false
        onPhase?.('encoding')
        canvas.toBlob(blob => {
          if (settled) return
          if (!blob) { finish(new Error('视频画面编码失败')); return }
          const name = /\.png$/i.test(fileName) ? fileName : `${fileName}.png`
          finish(undefined, new File([blob], name, { type: 'image/png' }))
        }, 'image/png')
      } catch (error) { finish(error instanceof Error ? error : new Error('视频跨域读取失败')) }
    }
    video.onloadedmetadata = () => {
      if (!Number.isFinite(video.duration) || video.duration <= 0) { finish(new Error('视频时长无效')); return }
      const target = clampVideoFrameTime(targetSeconds, video.duration)
      keepAlive()
      onPhase?.('seeking')
      if (Math.abs(video.currentTime - target) < .000001) { positioned = true; capture() }
      else video.currentTime = target
    }
    video.onseeked = () => { positioned = true; capture() }
    video.onloadeddata = capture
    video.onerror = () => finish(new Error('视频解码失败，请检查源视频'))
    video.onprogress = keepAlive
    keepAlive()
    onPhase?.('reading')
    video.src = url
    video.load()
  })
}
