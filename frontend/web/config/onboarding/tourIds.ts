/** 当前 Web 注册的引导 ID，服务端仍可保留历史引导记录。 */
export const ONBOARDING_TOUR_IDS = [
  'welcome-overview',
  'page-case-detail',
  'create-first-step',
  'create-global-setting',
  'create-scene-character',
  'create-storyboard-script',
  'create-storyboard-video',
  'create-dubbing',
  'create-preview',
  'create-series-upload',
  'create-series-episode',
  'studio-free',
  'studio-previz'
] as const

export type OnboardingTourId = (typeof ONBOARDING_TOUR_IDS)[number]

export const ONBOARDING_SCHEMA_VERSION = 1

export const ONBOARDING_LOCAL_KEY = 'onboarding:progress:v1'

export const ONBOARDING_PENDING_WELCOME_KEY = 'onboarding:pending-welcome'
