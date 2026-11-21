import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { ONBOARDING_SCHEMA_VERSION } from '~/config/onboarding'
import type { OnboardingTourProgress,OnboardingTourStatus } from '~/config/onboarding/types'
import { formatOnboardingClientDateTime } from '~/utils/onboardingDateTime'
export interface OnboardingStoreState {
  userId: string
  schemaVersion: number
  tours: Record<string, OnboardingTourProgress>
  globalDismissed: boolean
  lastSyncedAt: string | null
  pendingSync: boolean
  hydrated: boolean
}

interface OnboardingStoreActions {
  isTourDone: (tourId: string) => boolean
  isTourEligibleForAuto: (tourId: string, configVersion: number) => boolean
  bindUser: (userId: string) => void
  clearForLogout: () => void
  applyTourProgress: (
    tourId: string,
    patch: Partial<OnboardingTourProgress> & {
      status: OnboardingTourStatus
      tourVersion: number
    }
  ) => boolean
  mergeServerSnapshot: (payload: {
    schemaVersion?: number
    globalDismissed?: boolean
    tours?: Array<{
      tourId: string
      status: OnboardingTourStatus
      tourVersion: number
      lastStepId?: string | null
      updatedAt: string
    }>
    updatedAt?: string
  }) => void
  markSynced: () => void
  setGlobalDismissed: (dismissed: boolean) => void
  /** 开发环境：清空本地进度（非用户功能） */
  devClearAll: () => void
}

function normalizeOnboardingDate(raw: string): string {
  return raw.includes('T') ? raw.replace('T', ' ').replace(/\.\d{3}Z$/, '') : raw
}

function emptyState(): OnboardingStoreState {
  return {
    userId: '',
    schemaVersion: ONBOARDING_SCHEMA_VERSION,
    tours: {},
    globalDismissed: false,
    lastSyncedAt: null,
    pendingSync: false,
    hydrated: false
  }
}

export const useOnboardingStore = create<OnboardingStoreState & OnboardingStoreActions>()(
  persist(
    (set, get) => ({
      ...emptyState(),

      isTourDone(tourId) {
        const row = get().tours[tourId]
        if (!row) return false
        return row.status === 'completed' || row.status === 'skipped'
      },

      isTourEligibleForAuto(tourId, configVersion) {
        const state = get()
        if (state.globalDismissed) return false
        const row = state.tours[tourId]
        if (!row) return true
        if (row.status === 'completed' || row.status === 'skipped') {
          return configVersion > row.tourVersion
        }
        return true
      },

      bindUser(userId) {
        const state = get()
        if (state.userId && state.userId !== userId) {
          set({ tours: {}, globalDismissed: false, pendingSync: false, lastSyncedAt: null, hydrated: false })
        }
        set({ userId })
      },

      clearForLogout() {
        set(emptyState())
      },

      applyTourProgress(tourId, patch) {
        const prev = get().tours[tourId]
        const updatedAt = patch.updatedAt ?? formatOnboardingClientDateTime()
        if (prev) {
          const prevTime = Date.parse(normalizeOnboardingDate(prev.updatedAt))
          const nextTime = Date.parse(normalizeOnboardingDate(updatedAt))
          if (!Number.isNaN(prevTime) && !Number.isNaN(nextTime) && nextTime < prevTime) {
            return false
          }
        }
        set({
          tours: {
            ...get().tours,
            [tourId]: {
              status: patch.status,
              tourVersion: patch.tourVersion,
              lastStepId: patch.lastStepId,
              updatedAt
            }
          },
          pendingSync: true
        })
        return true
      },

      mergeServerSnapshot(payload) {
        const state = get()
        const serverSchema = payload.schemaVersion
        const schemaMismatch = serverSchema !== undefined && serverSchema !== state.schemaVersion

        const next: Partial<OnboardingStoreState> = {}

        if (typeof payload.globalDismissed === 'boolean') {
          next.globalDismissed = payload.globalDismissed
        }

        if (schemaMismatch) {
          const tours: Record<string, OnboardingTourProgress> = {}
          for (const row of payload.tours ?? []) {
            tours[row.tourId] = {
              status: row.status,
              tourVersion: row.tourVersion,
              lastStepId: row.lastStepId ?? undefined,
              updatedAt: row.updatedAt
            }
          }
          next.tours = tours
          if (serverSchema !== undefined) {
            next.schemaVersion = serverSchema
          }
        } else {
          const tours = { ...state.tours }
          for (const row of payload.tours ?? []) {
            const local = tours[row.tourId]
            const serverTime = Date.parse(normalizeOnboardingDate(row.updatedAt))
            const localTime = local ? Date.parse(normalizeOnboardingDate(local.updatedAt)) : 0
            if (!local || serverTime >= localTime) {
              tours[row.tourId] = {
                status: row.status,
                tourVersion: row.tourVersion,
                lastStepId: row.lastStepId ?? undefined,
                updatedAt: row.updatedAt
              }
            }
          }
          next.tours = tours
        }

        if (payload.updatedAt) {
          next.lastSyncedAt = payload.updatedAt
        }
        next.hydrated = true
        set(next)
      },

      markSynced() {
        set({ pendingSync: false, lastSyncedAt: formatOnboardingClientDateTime() })
      },

      setGlobalDismissed(dismissed) {
        set({ globalDismissed: dismissed, pendingSync: true })
      },

      devClearAll() {
        set({ tours: {}, globalDismissed: false, pendingSync: false })
      }
    }),
    {
      name: 'onboarding-store',
      partialize: (s) => ({
        userId: s.userId,
        schemaVersion: s.schemaVersion,
        tours: s.tours,
        globalDismissed: s.globalDismissed,
        lastSyncedAt: s.lastSyncedAt,
        pendingSync: s.pendingSync
      })
    }
  )
)
