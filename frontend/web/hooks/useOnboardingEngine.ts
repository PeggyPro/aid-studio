import { create } from 'zustand'
import { getOnboardingProgress } from '~/composables/useOnboardingProgress'
import {
getTourById,
isCreateFlowPath,
matchToursForRoute,
ONBOARDING_PENDING_WELCOME_KEY,
tourMatchesPath,
type TourDefinition
} from '~/config/onboarding'
import { useCreationStore } from '~/stores/creation'
import { useOnboardingStore } from '~/stores/onboarding'
import { useUserStore } from '~/stores/user'
import { isMobileClientEnvironment } from '@/utils/mobileOnlyNavigation'

export interface ActiveOnboardingSession {
  tour: TourDefinition
  startStepIndex?: number
}

/** 引擎单例状态（原 Vue 模块级 ref）：任意组件调用 useOnboardingEngine() 拿到同一引擎 */
interface OnboardingEngineState {
  activeSession: ActiveOnboardingSession | null
  queue: string[]
  running: boolean
  paused: boolean
  readyToken: string | null
  versionUpgradePrompt: TourDefinition | null
}

const useOnboardingEngineStore = create<OnboardingEngineState>(() => ({
  activeSession: null,
  queue: [],
  running: false,
  paused: false,
  readyToken: null,
  versionUpgradePrompt: null
}))

function selectGlobalOnboardingBusy(state: OnboardingEngineState) {
  return state.paused || Boolean(state.activeSession || state.versionUpgradePrompt)
}

export function useGlobalOnboardingBusy() {
  return useOnboardingEngineStore(selectGlobalOnboardingBusy)
}

export function isGlobalOnboardingBusy() {
  return selectGlobalOnboardingBusy(useOnboardingEngineStore.getState()) ||
    Boolean(sessionStorage.getItem(ONBOARDING_PENDING_WELCOME_KEY))
}


function getPath(): string {
  if (typeof window === 'undefined') return '/'
  return window.location.pathname
}

function isLoggedIn(): boolean {
  return !!useUserStore.getState().token
}

function isSeriesProject(): boolean {
  return useCreationStore.getState().currentProjectType === 'series'
}

function tourPriority(tour: TourDefinition): number {
  return tour.trigger.priority ?? 5
}

function isTourEligible(tour: TourDefinition, store: ReturnType<typeof useOnboardingStore.getState>) {
  return store.isTourEligibleForAuto(tour.id, tour.version) &&
    (tour.id !== 'welcome-overview' || !store.isTourDone(tour.id))
}

function isWelcomeBlocking(store: ReturnType<typeof useOnboardingStore.getState>): boolean {
  if (isMobileClientEnvironment()) return false
  const welcome = getTourById('welcome-overview')
  if (!welcome) return false
  return isTourEligible(welcome, store)
}

function prerequisitesMet(
  tour: TourDefinition,
  store: ReturnType<typeof useOnboardingStore.getState>
): boolean {
  for (const pre of tour.prerequisites ?? []) {
    const preTour = getTourById(pre)
    if (!preTour) continue
    if (isTourEligible(preTour, store)) return false
  }
  return true
}

/**
 * 「素材准备」引导尚未完成：应抑制自动提取弹窗，改由引导第 2 步按需打开。
 * 含引导排队中、引导进行中的时段（不仅限于 activeSession 已赋值瞬间）。
 */
export function isCreateSceneCharacterOnboardingPending(
  path: string,
  opts?: { activeTourId?: string | null }
): boolean {
  if (opts?.activeTourId === 'create-scene-character') return true

  const store = useOnboardingStore.getState()
  if (store.globalDismissed) return false

  const tour = getTourById('create-scene-character')
  if (!tour) return false
  if (!tourMatchesPath(tour, path)) return false
  if (!isTourEligible(tour, store)) return false
  if (!prerequisitesMet(tour, store)) return false

  return true
}

function isVersionUpgrade(
  store: ReturnType<typeof useOnboardingStore.getState>,
  tour: TourDefinition
): boolean {
  const row = store.tours[tour.id]
  if (!row) return false
  if (row.status !== 'completed' && row.status !== 'skipped') return false
  return tour.version > row.tourVersion
}

