import { getTourById } from '@/config/onboarding'
import type { OnboardingTourProgress, OnboardingTourStatus } from '@/config/onboarding/types'
import { formatOnboardingClientDateTime } from '@/utils/onboardingDateTime'

const VALID_STATUSES: OnboardingTourStatus[] = ['completed', 'skipped', 'in_progress']

/** Persisted browser data can predate the current Tour catalog and API date contract. */
export function buildOnboardingSyncTours(tours: Record<string, OnboardingTourProgress>) {
  return Object.entries(tours).flatMap(([tourId, row]) => {
    if (!getTourById(tourId) || !row || !VALID_STATUSES.includes(row.status)
      || !Number.isInteger(row.tourVersion) || row.tourVersion < 1) return []
    const timestamp = typeof row.updatedAt === 'string' ? row.updatedAt.trim() : ''
    const date = new Date(timestamp.replace(' ', 'T'))
    if (Number.isNaN(date.getTime())) return []
    return [{
      tourId,
      status: row.status,
      tourVersion: row.tourVersion,
      lastStepId: row.lastStepId ?? null,
      clientUpdatedAt: formatOnboardingClientDateTime(date)
    }]
  })
}
