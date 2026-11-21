'use client'

import { useEffect, useState } from 'react'
import { isRejectedMediaContentType } from '@/utils/mediaBlobGuard'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

const cache = new Map<string, number[]>()
let decoders = 0
const waiting: Array<() => void> = []
async function acquireDecoder() {
  if (decoders >= 2) await new Promise<void>((resolve) => waiting.push(resolve))
  else decoders += 1
  return () => { const next = waiting.shift(); if (next) next(); else decoders -= 1 }
}
async function previewBytes(url: string): Promise<ArrayBuffer | null> {
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000)
  const limit = 32 * 1024 * 1024
  try {
    const response = await fetch(url, { signal: controller.signal, credentials: 'omit' })
    if (!response.ok || isRejectedMediaContentType(response.headers.get('content-type')) || Number(response.headers.get('content-length')) > limit || !response.body) { controller.abort(); return null }
    const reader = response.body.getReader(), chunks: Uint8Array[] = []
    let size = 0
    try {
      while (true) {
        const part = await reader.read()
        if (part.done) break
        size += part.value.byteLength
        if (size > limit) { await reader.cancel(); return null }
        chunks.push(part.value)
      }
    } finally { reader.releaseLock() }
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
    return bytes.buffer
  } finally { clearTimeout(timeout) }
}
/** Small preview cache only; long media remains editable using metadata and server processing. */
export function useStudioAudioPeaks(url: string, duration: number, visible: boolean) {
  const [result, setResult] = useState<{ url: string; peaks: number[] }>({ url: '', peaks: [] })
  useEffect(() => {
    if (!url || !visible || !duration || duration > 120) return
    let active = true
    void runStudioRequestSingleFlight(`audio-peaks:${url}`, async () => {
      if (cache.has(url)) return cache.get(url)!
      const release = await acquireDecoder()
      let context: AudioContext | undefined
      try {
        const bytes = await previewBytes(url)
        if (!bytes) return []
        context = new AudioContext()
        const buffer = await context.decodeAudioData(bytes)
        const peaks = Array.from({ length: 96 }, (_, index) => {
          const start = Math.floor(index * buffer.length / 96), end = Math.floor((index + 1) * buffer.length / 96)
          let peak = 0
          for (let channel = 0; channel < buffer.numberOfChannels; channel += 1) {
            const samples = buffer.getChannelData(channel)
            for (let i = start; i < end; i += Math.max(1, Math.floor((end - start) / 256))) peak = Math.max(peak, Math.abs(samples[i]))
          }
          return peak
        })
        if (cache.size >= 12) cache.delete(cache.keys().next().value!)
        cache.set(url, peaks)
        return peaks
      } finally { try { await context?.close() } finally { release() } }
    }).then((peaks) => { if (active) setResult({ url, peaks }) }).catch(() => {})
    return () => { active = false }
  }, [duration, url, visible])
  return result.url === url ? result.peaks : []
}
