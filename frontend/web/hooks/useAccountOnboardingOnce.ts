'use client'

import { useEffect, useRef } from 'react'
import type { OnboardingTourId } from '@/config/onboarding'
import { getOnboardingProgress } from '@/hooks/useOnboardingProgress'
import { isGlobalOnboardingBusy, useGlobalOnboardingBusy } from '@/hooks/useOnboardingEngine'
import { useUserStore } from '@/stores/user'

export function useAccountOnboardingOnce(tourId: OnboardingTourId, enabled: boolean, open: () => void) {
  const token = useUserStore(state => state.token)
  const userId = useUserStore(state => state.user?.id ? String(state.user.id) : '')
  const globalOnboardingBusy = useGlobalOnboardingBusy()
  const autoOpenedFor = useRef<string | null>(null)

  useEffect(() => {
    if (!enabled || !token || !userId || globalOnboardingBusy) return

    const progress = getOnboardingProgress()
    let cancelled = false
    let frame = 0

    async function tryOpen() {
      const state = progress.store.getState()
      if (!state.hydrated || state.userId !== userId || !progress.hasLoadedServerProgress(token)) {
        if (!await progress.fetchAndMergeFromServer()) return
      }
      if (cancelled) return
      // Let the global provider evaluate the login after it merges the same snapshot.
      await new Promise<void>(resolve => window.requestAnimationFrame(() => resolve()))

      async function claim() {
        const options = progress.store.persist?.getOptions()
        if (!options?.storage || !options.name) return
        const { storage, name } = options
        const saved = await Promise.resolve().then(() => storage.getItem(name)).catch(() => null)
        const current = progress.store.getState()
        if (cancelled || document.visibilityState === 'hidden' || isGlobalOnboardingBusy() ||
          useUserStore.getState().token !== token || current.userId !== userId ||
          current.globalDismissed || current.tours[tourId] ||
          (saved?.state?.userId === userId && (saved.state.globalDismissed || saved.state.tours?.[tourId]))) return

        // Write before opening so leaving mid-tour still consumes the automatic display.
        autoOpenedFor.current = token
        void progress.reportTour(tourId, 'in_progress')
        open()
      }

      if (navigator.locks) {
        await navigator.locks.request(`aid-onboarding:${userId}:${tourId}`, claim)
      } else {
        await claim()
      }
    }

    function schedule() {
      if (document.visibilityState === 'hidden') return
      window.cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(() => { void tryOpen() })
    }

    schedule()
    document.addEventListener('visibilitychange', schedule)
    return () => {
      cancelled = true
      window.cancelAnimationFrame(frame)
      document.removeEventListener('visibilitychange', schedule)
    }
  }, [enabled, globalOnboardingBusy, open, token, tourId, userId])

  return (status: 'completed' | 'skipped') => {
    if (autoOpenedFor.current !== token || !token || useUserStore.getState().token !== token) return
    autoOpenedFor.current = null
    void getOnboardingProgress().reportTour(tourId, status)
  }
}
