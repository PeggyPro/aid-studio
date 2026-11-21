'use client'

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { StudioNodeGeneratingPreview, useStudioGeneratingReducedMotion, type StudioNodeGeneratingPreviewProps } from './StudioNodeGeneratingPreview'

type Phase = 'idle' | 'generating' | 'waiting' | 'revealing'

/** 保留同一显影画布到权威成功且真实媒体就绪，失败/取消直接交还原有结果或错误入口。 */
export function StudioNodeGenerationSurface({
  enabled, active, successful, resultKey, children, ...preview
}: StudioNodeGeneratingPreviewProps & {
  enabled: boolean
  active: boolean
  successful: boolean
  resultKey: string
  children: ReactNode
}) {
  const reduce = useStudioGeneratingReducedMotion(preview.reducedMotion)
  const resultRef = useRef<HTMLDivElement>(null)
  const generating = enabled && active
  const [cycle, setCycle] = useState({ active: generating, phase: generating ? 'generating' as Phase : 'idle' as Phase, resultKey })
  // 在 React 提交前协调生命周期，避免成功首帧先显示结果再变回模糊。
  if (cycle.active !== generating) {
    setCycle({ active: generating, resultKey, phase: generating ? 'generating' : enabled && successful && cycle.phase !== 'idle' ? 'waiting' : 'idle' })
  } else if (cycle.resultKey !== resultKey) {
    setCycle({ ...cycle, resultKey, phase: cycle.phase === 'revealing' ? 'waiting' : cycle.phase })
  }
  const phase = cycle.phase
  const overlay = enabled && (generating || (successful && phase !== 'idle'))

  useEffect(() => {
    if (phase !== 'waiting' || !successful || generating) return
    const result = resultRef.current
    if (!result) return
    let disposed = false, ready = false, minimumPassed = reduce
    let firstFrame = 0, secondFrame = 0
    const preloads = new Map<HTMLVideoElement, HTMLVideoElement['preload']>()
    const reveal = () => {
      if (disposed || ready) return
      const media = Array.from(result.querySelectorAll<HTMLImageElement | HTMLMediaElement>('img,video,audio'))
      // 预览默认只取元数据；揭晓必须拿到首帧，不能等待被遮罩挡住的 hover/play。
      for (const item of media) {
        if (item instanceof HTMLVideoElement && item.readyState < 2 && !preloads.has(item)) {
          preloads.set(item, item.preload)
          item.preload = 'auto'
          item.load()
        }
      }
      if (!minimumPassed) return
      // 网络预览错误由原预览组件呈现，不能把成功任务误标为生成失败，也不能永久留在生成态。
      if (media.some(item => item instanceof HTMLImageElement ? item.complete && !item.naturalWidth : !!item.error)) {
        ready = true; setCycle(value => ({ ...value, phase: 'idle' })); return
      }
      if (media.some(item => item instanceof HTMLImageElement ? !item.complete : item.readyState < (item instanceof HTMLVideoElement ? 2 : 1))) return
      ready = true
      firstFrame = requestAnimationFrame(() => {
        secondFrame = requestAnimationFrame(() => {
          if (!disposed) setCycle(value => ({ ...value, phase: 'revealing' }))
        })
      })
    }
    const timer = window.setTimeout(() => { minimumPassed = true; reveal() }, reduce ? 0 : 450)
    const observer = new MutationObserver(reveal)
    observer.observe(result, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] })
    for (const event of ['load', 'loadeddata', 'loadedmetadata', 'error']) result.addEventListener(event, reveal, true)
    reveal()
    return () => {
      disposed = true; window.clearTimeout(timer); cancelAnimationFrame(firstFrame); cancelAnimationFrame(secondFrame)
      observer.disconnect()
      for (const event of ['load', 'loadeddata', 'loadedmetadata', 'error']) result.removeEventListener(event, reveal, true)
      for (const [video, preload] of preloads) video.preload = preload
    }
  }, [phase, successful, generating, resultKey, reduce])

  useEffect(() => {
    if (phase !== 'revealing') return
    const timer = window.setTimeout(() => setCycle(value => ({ ...value, phase: 'idle' })), reduce ? 0 : 1100)
    return () => window.clearTimeout(timer)
  }, [phase, reduce])

  return (
    <div className={`studio-node-card__media-stage${overlay ? ' studio-node-generation-surface' : ''}${phase === 'revealing' ? ' is-done' : ''}${reduce ? ' is-reduced-motion' : ''}`} data-generation-phase={phase}>
      {overlay ? <StudioNodeGeneratingPreview {...preview} reducedMotion={reduce} completed={!generating && successful} done={phase === 'revealing'} /> : null}
      {!generating ? (
        <div ref={resultRef} className={overlay ? 'studio-node-generation__result' : 'studio-node-generation__content'} inert={overlay} aria-hidden={overlay || undefined}>
          {children}
        </div>
      ) : null}
    </div>
  )
}
