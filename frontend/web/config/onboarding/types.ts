import type { CreationStep } from '~/types'

export type OnboardingTourStatus = 'completed' | 'skipped' | 'in_progress'

export type OnboardingAdvanceOn = 'next' | 'click-target' | 'route-change'

export type OnboardingTourType = 'spotlight' | 'overview'

export interface SpotlightStep {
  id: string
  /** data-onboarding 锚点值 */
  element: string
  title: string
  description: string
  side?: 'top' | 'bottom' | 'left' | 'right'
  advanceOn?: OnboardingAdvanceOn
  waitFor?: string
  waitTimeoutMs?: number
  /**
   * 运行时过滤：
   * - non-pro：仅非专业版创作模式展示
   * - pro：仅专业版创作模式展示
   */
  when?: 'non-pro' | 'pro'
}

export interface TourTrigger {
  route?: string | RegExp
  creationStep?: CreationStep
  /** 非路由触发：如 create-first-step 弹窗 */
  modalKey?: 'create-first-step'
  once?: boolean
  priority?: number
  requiresLogin?: boolean
  /** 剧集项目才触发 */
  seriesOnly?: boolean
}

export interface TourDefinition {
  id: string
  version: number
  type: OnboardingTourType
  trigger: TourTrigger
  prerequisites?: string[]
  spotlightSteps?: SpotlightStep[]
}

export interface OnboardingTourProgress {
  status: OnboardingTourStatus
  tourVersion: number
  lastStepId?: string
  updatedAt: string
}
