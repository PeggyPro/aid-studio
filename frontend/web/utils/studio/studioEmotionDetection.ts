import { withAppBasePath } from '@/utils/appBasePath'
import { FaceDetector, FilesetResolver } from '@mediapipe/tasks-vision'
import type { StudioEmotionFace } from '@/types/studio'
import { normalizeEmotionRect } from './studioEmotionSession'
import { loadStudioCanvasImage } from './studioCanvasImageLoad'
import {
  selectEmotionFaceRects,
  type StudioEmotionFaceCandidate
} from './studioEmotionFaceValidation'
import { prepareStudioVisionBrowserRuntime } from './studioVisionBrowserRuntime'

let detector: Promise<FaceDetector> | undefined
type FaceProgress = (faces: StudioEmotionFace[]) => void
const jobs = new Map<string, {
  promise: Promise<StudioEmotionFace[]>
  listeners: Set<FaceProgress>
  faces: StudioEmotionFace[]
  settled: boolean
  controller: AbortController
}>()
function getDetector() {
  prepareStudioVisionBrowserRuntime()
  detector ??= FilesetResolver.forVisionTasks(withAppBasePath('/studio/emotion/vision'))
    .then(async (files) => {
      // A static server may not gzip WASM. Load the reproducibly compressed asset
      // ourselves, retaining the original runtime for older browsers/deployments.
      const preload = document.createElement('link')
      preload.rel = 'preload'
      preload.as = 'script'
      preload.href = files.wasmLoaderPath
      document.head.append(preload)
      let binaryUrl: string | undefined
      try {
        if (typeof DecompressionStream !== 'undefined') {
          try {
            const response = await fetch(`${files.wasmBinaryPath}.gz`)
            if (!response.ok) throw new Error('Compressed WASM unavailable')
            const bytes = new Uint8Array(await response.arrayBuffer())
            // Some servers use Content-Encoding and transparently decode .gz.
            const binary = bytes[0] === 0x1f && bytes[1] === 0x8b
              ? await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
              : bytes.buffer
            const header = new Uint8Array(binary, 0, Math.min(4, binary.byteLength))
            if (header.length !== 4 || header[0] !== 0 || header[1] !== 0x61 || header[2] !== 0x73 || header[3] !== 0x6d) {
              throw new Error('Invalid compressed WASM')
            }
            binaryUrl = URL.createObjectURL(new Blob([binary], { type: 'application/wasm' }))
            files = { ...files, wasmBinaryPath: binaryUrl }
          } catch { /* Plain local WASM remains a compatible fallback. */ }
        }
        return await FaceDetector.createFromOptions(files, {
          baseOptions: {
            modelAssetPath: withAppBasePath('/studio/emotion/face-detector.tflite'),
            delegate: 'CPU'
          },
          runningMode: 'IMAGE',
          minDetectionConfidence: 0.5
        })
      } finally {
        if (binaryUrl) URL.revokeObjectURL(binaryUrl)
        preload.remove()
      }
    })
    .catch((error) => {
      detector = undefined
      throw error
    })
  return detector
}

function toFaces(candidates: StudioEmotionFaceCandidate[]): StudioEmotionFace[] {
  return selectEmotionFaceRects(candidates)
    .sort((a, b) => a.x - b.x || a.y - b.y)
    .slice(0, 20)
    .map((rect, index) => ({
      id: `auto-${index}`, label: `角色${index + 1}`, rect,
      source: 'auto', affinity: 0, arousal: 0
    }))
}

