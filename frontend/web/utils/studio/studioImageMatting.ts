'use client'

import { fetchMediaBlob } from '@/utils/mediaFetch'
import { withAppBasePath } from '@/utils/appBasePath'

const IMGLY_DATA_VERSION = '1.7.0'
const pending = new Map<string, { resolve: (blob: Blob) => void; reject: (error: Error) => void }>()
let worker: Worker | null = null
let sequence = 0

function publicPath(): string {
  return new URL(withAppBasePath(`/vendor/imgly/background-removal/${IMGLY_DATA_VERSION}/`), window.location.origin).toString()
}

function getWorker(): Worker | null {
  if (typeof Worker === 'undefined') return null
  if (worker) return worker
  worker = new Worker(new URL('./studioImageMatting.worker.ts', import.meta.url), { type: 'module' })
  worker.onmessage = (event: MessageEvent<{ id: string; blob?: Blob; error?: string }>) => {
    const request = pending.get(event.data.id)
    if (!request) return
    pending.delete(event.data.id)
    if (event.data.blob) request.resolve(event.data.blob)
    else request.reject(new Error(event.data.error || '\u62a0\u56fe\u5931\u8d25'))
  }
  worker.onerror = (event) => {
    const error = new Error(event.message || '\u62a0\u56fe Worker \u8fd0\u884c\u5931\u8d25')
    pending.forEach((request) => request.reject(error))
    pending.clear()
    worker?.terminate()
    worker = null
  }
  return worker
}

async function removeInWorker(blob: Blob): Promise<Blob> {
  const active = getWorker()
  if (!active) {
    const { removeBackground } = await import('@imgly/background-removal')
    return removeBackground(blob, {
      publicPath: publicPath(),
      model: 'isnet',
      device: 'gpu',
      output: { format: 'image/png', quality: 1 }
    })
  }
  const id = `matting-${Date.now()}-${++sequence}`
  return new Promise<Blob>((resolve, reject) => {
    pending.set(id, { resolve, reject })
    active.postMessage({ id, blob, publicPath: publicPath() })
  })
}

/** Browser-side background removal. Model and WASM data are loaded from this deployment's origin. */
export async function removeImageBackgroundToFile(mediaUrl: string, fileBaseName: string): Promise<File> {
  const blob = await fetchMediaBlob(mediaUrl)
  if (!blob) throw new Error('\u56fe\u7247\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216\u5a92\u4f53\u4ee3\u7406\u914d\u7f6e')
  try {
    const result = await removeInWorker(blob)
    return new File([result], `${fileBaseName}-matting.png`, { type: 'image/png' })
  } catch (error: unknown) {
    const detail = String((error as Error)?.message || error || '')
    if (/fetch|network|failed to load|resource/i.test(detail)) {
      throw new Error('\u62a0\u56fe\u6a21\u578b\u8d44\u6e90\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u68c0\u67e5\u9759\u6001\u8d44\u6e90')
    }
    throw error
  }
}
