'use client'

import { usePathname } from 'next/navigation'
import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import SpotlightTour from '~/components/onboarding/SpotlightTour'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { prepareOnboardingIdentity, useOnboardingEngine } from '~/composables/useOnboardingEngine'
import {
isOnboardingPausedByModal,
useOnboardingModalPause
} from '~/composables/useOnboardingModalPause'
import { useOnboardingStore } from '~/stores/onboarding'
import { useUserStore } from '~/stores/user'
import './OnboardingProvider.css'

/**
 * 新用户视觉引导全局提供者（挂在根布局）：
 * 协调首页欢迎、聚光灯引导和版本升级确认，并承接原 onboarding.client 插件的
 * 初始化、路由评估、登录态联动与页面可见性同步。
 */
export default function OnboardingProvider() {
  const engine = useOnboardingEngine()
  const modalPause = useOnboardingModalPause()
  const homeCreateModal = useHomeShellCreateModal()
  const token = useUserStore((s) => s.token)
  const pathname = usePathname()

  const upgradeCardRef = useRef<HTMLDivElement | null>(null)

  const activeTour = engine.activeSession?.tour ?? null
  const upgradeTour = engine.versionUpgradePrompt

  const spotlightTour = activeTour?.type === 'spotlight' ? activeTour : null

  /* —— 原 plugins/onboarding.client.ts：初始化 + 可见性同步 + dev 重置入口 —— */
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible' && useUserStore.getState().token) {
        void engine.syncPendingToServer()
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange)

    if (process.env.NODE_ENV === 'development') {
      ;(window as Window & { __resetOnboarding?: () => void }).__resetOnboarding = () => {
        useOnboardingStore.getState().devClearAll()
        engine.evaluateRoute(window.location.pathname)
      }
    }

    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void prepareOnboardingIdentity(token, controller.signal)
    return () => controller.abort()
  }, [token])

  /* 路由变化后等待锚点就绪，离开页面时取消旧路径的评估。 */
  useEffect(() => {
    engine.cancelActiveTourIfRouteMismatch(pathname)
    if (engine.readyToken !== token) return
    if (pathname === '/') {
      engine.evaluateRoute()
      return
    }
    const timer = window.setTimeout(() => engine.evaluateRoute(), 600)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname, engine.readyToken, token])

  /* —— 弹层暂停（immediate watch） —— */
  const isPausedByModal = modalPause.isPausedByModal
  useEffect(() => {
    if (isPausedByModal) engine.pauseEngine()
    else engine.resumeEngine()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPausedByModal])

  /* —— 「创建作品第一步」弹窗联动 —— */
  const showCreateFirstStepModal = homeCreateModal.showCreateFirstStepModal
  const isFirstCreateModalRef = useRef(true)
  useEffect(() => {
    if (isFirstCreateModalRef.current) {
      isFirstCreateModalRef.current = false
      return
    }
    modalPause.setPaused('create-first-step', showCreateFirstStepModal)
    engine.evaluateCreateFirstStepModal(showCreateFirstStepModal)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showCreateFirstStepModal])

  /* —— 版本升级确认弹窗：出现时暂停引擎并聚焦 —— */
  const isFirstUpgradeRef = useRef(true)
  useEffect(() => {
    if (isFirstUpgradeRef.current) {
      isFirstUpgradeRef.current = false
      return
    }
    if (upgradeTour) {
      engine.pauseEngine()
      requestAnimationFrame(() => upgradeCardRef.current?.focus())
    } else if (!isOnboardingPausedByModal()) {
      engine.resumeEngine()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upgradeTour])

  function onSpotlightComplete() {
    engine.finishActive('completed')
  }

  function onSpotlightSkip() {
    engine.finishActive('skipped')
  }

  function onSpotlightAbort() {
    engine.cancelActiveWithoutSaving()
  }

  function onAcceptUpgrade() {
    engine.confirmVersionUpgrade(true)
  }

  function onDeclineUpgrade() {
    engine.confirmVersionUpgrade(false)
  }

  return (
    <>
      {spotlightTour ? (
        <SpotlightTour
          key={spotlightTour.id}
          tour={spotlightTour}
          onComplete={onSpotlightComplete}
          onSkip={onSpotlightSkip}
          onAbort={onSpotlightAbort}
        />
      ) : null}
      {upgradeTour && typeof document !== 'undefined'
        ? createPortal(
            <div
              className="onboarding-upgrade"
              role="dialog"
              aria-modal="true"
              aria-labelledby="onboarding-upgrade-title"
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  e.preventDefault()
                  onDeclineUpgrade()
                }
              }}
            >
              <div className="onboarding-upgrade__backdrop" aria-hidden="true" />
              <div ref={upgradeCardRef} className="onboarding-upgrade__card" tabIndex={-1}>
                <h2 id="onboarding-upgrade-title" className="onboarding-upgrade__title">
                  功能更新
                </h2>
                <p className="onboarding-upgrade__desc">我们更新了部分引导内容，要看新引导吗？</p>
                <div className="onboarding-upgrade__actions">
                  <button
                    type="button"
                    className="onboarding-upgrade__secondary"
                    onClick={onDeclineUpgrade}
                  >
                    暂不需要
                  </button>
                  <button
                    type="button"
                    className="onboarding-upgrade__primary"
                    onClick={onAcceptUpgrade}
                  >
                    查看新引导
                  </button>
                </div>
              </div>
            </div>,
            document.body
          )
        : null}
    </>
  )
}
