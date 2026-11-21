'use client'

import { usePathname } from 'next/navigation'
import { useEffect,useRef,useState } from 'react'
import { createPortal } from 'react-dom'
import { tourMatchesPath } from '~/config/onboarding'
import type { SpotlightStep,TourDefinition } from '~/config/onboarding/types'
import { useCreationStore } from '~/stores/creation'
import { isProCreationMode } from '~/utils/creationModeUiRules'
import {
dispatchOnboardingPrepareAnchor,
isElementVisible,
onboardingSelector,
sleepMs,
waitForElement
} from '~/utils/onboardingDom'
import {
cleanupOnboardingPrepareClasses,
isLazyPrepareTour,
prepareOnboardingAnchor
} from '~/utils/onboardingPrepareAnchor'
import { readHtmlZoom,toLayoutPx } from '~/utils/viewportZoom'
import './SpotlightTour.css'

interface SpotlightTourProps {
  tour: TourDefinition
  onComplete: () => void
  onSkip: () => void
  onAbort: () => void
}

/** 与 driver stagePadding 一致：独立白框比目标元素外扩该值 */
const SPOTLIGHT_RING_PADDING = 4

export default function SpotlightTour(props: SpotlightTourProps) {
  const { tour } = props

  const pathname = usePathname()
  const pathnameRef = useRef(pathname)
  pathnameRef.current = pathname

  const callbacksRef = useRef({
    onComplete: props.onComplete,
    onSkip: props.onSkip,
    onAbort: props.onAbort
  })
  callbacksRef.current = {
    onComplete: props.onComplete,
    onSkip: props.onSkip,
    onAbort: props.onAbort
  }

  const driverApiRef = useRef<import('driver.js').Driver | null>(null)
  const clickCleanupRef = useRef<(() => void) | null>(null)
  const completedNormallyRef = useRef(false)
  const unmountedRef = useRef(false)
  const connectorElRef = useRef<HTMLDivElement | null>(null)
  const ringElRef = useRef<HTMLDivElement | null>(null)
  const resolvedStepCountRef = useRef(0)

  const [skipVisible, setSkipVisible] = useState(false)

  function destroyDriver(markCompleted: boolean) {
    completedNormallyRef.current = markCompleted
    cleanupClickListener()
    removeSpotlightChrome()
    cleanupOnboardingPrepareClasses()
    document.documentElement.classList.remove('onboarding-spotlight-active')
    setSkipVisible(false)
    driverApiRef.current?.destroy()
    driverApiRef.current = null
  }

  function cleanupOrphanDriverDom() {
    document.querySelectorAll('.driver-popover, .driver-overlay').forEach((el) => el.remove())
    removeSpotlightChrome()
  }

  function cleanupClickListener() {
    clickCleanupRef.current?.()
    clickCleanupRef.current = null
  }

  function removeConnector() {
    connectorElRef.current?.remove()
    connectorElRef.current = null
  }

  function removeRing() {
    ringElRef.current?.remove()
    ringElRef.current = null
  }

  function removeSpotlightChrome() {
    removeConnector()
    removeRing()
  }

  function stepSelector(step: SpotlightStep): string {
    return step.waitFor ?? onboardingSelector(step.element)
  }

  async function resolveStepElement(step: SpotlightStep): Promise<Element | null> {
    const selector = stepSelector(step)
    const timeout = step.waitTimeoutMs ?? 5000
    const el = await waitForElement(selector, timeout)
    if (el && isElementVisible(el)) return el
    return null
  }

  function filterStepsByRuntime(steps: SpotlightStep[]): SpotlightStep[] {
    const creationState = useCreationStore.getState()
    const isPro = isProCreationMode(creationState.formData.globalSetting?.creationMode)
    return steps.filter((step) => {
      if (step.when === 'non-pro') return !isPro
      if (step.when === 'pro') return isPro
      return true
    })
  }

  async function resolveTourSteps(steps: SpotlightStep[]): Promise<SpotlightStep[]> {
    const filtered = filterStepsByRuntime(steps)
    if (isLazyPrepareTour(tour.id)) {
      return filtered
    }

    const resolved: SpotlightStep[] = []
    for (const step of filtered) {
      if (step.element === 'btn-auto-storyboard') {
        dispatchOnboardingPrepareAnchor('btn-auto-storyboard')
        await sleepMs(180)
      }
      const el = await resolveStepElement(step)
      if (unmountedRef.current) return resolved
      if (el) resolved.push(step)
    }
    return resolved
  }

  function attachClickAdvance(el: Element, advance: () => void) {
    cleanupClickListener()
    const handler = () => {
      cleanupClickListener()
      advance()
    }
    el.addEventListener('click', handler, { capture: true, once: true })
    clickCleanupRef.current = () => el.removeEventListener('click', handler, { capture: true })
  }

  async function prepareStepAtIndex(resolvedSteps: SpotlightStep[], index: number) {
    const step = resolvedSteps[index]
    if (!step) return
    await prepareOnboardingAnchor(step.element, tour.id)
    await sleepMs(80)
  }

  function formatStepActionLabel(d: import('driver.js').Driver, total: number): string {
    const idx = (d.getActiveIndex() ?? 0) + 1
    if (d.isLastStep()) return `完成(${idx}/${total})`
    return `下一步(${idx}/${total})`
  }

  function customizePopoverDom(d: import('driver.js').Driver) {
    const popover = document.getElementById('driver-popover-content')
    if (!popover) return

    const closeBtn = popover.querySelector('.driver-popover-close-btn') as HTMLElement | null
    const progress = popover.querySelector('.driver-popover-progress-text') as HTMLElement | null
    const prevBtn = popover.querySelector('.driver-popover-prev-btn') as HTMLElement | null
    const nextBtn = popover.querySelector('.driver-popover-next-btn') as HTMLElement | null

    if (closeBtn) closeBtn.style.display = 'none'
    if (progress) progress.style.display = 'none'
    if (prevBtn) prevBtn.style.display = 'none'
    if (nextBtn) nextBtn.textContent = formatStepActionLabel(d, resolvedStepCountRef.current)
  }

  /**
   * html 根节点 zoom ≠ 1 时：getBoundingClientRect 为视觉坐标，
   * fixed 的 left/top/width/height 声明值还会再乘 zoom，必须先 /zoom（toLayoutPx）。
   * 与 SettingSelectField 等 Teleport 浮层同一套约定。
   */
  function applyLayoutPxBox(
    el: HTMLElement,
    visualLeft: number,
    visualTop: number,
    visualWidth?: number,
    visualHeight?: number
  ) {
    const zoom = readHtmlZoom()
    el.style.left = `${toLayoutPx(visualLeft, zoom)}px`
    el.style.top = `${toLayoutPx(visualTop, zoom)}px`
    if (visualWidth != null) el.style.width = `${toLayoutPx(visualWidth, zoom)}px`
    if (visualHeight != null) el.style.height = `${toLayoutPx(visualHeight, zoom)}px`
  }

  /** driver.js 按视觉坐标写 left/top，根节点 zoom 下需换算一次，避免提示卡整体偏左上 */
  function correctDriverPopoverZoomPosition() {
    const popover = document.getElementById('driver-popover-content')
    if (!popover) return

    const zoom = readHtmlZoom()
    if (!Number.isFinite(zoom) || Math.abs(zoom - 1) < 0.001) {
      delete popover.dataset.onboardingZoomCorrected
      return
    }

    const left = parseFloat(popover.style.left)
    const top = parseFloat(popover.style.top)
    if (Number.isNaN(left) || Number.isNaN(top)) return

    // driver 每次重定位都会重写 style；仅在尚未换算时处理，避免 rAF/resize 二次除法
    if (popover.dataset.onboardingZoomCorrected === '1') return

    popover.style.left = `${toLayoutPx(left, zoom)}px`
    popover.style.top = `${toLayoutPx(top, zoom)}px`
    popover.dataset.onboardingZoomCorrected = '1'
  }

  function resetDriverPopoverZoomCorrection() {
    const popover = document.getElementById('driver-popover-content')
    if (popover) delete popover.dataset.onboardingZoomCorrected
  }

  /** 引导卡贴近视口底部时上移，避免与「跳过介绍」重叠 */
  function ensurePopoverAboveSkipButton() {
    const popover = document.getElementById('driver-popover-content')
    if (!popover) return

    const SKIP_CLEARANCE_PX = 88 // skip 高约 36 + bottom 24 + 间距
    const zoom = readHtmlZoom()
    const rect = popover.getBoundingClientRect()
    const overflow = rect.bottom - (window.innerHeight - SKIP_CLEARANCE_PX)
    if (overflow <= 0) return

    const currentTop = parseFloat(popover.style.top)
    if (Number.isNaN(currentTop)) return
    popover.style.top = `${currentTop - toLayoutPx(overflow, zoom)}px`
  }

  function positionRing(d: import('driver.js').Driver) {
    removeRing()
    const target = d.getActiveElement()
    if (!target || !(target instanceof HTMLElement)) return

    const rect = target.getBoundingClientRect()
    if (rect.width <= 0 && rect.height <= 0) return

    const pad = SPOTLIGHT_RING_PADDING
    const ringEl = document.createElement('div')
    ringEl.className = 'onboarding-spotlight-ring'
    ringEl.setAttribute('aria-hidden', 'true')
    const left = Math.max(2, rect.left - pad)
    const top = Math.max(2, rect.top - pad)
    const right = Math.min(window.innerWidth - 2, rect.right + pad)
    const bottom = Math.min(window.innerHeight - 2, rect.bottom + pad)
    if (right <= left || bottom <= top) return
    applyLayoutPxBox(ringEl, left, top, right - left, bottom - top)
    document.body.appendChild(ringEl)
    ringElRef.current = ringEl
  }

  function positionConnector(d: import('driver.js').Driver) {
    removeConnector()
    const target = d.getActiveElement()
    const popover = document.getElementById('driver-popover-content')
    if (!target || !popover || !(target instanceof HTMLElement)) return

    const targetRect = target.getBoundingClientRect()
    const popRect = popover.getBoundingClientRect()
    if (targetRect.width <= 0 || targetRect.height <= 0) return

    const pad = SPOTLIGHT_RING_PADDING
    const dotX = targetRect.left + targetRect.width / 2
    const ringBottom = targetRect.bottom + pad
    const popTop = popRect.top

    if (popTop <= ringBottom + 4) return

    const lineTop = ringBottom
    const lineHeight = Math.max(8, popTop - ringBottom - 6)
    const zoom = readHtmlZoom()

    const connectorEl = document.createElement('div')
    connectorEl.className = 'onboarding-spotlight-connector'
    connectorEl.innerHTML =
      '<span class="onboarding-spotlight-connector__dot" aria-hidden="true"></span><span class="onboarding-spotlight-connector__line" aria-hidden="true"></span>'

    const dot = connectorEl.querySelector('.onboarding-spotlight-connector__dot') as HTMLElement
    const line = connectorEl.querySelector('.onboarding-spotlight-connector__line') as HTMLElement

    applyLayoutPxBox(dot, dotX - 4, ringBottom - 4)
    applyLayoutPxBox(line, dotX, lineTop)
    line.style.height = `${toLayoutPx(lineHeight, zoom)}px`

    document.body.appendChild(connectorEl)
    connectorElRef.current = connectorEl
  }

  function refreshSpotlightChrome(d: import('driver.js').Driver) {
    requestAnimationFrame(() => {
      customizePopoverDom(d)
      correctDriverPopoverZoomPosition()
      ensurePopoverAboveSkipButton()
      positionRing(d)
      positionConnector(d)
    })
  }

  function skipTour() {
    destroyDriver(false)
  }

  async function startTour() {
    if (typeof window === 'undefined' || unmountedRef.current) return

    if (tour.trigger.route && !tourMatchesPath(tour, pathnameRef.current)) {
      callbacksRef.current.onAbort()
      return
    }

    cleanupClickListener()
    cleanupOnboardingPrepareClasses()
    driverApiRef.current?.destroy()
    completedNormallyRef.current = false
    setSkipVisible(false)

    const steps = tour.spotlightSteps ?? []
    if (!steps.length) {
      callbacksRef.current.onAbort()
      return
    }

    let resolvedSteps: SpotlightStep[] = await resolveTourSteps(steps)
    if (unmountedRef.current) return

    if (!resolvedSteps.length) {
      await sleepMs(700)
      resolvedSteps = await resolveTourSteps(steps)
      if (unmountedRef.current) return
    }

    if (!resolvedSteps.length) {
      callbacksRef.current.onAbort()
      return
    }

    resolvedStepCountRef.current = resolvedSteps.length
    await prepareStepAtIndex(resolvedSteps, 0)

    const firstStep = resolvedSteps[0]
    if (firstStep) {
      let firstEl = await resolveStepElement(firstStep)
      if (!firstEl) {
        await sleepMs(500)
        firstEl = await resolveStepElement(firstStep)
      }
      if (!firstEl) {
        callbacksRef.current.onAbort()
        return
      }
    }

    const { driver } = await import('driver.js')
    await import('driver.js/dist/driver.css')

    const resolvedRef = resolvedSteps
    const narrowScreen = window.innerWidth < 768
    let onReposition = () => {}

    const driverApi = driver({
      animate: true,
      overlayOpacity: 0.7,
      stagePadding: SPOTLIGHT_RING_PADDING,
      stageRadius: 10,
      allowClose: false,
      overlayColor: '#04070c',
      popoverClass: 'onboarding-driver-popover',
      popoverOffset: 16,
      showProgress: false,
      showButtons: ['next'],
      onPopoverRender: (_popover, { driver: d }) => {
        customizePopoverDom(d)
      },
      onNextClick: (_el, _step, { driver: d }) => {
        if (d.isLastStep()) {
          completedNormallyRef.current = true
          d.destroy()
          return
        }
        const nextIdx = (d.getActiveIndex() ?? 0) + 1
        void prepareStepAtIndex(resolvedRef, nextIdx).then(async () => {
          if (unmountedRef.current || !driverApiRef.current) return
          const nextStep = resolvedRef[nextIdx]
          if (nextStep) {
            const nextEl = await resolveStepElement(nextStep)
            if (!nextEl) {
              completedNormallyRef.current = true
              d.destroy()
              return
            }
          }
          d.moveNext()
        })
      },
      onCloseClick: (_el, _step, { driver: d }) => {
        completedNormallyRef.current = false
        d.destroy()
      },
      onHighlightStarted: (element, _step, { driver: d }) => {
        if (tour.trigger.route && !tourMatchesPath(tour, pathnameRef.current)) {
          destroyDriver(true)
          return
        }
        // 步骤切换时 driver 会重写 left/top（视觉坐标），需重新做 zoom 换算
        resetDriverPopoverZoomCorrection()
        cleanupClickListener()
        const idx = d.getActiveIndex() ?? 0
        const meta = resolvedRef[idx]
        if (meta?.advanceOn === 'click-target' && element) {
          attachClickAdvance(element, () => {
            if (d.isLastStep()) {
              completedNormallyRef.current = true
              d.destroy()
            } else {
              const nextIdx = idx + 1
              void prepareStepAtIndex(resolvedRef, nextIdx).then(() => {
                if (unmountedRef.current || !driverApiRef.current) return
                d.moveNext()
              })
            }
          })
        }
      },
      onHighlighted: (_element, _step, { driver: d }) => {
        refreshSpotlightChrome(d)
      },
      onDestroyed: () => {
        window.removeEventListener('resize', onReposition)
        window.removeEventListener('scroll', onReposition, true)
        cleanupClickListener()
        removeSpotlightChrome()
        cleanupOnboardingPrepareClasses()
        document.documentElement.classList.remove('onboarding-spotlight-active')
        setSkipVisible(false)
        driverApiRef.current = null
        cleanupOrphanDriverDom()
        if (unmountedRef.current) return
        if (completedNormallyRef.current) {
          callbacksRef.current.onComplete()
        } else {
          callbacksRef.current.onSkip()
        }
      },
      steps: resolvedSteps.map((step) => ({
        element: stepSelector(step),
        disableActiveInteraction: step.advanceOn !== 'click-target',
        popover: {
          title: step.title,
          description: step.description,
          side: narrowScreen ? 'bottom' : (step.side ?? 'bottom'),
          align: 'start' as const,
          showButtons: step.advanceOn === 'click-target' ? (['close'] as const) : (['next'] as const)
        }
      }))
    })
    driverApiRef.current = driverApi

    document.documentElement.classList.add('onboarding-spotlight-active')
    setSkipVisible(true)

    onReposition = () => {
      if (!driverApiRef.current?.isActive()) return
      resetDriverPopoverZoomCorrection()
      try {
        driverApiRef.current.refresh()
      } catch {
        /* driver 未就绪时忽略 */
      }
      refreshSpotlightChrome(driverApiRef.current)
    }
    window.addEventListener('resize', onReposition)
    window.addEventListener('scroll', onReposition, true)

    driverApi.drive()
  }

  useEffect(() => {
    void startTour()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tour.id])

  useEffect(() => {
    if (!tour.trigger.route) return
    if (!driverApiRef.current) return
    if (!tourMatchesPath(tour, pathname)) {
      destroyDriver(true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname])

  useEffect(() => {
    return () => {
      unmountedRef.current = true
      destroyDriver(false)
      cleanupOrphanDriverDom()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <>
      <span className="onboarding-spotlight-host" aria-hidden="true" />
      {skipVisible && typeof document !== 'undefined'
        ? createPortal(
            <button
              type="button"
              className="onboarding-spotlight-skip"
              aria-label="跳过介绍"
              onClick={skipTour}
            >
              跳过介绍
            </button>,
            document.body
          )
        : null}
    </>
  )
}
