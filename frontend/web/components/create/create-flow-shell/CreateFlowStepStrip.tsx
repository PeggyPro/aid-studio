'use client'

/**
 * 顶部流程条。业务状态、禁用和跳转沿用原参数，外观是一条进度轨道：
 * 当前步骤用滑块标出，完成显示勾，生成中显示进度环。
 */

import { Tooltip } from 'antd'
import { useEffect, useLayoutEffect, useRef } from 'react'
import type { CreationStep } from '~/types'
import type { CreationFlowStepMeta } from '~/utils/createFlowStepMeta'

const checkIcon = (
  <svg className="flow-step__check" viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="10" cy="10" r="9" fill="currentColor" />
    <path d="m5.2 10.1 3.1 3.2 6.5-6.6" fill="none" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

const ringIcon = (
  <svg className="flow-step__ring" viewBox="0 0 20 20" aria-hidden="true">
    <circle className="flow-step__ring-track" cx="10" cy="10" r="8.5" />
    <circle className="flow-step__ring-arc" cx="10" cy="10" r="8.5" />
  </svg>
)

export interface CreateFlowStepStripProps {
  displaySteps: CreationFlowStepMeta[]
  flowStepIndex: number
  stepStatus: Array<'completed' | 'pending' | 'disabled' | 'active'>
  unlockedStepIndex: number
  stepRealIndex: (stepKey: CreationStep) => number
  /** 保留给调用方，进度铺底改由 unlockedStepIndex 决定。 */
  isConnectorTrailDone: (index: number) => boolean
  isPillDisabled: (index: number) => boolean
  publishGuideActive?: boolean
  publishGuideTooltipOpen?: boolean
  isPreviewStep?: boolean
  /** 从案例复制/预览进入时，仅为当前停留步骤加呼吸灯。 */
  highlightAll?: boolean
  /** 素材准备（scene-character）步 loading（提取中 / 第三步可视生成中） */
  sceneCharacterGenerating: boolean
  storyboardScriptGenerating: boolean
  storyboardVideoGenerating: boolean
  dubbingGenerating: boolean
  onPillClick: (index: number) => void
  onPillIntent: (step: CreationStep) => void
}

export function CreateFlowStepStrip({
  displaySteps,
  flowStepIndex,
  stepStatus,
  unlockedStepIndex,
  stepRealIndex,
  isConnectorTrailDone: _isConnectorTrailDone,
  isPillDisabled,
  publishGuideActive = false,
  publishGuideTooltipOpen = false,
  isPreviewStep = false,
  highlightAll = false,
  sceneCharacterGenerating,
  storyboardScriptGenerating,
  storyboardVideoGenerating,
  dubbingGenerating,
  onPillClick,
  onPillIntent
}: CreateFlowStepStripProps) {
  const scrollRef = useRef<HTMLDivElement>(null)

  function pillLoading(stepKey: CreationStep): boolean {
    if (stepKey === 'scene-character') return sceneCharacterGenerating
    if (stepKey === 'storyboard-script') return storyboardScriptGenerating
    if (stepKey === 'storyboard-video') return storyboardVideoGenerating
    if (stepKey === 'dubbing') return dubbingGenerating
    return false
  }

  useLayoutEffect(() => {
    const rail = scrollRef.current
    if (!rail) return
    const measure = () => {
      if (!rail.isConnected) return
      const boxOf = (node: HTMLElement | null) => {
        let box = node
        while (box && box.parentElement && box.parentElement !== rail) box = box.parentElement
        return box
      }
      const active = boxOf(rail.querySelector<HTMLElement>('[aria-current="step"]'))
      if (active) {
        rail.style.setProperty('--flow-tx', `${active.offsetLeft}px`)
        rail.style.setProperty('--flow-tw', `${active.offsetWidth}px`)
      }
    }
    measure()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(rail)
    rail.querySelectorAll('.flow-step').forEach((node) => observer?.observe(node))
    return () => observer?.disconnect()
  }, [
    flowStepIndex,
    unlockedStepIndex,
    displaySteps,
    stepStatus,
    sceneCharacterGenerating,
    storyboardScriptGenerating,
    storyboardVideoGenerating,
    dubbingGenerating
  ])

  useEffect(() => {
    const scroll = scrollRef.current
    if (!scroll) return
    const revealCurrent = () => {
      if (!scroll.isConnected) return
      scroll.querySelector<HTMLElement>('[aria-current="step"]')?.scrollIntoView?.({
        block: 'nearest', inline: 'nearest', behavior: 'auto'
      })
    }
    revealCurrent()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(revealCurrent)
    observer?.observe(scroll)
    return () => observer?.disconnect()
  }, [flowStepIndex, displaySteps.length])

  return (
    <nav className="create-workflow" data-onboarding="create-step-bar" aria-label="创作流程">
      <div className="create-workflow__scroll flow-rail" ref={scrollRef}>
        <span className="flow-rail__thumb" aria-hidden="true" />
        {displaySteps.map((step) => {
          const realIndex = stepRealIndex(step.key)
          const isActive = flowStepIndex === realIndex
          const loading = pillLoading(step.key)
          const state = isActive
            ? 'active'
            : realIndex > unlockedStepIndex || stepStatus[realIndex] === 'disabled'
              ? 'locked'
              : stepStatus[realIndex] === 'completed'
                ? 'done'
                : 'open'
          const statusText = loading
            ? '生成中'
            : state === 'done'
              ? '已完成'
              : state === 'locked'
                ? '完成上一步后解锁'
                : state === 'active'
                  ? '当前步骤'
                  : '可进入'
          const guideHere = Boolean(publishGuideTooltipOpen && isActive)
          const classes = [
            'flow-step',
            loading ? 'is-generating' : '',
            publishGuideActive && !isPreviewStep && isActive ? 'flow-step-pill--guide-breathing' : '',
            highlightAll && isActive ? 'flow-step-pill--entry-breathing' : ''
          ].filter(Boolean).join(' ')
          return (
            <Tooltip
              key={step.key}
              open={guideHere ? true : undefined}
              title={guideHere ? '请先完成作品' : `${step.description} · ${statusText}`}
              placement="bottom"
              classNames={guideHere ? { root: 'publish-guide-tooltip' } : undefined}
            >
              <div className="flow-step-slot">
                <button
                  type="button"
                  className={classes}
                  data-state={state}
                  aria-current={isActive ? 'step' : undefined}
                  aria-busy={loading}
                  aria-label={`${step.title}，${statusText}`}
                  disabled={isPillDisabled(realIndex)}
                  onPointerEnter={() => onPillIntent(step.key)}
                  onPointerDown={() => onPillIntent(step.key)}
                  onFocus={() => onPillIntent(step.key)}
                  onClick={() => onPillClick(realIndex)}
                >
                  <span className="flow-step__hover" aria-hidden="true" />
                  <span className="flow-step__sheen" aria-hidden="true" />
                  <span className="flow-step__mark">
                    {ringIcon}
                    {checkIcon}
                  </span>
                  <span className="flow-step__label">{step.title}</span>
                </button>
              </div>
            </Tooltip>
          )
        })}
      </div>
    </nav>
  )
}