function canAutoStartTour(
  tour: TourDefinition,
  opts: { loggedIn: boolean; path: string; isSeriesProject: boolean }
): boolean {
  const store = useOnboardingStore.getState()
  if (store.globalDismissed) return false
  if (!isTourEligible(tour, store)) return false
  if (!prerequisitesMet(tour, store)) return false
  if (tour.trigger.requiresLogin !== false && !opts.loggedIn) return false
  if (tour.id === 'welcome-overview' && isMobileClientEnvironment()) return false
  if (tour.trigger.seriesOnly && !opts.isSeriesProject) return false
  if (tour.id !== 'welcome-overview' && isWelcomeBlocking(store)) return false
  if (tour.trigger.route && !tourMatchesPath(tour, opts.path)) return false
  return true
}

export function enqueueTourId(tourId: string, front = false) {
  const { activeSession, queue } = useOnboardingEngineStore.getState()
  if (activeSession?.tour.id === tourId) return
  if (queue.includes(tourId)) return
  if (front) {
    useOnboardingEngineStore.setState({ queue: [tourId, ...queue] })
  } else {
    useOnboardingEngineStore.setState({ queue: [...queue, tourId] })
  }
}

function sortQueue(currentPath?: string) {
  const normalized = currentPath?.split('?')[0].split('#')[0] || '/'
  const routeTourIds = new Set(matchToursForRoute(normalized).map((t) => t.id))
  const { queue } = useOnboardingEngineStore.getState()
  const sorted = [...queue].sort((a, b) => {
    const ta = getTourById(a)
    const tb = getTourById(b)
    const priorityDiff = tourPriority(ta!) - tourPriority(tb!)
    if (priorityDiff !== 0) return priorityDiff
    const aOnRoute = routeTourIds.has(a)
    const bOnRoute = routeTourIds.has(b)
    if (aOnRoute && !bOnRoute) return -1
    if (!aOnRoute && bOnRoute) return 1
    return 0
  })
  useOnboardingEngineStore.setState({ queue: sorted })
}

function requestAutoStartTour(
  tour: TourDefinition,
  opts: { loggedIn: boolean; path: string; isSeriesProject: boolean; front?: boolean }
) {
  if (!canAutoStartTour(tour, opts)) return
  const store = useOnboardingStore.getState()
  if (isVersionUpgrade(store, tour)) {
    const { versionUpgradePrompt, activeSession, queue } = useOnboardingEngineStore.getState()
    if (
      versionUpgradePrompt?.id !== tour.id &&
      activeSession?.tour.id !== tour.id &&
      !queue.includes(tour.id)
    ) {
      useOnboardingEngineStore.setState({ versionUpgradePrompt: tour })
    }
    return
  }
  enqueueTourId(tour.id, opts.front)
}

function pruneQueueForPath(path: string) {
  const { queue } = useOnboardingEngineStore.getState()
  useOnboardingEngineStore.setState({
    queue: queue.filter((id) => {
      const tour = getTourById(id)
      if (!tour?.trigger.route) return true
      return tourMatchesPath(tour, path)
    })
  })
}

/** 离开 Tour 所属页面时结束引导，避免 driver 浮层残留到其他路由 */
export function cancelActiveTourIfRouteMismatch(path: string) {
  pruneQueueForPath(path)
  const session = useOnboardingEngineStore.getState().activeSession
  if (!session) return
  const { tour } = session
  if (tour.type === 'overview') {
    if (!tourMatchesPath(tour, path) || isMobileClientEnvironment()) cancelActiveWithoutSaving()
    return
  }
  if (isCreateFlowPath(path) && tour.trigger.modalKey === 'create-first-step') {
    finishActive('completed')
    return
  }
  if (tour.trigger.modalKey) return
  if (!tour.trigger.route) return
  if (!tourMatchesPath(tour, path)) {
    finishActive('completed')
  }
}

export async function runNextInQueue() {
  const state = useOnboardingEngineStore.getState()
  if (state.running || state.paused || state.activeSession || state.versionUpgradePrompt) return
  sortQueue(getPath())

  while (useOnboardingEngineStore.getState().queue.length) {
    const queue = useOnboardingEngineStore.getState().queue
    const nextId = queue[0]
    useOnboardingEngineStore.setState({ queue: queue.slice(1) })
    const tour = getTourById(nextId)
    if (!tour) continue

    const store = useOnboardingStore.getState()
    if (!isTourEligible(tour, store)) continue
    if (tour.trigger.requiresLogin !== false && !isLoggedIn()) continue
    if (tour.id === 'welcome-overview' && isMobileClientEnvironment()) continue
    if (tour.trigger.route && !tourMatchesPath(tour, getPath())) continue

    useOnboardingEngineStore.setState({ running: true, activeSession: { tour } })
    return
  }
}

