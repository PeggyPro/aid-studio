import { getTourById } from '~/config/onboarding'
import type { OnboardingTourStatus } from '~/config/onboarding/types'
import { useOnboardingStore } from '~/stores/onboarding'
import { useUserStore } from '~/stores/user'
import {
userOnboardingProgressDismiss,
userOnboardingProgressGet,
userOnboardingProgressReport,
userOnboardingProgressSync
} from '~/utils/businessApi'
import { formatOnboardingClientDateTime } from '~/utils/onboardingDateTime'
import { buildOnboardingSyncTours } from '~/utils/onboardingSync'
import { apiErrorMessage } from '~/utils/apiError'

function isApiUnavailableError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return true
  const e = err as { response?: { status?: number }; code?: number; msg?: string }
  const status = e.response?.status
  if (status === 404 || status === 501 || status === 502 || status === 503) return true
  if (e.code === 404) return true
  const msg = String(e.msg ?? '')
  return /not found|404|网络|timeout/i.test(msg)
}

function mergeSnapshotFromApi(data: {
  schemaVersion: number
  globalDismissed: boolean
  tours: Array<{
    tourId: string
    status: OnboardingTourStatus
    tourVersion: number
    lastStepId?: string | null
    updatedAt: string
  }>
  updatedAt: string
}) {
  useOnboardingStore.getState().mergeServerSnapshot({
    schemaVersion: data.schemaVersion,
    globalDismissed: data.globalDismissed,
    tours: data.tours,
    updatedAt: data.updatedAt
  })
}

function ensureUserBound() {
  const user = useUserStore.getState().user
  const uid = user?.id ? String(user.id) : ''
  if (uid) useOnboardingStore.getState().bindUser(uid)
  return uid
}

const progressReads = new Map<string, Promise<boolean>>()
let loadedProgressToken: string | null = null
let recentProgressRead: { token: string; state: ReturnType<typeof useOnboardingStore.getState>; at: number } | null = null
const PROGRESS_READ_BURST_MS = 450

function fetchAndMergeFromServer(): Promise<boolean> {
  const token = useUserStore.getState().token
  if (!token) return Promise.resolve(false)
  const running = progressReads.get(token)
  if (running) return running
  if (recentProgressRead?.token === token
    && recentProgressRead.state === useOnboardingStore.getState()
    && Date.now() - recentProgressRead.at < PROGRESS_READ_BURST_MS) return Promise.resolve(true)
  const currentIdentity = () => useUserStore.getState().token === token
  const pending = Promise.resolve().then(async () => {
    if (!currentIdentity()) return false
    ensureUserBound()
    try {
      const data = await userOnboardingProgressGet()
      if (!currentIdentity()) return false
      mergeSnapshotFromApi(data)
      loadedProgressToken = token
      if (useOnboardingStore.getState().pendingSync) {
        await syncPendingToServer()
      } else {
        useOnboardingStore.getState().markSynced()
      }
      // Store changes invalidate this short reuse window, including local progress and logout.
      if (!currentIdentity()) return false
      recentProgressRead = { token, state: useOnboardingStore.getState(), at: Date.now() }
      return true
    } catch (err) {
      if (!currentIdentity()) return false
      if (!isApiUnavailableError(err)) console.warn('[onboarding] progress/get failed', err)
      useOnboardingStore.setState({ hydrated: true })
      return false
    }
  }).finally(() => {
    if (progressReads.get(token) === pending) progressReads.delete(token)
  })
  progressReads.set(token, pending)
  return pending
}

/** 登录成功后拉取服务端进度并合并本地 */
async function handleUserLogin(): Promise<void> {
  await fetchAndMergeFromServer()
}

