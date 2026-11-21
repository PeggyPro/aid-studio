'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import type { StudioMode } from '@/types/studio'
import { useAccountOnboardingOnce } from '@/hooks/useAccountOnboardingOnce'

const FREE_STEPS = [
  { target: '[data-studio-guide="planning"]', title: '从左下角打开开始面板', text: '点击画布左下角的导航图标，可让 Agent 搭结构，也能直接添加十类节点。' },
  { target: '[data-studio-guide="canvas"]', title: '选中节点就近编辑', text: '单选显示 360px 编辑浮层；多选显示批量工具条。' },
  { target: '[data-studio-guide="liquid-orb"]', title: '从动效球体打开 AI 助手', text: '空画布居中显示；创建节点后停靠右侧，也可拖到任意位置。任务统一放在左侧面板。' }
]

const FLOW_STEPS = [
  { target: '[data-studio-guide="planning"]', title: '从左下角打开流程导航', text: '点击画布左下角的导航图标，按七步打开原流程编辑器；业务实体由服务端数据生成。' },
  { target: '[data-studio-guide="canvas"]', title: '选中流程节点就近操作', text: '节点展示真实业务状态，具体参数和生成记录仍在原流程编辑器中操作。' },
  { target: '[data-studio-guide="conversation"]', title: '从顶栏打开 AI 创作', text: '右侧按作品和剧集隔离对话，并投影真实任务状态。' }
]

type GuideRect = { top: number; right: number; bottom: number; left: number; width: number; height: number }
type GuideStep = (typeof FREE_STEPS)[number]

function stepsFor(studioMode: StudioMode): readonly GuideStep[] {
  return studioMode === 'flow' ? FLOW_STEPS : FREE_STEPS
}

function visibleTargetRect(selector: string): GuideRect | null {
  const target = document.querySelector<HTMLElement>(selector)
  if (!target) return null
  const rect = target.getBoundingClientRect()
  if (!rect.width || !rect.height || rect.right <= 0 || rect.bottom <= 0 || rect.left >= window.innerWidth || rect.top >= window.innerHeight) return null
  const padding = 8
  const left = Math.max(8, rect.left - padding)
  const top = Math.max(8, rect.top - padding)
  const right = Math.min(window.innerWidth - 8, rect.right + padding)
  const bottom = Math.min(window.innerHeight - 8, rect.bottom + padding)
  if (right <= left || bottom <= top) return null
  return { left, top, right, bottom, width: right - left, height: bottom - top }
}

export function StudioFirstVisitGuide({ scopeKey, studioMode = 'free' }: { scopeKey: string; studioMode?: StudioMode }) {
  const steps = stepsFor(studioMode)
  const storageKey = `aid-studio-guide:v1:${scopeKey}`
  const [step, setStep] = useState(-1)
  const [targetRect, setTargetRect] = useState<GuideRect | null>(null)
  const [cardSize, setCardSize] = useState<{ width: number; height: number } | null>(null)
  const cardRef = useRef<HTMLElement | null>(null)
  const open = useCallback(() => setStep(0), [])
  const finish = useAccountOnboardingOnce('studio-free', studioMode === 'free' && step < 0, open)

  useEffect(() => {
    if (studioMode !== 'flow') return
    if (window.localStorage.getItem(storageKey) === 'done') return
    const timer = window.setTimeout(open, 240)
    return () => window.clearTimeout(timer)
  }, [open, storageKey, studioMode])

  useLayoutEffect(() => {
    if (step < 0) return
    const target = stepsFor(studioMode)[step]
    if (!target) return
    const measure = () => {
      setTargetRect(visibleTargetRect(target.target))
      const rect = cardRef.current?.getBoundingClientRect()
      setCardSize(rect ? { width: rect.width, height: rect.height } : null)
    }
    // 首帧必须同步量取目标，避免遮罩先盖住页面而高亮/连线晚一帧出现。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    measure()
    const frame = window.requestAnimationFrame(measure)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    if (cardRef.current) observer?.observe(cardRef.current)
    const node = document.querySelector<HTMLElement>(target.target)
    if (node) observer?.observe(node)
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => {
      window.cancelAnimationFrame(frame)
      observer?.disconnect()
      window.removeEventListener('resize', measure)
      window.removeEventListener('scroll', measure, true)
    }
  }, [step, studioMode])

  if (step < 0) return null
  const close = (status: 'completed' | 'skipped') => {
    if (studioMode === 'flow') window.localStorage.setItem(storageKey, 'done')
    else finish(status)
    setStep(-1)
  }
  const item = steps[step]
  const gutter = 18
  let cardStyle: { left: number; top: number; bottom: 'auto' } | undefined
  if (cardSize && targetRect && targetRect.width < window.innerWidth / 2 && targetRect.height < window.innerHeight / 2) {
    const { width, height } = cardSize
    const clamp = (value: number, size: number, limit: number) => Math.max(gutter, Math.min(value, limit - size - gutter))
    const sideX = targetRect.right + width + 2 * gutter <= window.innerWidth
      ? targetRect.right + gutter : targetRect.left - width - gutter
    const hasSide = sideX >= gutter
    const desiredY = hasSide
      ? targetRect.top + (targetRect.height - height) / 2
      : targetRect.top - height - gutter >= gutter ? targetRect.top - height - gutter : targetRect.bottom + gutter
    cardStyle = {
      left: clamp(hasSide ? sideX : targetRect.left + (targetRect.width - width) / 2, width, window.innerWidth),
      top: clamp(desiredY, height, window.innerHeight),
      bottom: 'auto'
    }
  }
  return (
    <div className="studio-first-guide-layer">
      {targetRect ? (
        <>
          <i className="studio-first-guide__shade" style={{ top: 0, right: 0, height: targetRect.top, left: 0 }} />
          <i className="studio-first-guide__shade" style={{ top: targetRect.top, width: targetRect.left, bottom: window.innerHeight - targetRect.bottom, left: 0 }} />
          <i className="studio-first-guide__shade" style={{ top: targetRect.top, right: 0, bottom: window.innerHeight - targetRect.bottom, left: targetRect.right }} />
          <i className="studio-first-guide__shade" style={{ top: targetRect.bottom, right: 0, bottom: 0, left: 0 }} />
          <i className="studio-first-guide__spotlight" style={{
            top: targetRect.top,
            left: targetRect.left,
            width: targetRect.width,
            height: targetRect.height
          }} />
        </>
      ) : null}
      <aside ref={cardRef} style={cardStyle} className="studio-first-guide" role="dialog" aria-label="Studio 首次使用引导">
        <span>{step + 1} / {steps.length}</span>
        <strong>{item.title}</strong>
        <p>{item.text}</p>
        <div>
          <Button size="small" type="text" onClick={() => close('skipped')}>跳过</Button>
          <Button size="small" type="primary" onClick={() => step === steps.length - 1 ? close('completed') : setStep(step + 1)}>{step === steps.length - 1 ? '完成' : '下一步'}</Button>
        </div>
      </aside>
    </div>
  )
}