function evaluateRouteForPath(path: string) {
  const store = useOnboardingStore.getState()
  if (store.globalDismissed) return
  const matched = matchToursForRoute(path)
  for (const tour of matched) {
    requestAutoStartTour(tour, {
      loggedIn: isLoggedIn(),
      path,
      isSeriesProject: isSeriesProject()
    })
  }
  void runNextInQueue()
}

export function finishActive(status: 'completed' | 'skipped') {
  const session = useOnboardingEngineStore.getState().activeSession
  if (!session) return
  const finishedId = session.tour.id
  const progress = getOnboardingProgress()
  void progress.reportTour(session.tour.id, status)
  useOnboardingEngineStore.setState({ activeSession: null, running: false })
  void runNextInQueue()
  if (finishedId === 'welcome-overview' && typeof window !== 'undefined') {
    window.setTimeout(() => evaluateRouteForPath(getPath()), 700)
  }
}

/** 锚点未就绪等导致引导未能展示：不写入完成进度，便于下次进入重试 */
export function cancelActiveWithoutSaving() {
  useOnboardingEngineStore.setState({ activeSession: null, running: false })
  void runNextInQueue()
}

export function pauseEngine() {
  useOnboardingEngineStore.setState({ paused: true })
}

export function resumeEngine() {
  useOnboardingEngineStore.setState({ paused: false })
  void runNextInQueue()
}

export function confirmVersionUpgrade(accept: boolean) {
  const tour = useOnboardingEngineStore.getState().versionUpgradePrompt
  if (!tour) return
  useOnboardingEngineStore.setState({ versionUpgradePrompt: null })
  const progress = getOnboardingProgress()
  if (accept) {
    enqueueTourId(tour.id, true)
    void runNextInQueue()
  } else {
    void progress.reportTour(tour.id, 'skipped')
    void runNextInQueue()
  }
}

export function evaluateRoute(path = getPath()) {
  evaluateRouteForPath(path)
}

export function evaluateCreateFirstStepModal(open: boolean) {
  if (!open) return
  const tour = getTourById('create-first-step')
  if (!tour) return
  requestAutoStartTour(tour, {
    loggedIn: isLoggedIn(),
    path: getPath(),
    isSeriesProject: isSeriesProject(),
    front: true
  })
  void runNextInQueue()
}

export async function prepareOnboardingIdentity(token: string, signal: AbortSignal) {
  if (useUserStore.getState().token !== token) return
  const previousToken = useOnboardingEngineStore.getState().readyToken
  if (previousToken !== token) {
    useOnboardingEngineStore.setState({
      readyToken: null, activeSession: null, queue: [], running: false, versionUpgradePrompt: null
    })
  }
  if (!token && previousToken) useOnboardingStore.getState().clearForLogout()

  if (token) await getOnboardingProgress().fetchAndMergeFromServer()
  if (signal.aborted || useUserStore.getState().token !== token) return

  if (
    previousToken === null &&
    process.env.NODE_ENV === 'development' &&
    new URLSearchParams(window.location.search).get('onboarding') === 'reset'
  ) {
    useOnboardingStore.getState().devClearAll()
  }
  try {
    sessionStorage.removeItem(ONBOARDING_PENDING_WELCOME_KEY)
  } catch {
    // Homepage eligibility still comes from account progress when storage is disabled.
  }
  evaluateRouteForPath(getPath())
  useOnboardingEngineStore.setState({ readyToken: token })
}

/**
 * 引导引擎（单例）：组件内调用可响应式读取 activeSession / queue 等状态；
 * 非组件上下文需要的动作同时以模块级具名导出提供。
 */
export function useOnboardingEngine() {
  const activeSession = useOnboardingEngineStore((s) => s.activeSession)
  const readyToken = useOnboardingEngineStore((s) => s.readyToken)
  const queue = useOnboardingEngineStore((s) => s.queue)
  const running = useOnboardingEngineStore((s) => s.running)
  const paused = useOnboardingEngineStore((s) => s.paused)
  const versionUpgradePrompt = useOnboardingEngineStore((s) => s.versionUpgradePrompt)
  const progress = getOnboardingProgress()

  return {
    activeSession,
    queue,
    running,
    paused,
    versionUpgradePrompt,
    readyToken,
    evaluateRoute,
    evaluateCreateFirstStepModal,
    enqueueTourId,
    runNextInQueue,
    finishActive,
    cancelActiveWithoutSaving,
    pauseEngine,
    resumeEngine,
    confirmVersionUpgrade,
    cancelActiveTourIfRouteMismatch,
    setGlobalDismissed: progress.setGlobalDismissed,
    syncPendingToServer: progress.syncPendingToServer
  }
}