async function reportTour(
  tourId: string,
  status: OnboardingTourStatus,
  lastStepId?: string
): Promise<void> {
  const tour = getTourById(tourId)
  const tourVersion = tour?.version ?? 1
  const clientUpdatedAt = formatOnboardingClientDateTime()
  const previousState = useOnboardingStore.getState()
  const applied = previousState.applyTourProgress(tourId, {
    status,
    tourVersion,
    lastStepId,
    updatedAt: clientUpdatedAt
  })
  if (!applied) return
  const token = useUserStore.getState().token
  if (!token) return
  const reportedState = useOnboardingStore.getState()

  try {
    const result = await userOnboardingProgressReport({
      tourId,
      status,
      tourVersion,
      lastStepId: lastStepId ?? null,
      clientUpdatedAt
    })
    if (useUserStore.getState().token !== token) return
    if (result.applied === false) {
      await fetchAndMergeFromServer()
      return
    }
    if (!previousState.pendingSync && useOnboardingStore.getState() === reportedState) reportedState.markSynced()
  } catch (err) {
    if (!isApiUnavailableError(err)) {
      console.warn('[onboarding] progress/report failed', err)
    }
  }
}

const pendingSyncRequests = new Map<string, Promise<void>>()

function syncPendingToServer(): Promise<void> {
  const token = useUserStore.getState().token
  if (!token) return Promise.resolve()
  const running = pendingSyncRequests.get(token)
  if (running) return running
  const pending = performPendingSync(token).finally(() => {
    if (pendingSyncRequests.get(token) === pending) pendingSyncRequests.delete(token)
  })
  pendingSyncRequests.set(token, pending)
  return pending
}

async function performPendingSync(token: string): Promise<void> {
  const store = useOnboardingStore.getState()
  if (useUserStore.getState().token !== token || !store.pendingSync) return
  const tours = buildOnboardingSyncTours(store.tours)
  if (!tours.length) {
    useOnboardingStore.getState().markSynced()
    return
  }
  try {
    const data = await userOnboardingProgressSync({ tours })
    if (useUserStore.getState().token !== token) return
    mergeSnapshotFromApi(data)
    useOnboardingStore.getState().markSynced()
  } catch (err) {
    if (useUserStore.getState().token !== token) return
    // Older deployments can have a narrower enabled Tour whitelist than this Web build.
    // A single rejected row must not keep the whole local progress batch failing on every login.
    if (apiErrorMessage(err) === '参数错误') {
      for (const tour of tours) {
        if (useUserStore.getState().token !== token) return
        try { await userOnboardingProgressReport(tour) }
        catch { /* Keep this Tour locally when the server has not enabled it. */ }
      }
      if (useUserStore.getState().token !== token) return
      useOnboardingStore.getState().markSynced()
      return
    }
    if (!isApiUnavailableError(err)) {
      console.warn('[onboarding] progress/sync failed:', apiErrorMessage(err))
    }
  }
}

async function setGlobalDismissed(dismissed: boolean): Promise<void> {
  useOnboardingStore.getState().setGlobalDismissed(dismissed)
  if (!useUserStore.getState().token) return
  try {
    const data = await userOnboardingProgressDismiss({ dismissed })
    mergeSnapshotFromApi(data)
    useOnboardingStore.getState().markSynced()
  } catch (err) {
    if (!isApiUnavailableError(err)) {
      console.warn('[onboarding] progress/dismiss failed', err)
    }
  }
}

/**
 * 引导进度同步（模块级单例，可在组件内或回调/引擎等非组件上下文调用）。
 * `store` 为 Zustand store 句柄：渲染读取用 selector，回调读取用 `store.getState()`。
 * 非 React Hook（不依赖组件上下文），故不以 use 前缀命名。
 */
export function getOnboardingProgress() {
  return {
    store: useOnboardingStore,
    ensureUserBound,
    fetchAndMergeFromServer,
    hasLoadedServerProgress: (token: string) => loadedProgressToken === token,
    handleUserLogin,
    reportTour,
    syncPendingToServer,
    setGlobalDismissed
  }
}
