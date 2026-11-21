'use client'

import { useEffect, useRef, useState } from 'react'
import type { StudioMediaKind } from '@/types/studio'
import {
  drawStudioGeneratingField,
  subscribeStudioGeneratingFrames,
  type StudioGeneratingFieldState
} from '@/utils/studio/studioGeneratingField'

const STAGES: Record<StudioMediaKind, string[]> = {
  image: ['正在理解描述', '正在构建画面', '正在细化光影', '正在完成最后细节'],
  video: ['正在理解描述', '正在推演镜头', '正在渲染帧序列', '正在完成最后细节'],
  audio: ['正在理解描述', '正在凝练声音', '正在调和音色', '正在完成最后细节'],
  text: ['正在理解描述', '正在组织内容', '正在细化内容', '正在完成最后细节']
}

export function studioGeneratingProgress(progress?: number | null, done = false): number | null {
  if (done) return 100
  return typeof progress === 'number' && Number.isFinite(progress) ? Math.max(0, Math.min(95, progress)) : null
}

export function studioGeneratingStage(kind: StudioMediaKind, queued: boolean, progress: number | null, stage?: string): string {
  if (queued) return stage?.trim() || '排队中'
  if (progress !== null && progress >= 92) return STAGES[kind][3]
  // 通用状态不覆盖 Demo 的媒体阶段；真实恢复、提交或本地加工说明仍保留。
  if (stage?.trim() && !['生成中', '模型生成中', '生成段执行中'].includes(stage.trim())) return stage.trim()
  return STAGES[kind][progress === null ? 1 : progress >= 60 ? 2 : progress >= 12 ? 1 : 0]
}

export function useStudioGeneratingReducedMotion(reducedMotion = false): boolean {
  const [systemReduced, setSystemReduced] = useState(false)
  useEffect(() => {
    const preference = matchMedia('(prefers-reduced-motion: reduce)')
    const update = () => setSystemReduced(preference.matches)
    update()
    preference.addEventListener('change', update)
    return () => preference.removeEventListener('change', update)
  }, [])
  return reducedMotion || systemReduced
}

export interface StudioNodeGeneratingPreviewProps {
  mediaKind: StudioMediaKind
  queued: boolean
  awaitingVerification?: boolean
  stage?: string
  progress?: number | null
  startedAt?: string
  darkTheme?: boolean
  reducedMotion?: boolean
  completed?: boolean
  done?: boolean
}

export function StudioNodeGeneratingPreview({
  mediaKind, queued, awaitingVerification = false, stage, progress, startedAt, darkTheme = true, reducedMotion = false,
  completed = false, done = false
}: StudioNodeGeneratingPreviewProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const fieldRef = useRef<StudioGeneratingFieldState | null>(null)
  const reduce = useStudioGeneratingReducedMotion(reducedMotion)
  const [elapsed, setElapsed] = useState(0)
  const normalized = queued || awaitingVerification ? null : studioGeneratingProgress(progress, completed)
  const stageLabel = awaitingVerification ? '提交结果待核实，暂无法预计完成时间' : studioGeneratingStage(mediaKind, queued, normalized, stage)

  useEffect(() => {
    if (queued || awaitingVerification || normalized !== null) return
    const parsed = startedAt ? Date.parse(startedAt) : NaN
    const start = Number.isFinite(parsed) ? parsed : Date.now()
    const update = () => setElapsed(Math.max(0, Math.floor((Date.now() - start) / 1000)))
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [queued, awaitingVerification, normalized, startedAt])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const state: StudioGeneratingFieldState = fieldRef.current ?? {
      ctx, w: 0, h: 0, dpr: 1, dark: darkTheme, kind: mediaKind,
      queued, progress: normalized, clarity: 0, done, reducedMotion: reduce
    }
    Object.assign(state, { kind: mediaKind, dark: darkTheme, queued, progress: normalized, done, reducedMotion: reduce })
    fieldRef.current = state
    // 原稿 is-done 后保留最后一帧作为对焦底图，不继续推进光团和颗粒。
    if (done) return
    let visible = true
    let stopFrames: (() => void) | undefined
    const paint = (time: number) => {
      if (state.w > 0 && state.h > 0 && !document.hidden) drawStudioGeneratingField(state, time)
    }
    const subscribe = () => {
      stopFrames?.(); stopFrames = undefined
      if (visible && !reduce) stopFrames = subscribeStudioGeneratingFrames(paint)
      if (visible && reduce) paint(0)
    }
    const resize = (w: number, h: number) => {
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      state.w = w; state.h = h; state.dpr = dpr
      const width = Math.round(w * dpr), height = Math.round(h * dpr)
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width; canvas.height = height
      }
      if (visible && reduce) paint(0)
    }
    // contentRect 是节点本地尺寸，不受 React Flow 的缩放 transform 影响。
    resize(canvas.clientWidth, canvas.clientHeight)
    const resizeObserver = new ResizeObserver(([entry]) => resize(entry.contentRect.width, entry.contentRect.height))
    resizeObserver.observe(canvas)
    const visibilityObserver = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting; subscribe()
    })
    visibilityObserver.observe(canvas)
    subscribe()
    return () => { stopFrames?.(); resizeObserver.disconnect(); visibilityObserver.disconnect() }
  }, [mediaKind, queued, normalized, darkTheme, reduce, done])

  const clock = `${Math.floor(elapsed / 60)}:${String(elapsed % 60).padStart(2, '0')}`
  const meta = awaitingVerification ? '请勿重复生成' : queued ? '等待开始' : normalized === null ? `已用 ${clock}` : `${Math.round(normalized)}%`
  return (
    <div className={`studio-node-generation${queued ? ' is-queued' : ''}${done ? ' is-done' : ''}${reduce ? ' is-reduced-motion' : ''}`}>
      <canvas ref={canvasRef} className="studio-node-generation__field" aria-hidden="true" />
      <div className="studio-node-generation__info">
        <span className="studio-node-generation__stage"><i aria-hidden="true" /><b>{stageLabel}</b></span>
        <span className="studio-node-generation__meta">{meta}</span>
      </div>
      <div
        className={`studio-node-generation__progress${!queued && normalized === null ? ' is-indeterminate' : ''}`}
        role="progressbar" aria-label="生成进度" aria-valuemin={0} aria-valuemax={100}
        aria-valuenow={queued ? undefined : normalized ?? undefined}
        aria-valuetext={`${stageLabel}，${meta}`}
      >
        <span style={{ transform: normalized === null ? undefined : `scaleX(${normalized / 100})` }} />
      </div>
    </div>
  )
}
