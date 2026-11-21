import { ONBOARDING_TOURS } from './tours'
import type { TourDefinition } from './types'

const tourMap = new Map<string, TourDefinition>(
  ONBOARDING_TOURS.map((t) => [t.id, t])
)

export function getTourById(id: string): TourDefinition | undefined {
  return tourMap.get(id)
}

export function getAllTours(): TourDefinition[] {
  return ONBOARDING_TOURS
}

export function matchToursForRoute(path: string): TourDefinition[] {
  const normalized = path.split('?')[0].split('#')[0] || '/'
  return ONBOARDING_TOURS.filter((tour) => {
    const route = tour.trigger.route
    if (!route) return false
    if (typeof route === 'string') return normalized === route
    return route.test(normalized)
  })
}

/** 当前路由是否仍属于该 Tour 的触发范围（无 route 约束的 Tour 如弹窗类恒为 true） */
export function tourMatchesPath(tour: TourDefinition, path: string): boolean {
  const route = tour.trigger.route
  if (!route) return true
  const normalized = path.split('?')[0].split('#')[0] || '/'
  if (typeof route === 'string') return normalized === route
  return route.test(normalized)
}

export function isCreateFlowPath(path: string): boolean {
  const normalized = path.split('?')[0].split('#')[0] || '/'
  return normalized === '/create' || normalized.startsWith('/create/')
}

export * from './types'
export * from './tourIds'
export { ONBOARDING_TOURS }