async function scanLocalEmotionFaces(
  sourceUrl: string,
  onProgress: FaceProgress,
  signal: AbortSignal
): Promise<StudioEmotionFace[]> {
  // The same OSS/CDN proxy + Blob decode as crop/mask tools; a visible <img>
  // alone does not guarantee CORS-readable pixels for inference.
  // Initialize WASM while the image is downloaded and decoded.
  const engineReady = getDetector()
  void engineReady.catch(() => undefined)
  const image = await loadStudioCanvasImage(sourceUrl, signal)
  try {
    if (image.width < 1 || image.height < 1) throw new Error('无效的图片尺寸')
    const engine = await engineReady
    signal.throwIfAborted()
    const canvas = document.createElement('canvas')
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('Canvas unavailable')
    const candidates: StudioEmotionFaceCandidate[] = []
    // Overlapping tiles preserve small faces in full-body and multi-person images.
    const side = Math.min(image.width, image.height) * 0.8
    const steps = (extent: number) => Math.min(6, Math.max(1, Math.ceil((extent - side) / (side * 0.65))))
    const columns = steps(image.width),
      rows = steps(image.height)
    const regions = [{ x: 0, y: 0, w: 1, h: 1 }]
    for (let y = 0; y <= rows; y++)
      for (let x = 0; x <= columns; x++) {
        regions.push({
          x: ((image.width - side) * x) / columns / image.width,
          y: ((image.height - side) * y) / rows / image.height,
          w: side / image.width,
          h: side / image.height
        })
      }
    // Scan a finer scale even after a large face is found: other people may be distant.
    const fallbackScales = [0.35, 0.18]
    let fallbackIndex = 0
    let lastFaces = ''
    for (let regionId = 0; regionId < regions.length; regionId++) {
      signal.throwIfAborted()
      const region = regions[regionId]
      const width = image.width * region.w,
        height = image.height * region.h
      const scale = Math.min(1, 320 / Math.max(width, height))
      canvas.width = Math.max(1, Math.round(width * scale))
      canvas.height = Math.max(1, Math.round(height * scale))
      context.drawImage(
        image.drawable,
        image.width * region.x,
        image.height * region.y,
        width,
        height,
        0,
        0,
        canvas.width,
        canvas.height
      )
      for (const item of engine.detect(canvas)?.detections ?? []) {
        const b = item.boundingBox
        if (!b) continue
        const rect = normalizeEmotionRect({
          x: region.x + (b.originX / canvas.width) * region.w,
          y: region.y + (b.originY / canvas.height) * region.h,
          w: (b.width / canvas.width) * region.w,
          h: (b.height / canvas.height) * region.h
        })
        if (!rect) continue
        const landmarks = (item.keypoints ?? []).map((point) => ({
          x: region.x + point.x * region.w,
          y: region.y + point.y * region.h
        }))
        candidates.push({
          rect,
          landmarks,
          score: item.categories[0]?.score ?? 0,
          regionId,
          imageAspectRatio: image.width / image.height
        })
      }
      const faces = toFaces(candidates)
      const serialized = JSON.stringify(faces)
      if (faces.length && serialized !== lastFaces) {
        lastFaces = serialized
        onProgress(faces)
      }
      if (regionId === regions.length - 1 && (fallbackIndex === 0 || !faces.length) && fallbackIndex < fallbackScales.length) {
        const fineSide = Math.min(image.width, image.height) * fallbackScales[fallbackIndex++]
        const fineColumns = Math.min(9, Math.ceil((image.width - fineSide) / (fineSide * 0.7)))
        const fineRows = Math.min(9, Math.ceil((image.height - fineSide) / (fineSide * 0.7)))
        for (let y = 0; y <= fineRows; y++) for (let x = 0; x <= fineColumns; x++) {
          regions.push({
            x: ((image.width - fineSide) * x) / fineColumns / image.width,
            y: ((image.height - fineSide) * y) / fineRows / image.height,
            w: fineSide / image.width, h: fineSide / image.height
          })
        }
      }
      // Yield a paint between passes so a detected face can be selected immediately.
      await new Promise<void>((resolve) => setTimeout(resolve, 0))
    }
    return toFaces(candidates)
  } finally {
    image.dispose()
  }
}

/** Share image reads/inference across remounts; cache only successful face detections. */
export function detectLocalEmotionFaces(sourceUrl: string, signal?: AbortSignal, onProgress?: FaceProgress): Promise<StudioEmotionFace[]> {
  signal?.throwIfAborted()
  let job = jobs.get(sourceUrl)
  if (!job) {
    const listeners = new Set<FaceProgress>()
    const controller = new AbortController()
    const created = { promise: Promise.resolve([] as StudioEmotionFace[]), listeners, faces: [] as StudioEmotionFace[], settled: false, controller }
    created.promise = scanLocalEmotionFaces(sourceUrl, (faces) => {
      created.faces = faces
      for (const listener of listeners) listener(faces)
    }, controller.signal).then((faces) => {
      created.faces = faces
      created.settled = true
      if (!faces.length && jobs.get(sourceUrl) === created) jobs.delete(sourceUrl)
      // Keep a small bounded cache; in-flight jobs must retain their single-flight identity.
      while (jobs.size > 8) {
        const entry = [...jobs].find(([, value]) => value.settled)
        if (!entry) break
        jobs.delete(entry[0])
      }
      return faces
    }).catch((error: unknown) => { if (jobs.get(sourceUrl) === created) jobs.delete(sourceUrl); throw error })
    jobs.set(sourceUrl, created)
    job = created
  }
  const current = job
  return new Promise((resolve, reject) => {
    const progress = (faces: StudioEmotionFace[]) => { if (!signal?.aborted) onProgress?.(structuredClone(faces)) }
    const cleanup = () => {
      current.listeners.delete(progress)
      signal?.removeEventListener('abort', abort)
      if (!current.settled && !current.listeners.size) setTimeout(() => {
        // Give a Strict Mode remount the chance to rejoin this same request.
        if (current.settled || current.listeners.size) return
        if (jobs.get(sourceUrl) === current) jobs.delete(sourceUrl)
        current.controller.abort()
      }, 0)
    }
    const abort = () => { cleanup(); reject(signal?.reason ?? new DOMException('Aborted', 'AbortError')) }
    current.listeners.add(progress)
    signal?.addEventListener('abort', abort, { once: true })
    if (current.faces.length) progress(current.faces)
    void current.promise.then((faces) => { cleanup(); if (!signal?.aborted) resolve(structuredClone(faces)) },
      (error: unknown) => { cleanup(); reject(error) })
  })
}
