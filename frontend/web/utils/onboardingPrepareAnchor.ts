import { create } from 'zustand'
import { useCreationStore } from '~/stores/creation'
import { dispatchOnboardingPrepareAnchor,sleepMs } from '~/utils/onboardingDom'
const SCP_TAB_CLASS = 'onboarding-spotlight-scp-tabs'
const SCP_EXTRACT_MODAL_CLASS = 'onboarding-spotlight-extract-modal'
const SCP_BATCH_CLASS = 'onboarding-spotlight-scp-batch'
const SBS_BATCH_CLASS = 'onboarding-spotlight-sbs-batch'
const SBV_BATCH_CLASS = 'onboarding-spotlight-sbv-batch'
const DUB_BATCH_CLASS = 'onboarding-spotlight-dub-batch'

/**
 * 引导期间的准备态开关（原 Vue 模块级 ref）：
 * 组件渲染读取用 selector，如 `useOnboardingPrepareFlags((s) => s.scpOnboardingShowBatchOps)`；
 * 非组件读取用 `useOnboardingPrepareFlags.getState()`。
 */
interface OnboardingPrepareFlagsState {
  /** 仅在引导第 3～5 步时强制展示「批量操作」，避免整段引导期间多出按钮 */
  scpOnboardingShowBatchOps: boolean
  /** 引导第 2 步允许打开提取弹窗（区别于自动弹窗抑制） */
  scpOnboardingAllowExtractModal: boolean
  /** 分镜设计引导期间强制展示批量操作按钮 */
  sbsOnboardingShowToolbar: boolean
  /** 视频生成引导期间强制展示批量操作按钮 */
  sbvOnboardingShowToolbar: boolean
  /** 音画同步引导期间强制展示批量操作按钮 */
  dubOnboardingShowToolbar: boolean
}

export const useOnboardingPrepareFlags = create<OnboardingPrepareFlagsState>(() => ({
  scpOnboardingShowBatchOps: false,
  scpOnboardingAllowExtractModal: false,
  sbsOnboardingShowToolbar: false,
  sbvOnboardingShowToolbar: false,
  dubOnboardingShowToolbar: false
}))

function resetScpPrepareClasses() {
  if (typeof window === 'undefined') return
  document.documentElement.classList.remove(SCP_TAB_CLASS, SCP_EXTRACT_MODAL_CLASS, SCP_BATCH_CLASS)
}

function resetStoryboardPrepareClasses() {
  if (typeof window === 'undefined') return
  document.documentElement.classList.remove(SBS_BATCH_CLASS, SBV_BATCH_CLASS, DUB_BATCH_CLASS)
}

export function cleanupOnboardingPrepareClasses() {
  if (typeof window === 'undefined') return
  useOnboardingPrepareFlags.setState({
    scpOnboardingShowBatchOps: false,
    scpOnboardingAllowExtractModal: false,
    sbsOnboardingShowToolbar: false,
    sbvOnboardingShowToolbar: false,
    dubOnboardingShowToolbar: false
  })
  resetScpPrepareClasses()
  resetStoryboardPrepareClasses()
  dispatchOnboardingPrepareAnchor('scp-batch-ops')
  dispatchOnboardingPrepareAnchor('sbs-close-dropdown')
  dispatchOnboardingPrepareAnchor('sbv-close-dropdown')
  dispatchOnboardingPrepareAnchor('dub-close-dropdown')
}

async function prepareCreateSceneCharacterAnchor(anchor: string): Promise<void> {
  if (anchor === 'scp-tabs') {
    useOnboardingPrepareFlags.setState({
      scpOnboardingShowBatchOps: false,
      scpOnboardingAllowExtractModal: false
    })
    resetScpPrepareClasses()
    dispatchOnboardingPrepareAnchor('scp-batch-ops')
    useCreationStore.setState({ showExtractAgentModal: false })
    await sleepMs(200)
    document.documentElement.classList.add(SCP_TAB_CLASS)
    return
  }

  if (anchor === 'extract-agent-start') {
    useOnboardingPrepareFlags.setState({
      scpOnboardingShowBatchOps: false,
      scpOnboardingAllowExtractModal: true
    })
    resetScpPrepareClasses()
    dispatchOnboardingPrepareAnchor('scp-batch-ops')
    useCreationStore.setState({ showExtractAgentModal: true })
    await sleepMs(280)
    document.documentElement.classList.add(SCP_EXTRACT_MODAL_CLASS)
    return
  }

  if (anchor === 'scp-batch-ops') {
    useOnboardingPrepareFlags.setState({
      scpOnboardingShowBatchOps: true,
      scpOnboardingAllowExtractModal: false
    })
    resetScpPrepareClasses()
    useCreationStore.setState({ showExtractAgentModal: false })
    await sleepMs(160)
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(80)
    return
  }

  if (anchor === 'scp-batch-form-generate' || anchor === 'scp-batch-image-generate') {
    useOnboardingPrepareFlags.setState({
      scpOnboardingShowBatchOps: true,
      scpOnboardingAllowExtractModal: false
    })
    resetScpPrepareClasses()
    useCreationStore.setState({ showExtractAgentModal: false })
    await sleepMs(140)
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(120)
    document.documentElement.classList.add(SCP_BATCH_CLASS)
  }
}

async function prepareCreateStoryboardScriptAnchor(anchor: string): Promise<void> {
  if (anchor === 'storyboard-panel-list') {
    useOnboardingPrepareFlags.setState({ sbsOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('sbs-close-dropdown')
    await sleepMs(120)
    return
  }

  if (anchor === 'btn-auto-storyboard' || anchor === 'sbs-batch-image') {
    useOnboardingPrepareFlags.setState({ sbsOnboardingShowToolbar: true })
    resetStoryboardPrepareClasses()
    document.documentElement.classList.add(SBS_BATCH_CLASS)
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(200)
    return
  }

  if (anchor === 'btn-next-step') {
    useOnboardingPrepareFlags.setState({ sbsOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('sbs-close-dropdown')
    await sleepMs(120)
  }
}

async function prepareCreateStoryboardVideoAnchor(anchor: string): Promise<void> {
  if (anchor === 'video-panel-list') {
    useOnboardingPrepareFlags.setState({ sbvOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('sbv-close-dropdown')
    await sleepMs(120)
    return
  }

  if (anchor === 'sbv-batch-video') {
    useOnboardingPrepareFlags.setState({ sbvOnboardingShowToolbar: true })
    resetStoryboardPrepareClasses()
    document.documentElement.classList.add(SBV_BATCH_CLASS)
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(200)
    return
  }

  if (anchor === 'btn-next-step') {
    useOnboardingPrepareFlags.setState({ sbvOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('sbv-close-dropdown')
    await sleepMs(120)
  }
}

async function prepareCreateDubbingAnchor(anchor: string): Promise<void> {
  if (anchor === 'dubbing-track') {
    useOnboardingPrepareFlags.setState({ dubOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('dub-close-dropdown')
    await sleepMs(120)
    return
  }

  if (anchor === 'dub-batch-dubbing') {
    useOnboardingPrepareFlags.setState({ dubOnboardingShowToolbar: true })
    resetStoryboardPrepareClasses()
    document.documentElement.classList.add(DUB_BATCH_CLASS)
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(200)
    return
  }

  if (anchor === 'btn-next-step') {
    useOnboardingPrepareFlags.setState({ dubOnboardingShowToolbar: false })
    resetStoryboardPrepareClasses()
    dispatchOnboardingPrepareAnchor('dub-close-dropdown')
    await sleepMs(120)
  }
}

/** 启动阶段只做 DOM 探测，切换步骤时再 prepare */
export function isLazyPrepareTour(tourId: string): boolean {
  return (
    tourId === 'create-scene-character' ||
    tourId === 'create-storyboard-script' ||
    tourId === 'create-storyboard-video' ||
    tourId === 'create-dubbing'
  )
}

/** Spotlight 切换步骤前：关闭/打开弹窗、展开下拉等，保证锚点可见且位于蒙层之上 */
export async function prepareOnboardingAnchor(anchor: string, tourId?: string): Promise<void> {
  if (typeof window === 'undefined') return

  if (tourId === 'create-scene-character') {
    await prepareCreateSceneCharacterAnchor(anchor)
    return
  }

  if (tourId === 'create-storyboard-script') {
    await prepareCreateStoryboardScriptAnchor(anchor)
    return
  }

  if (tourId === 'create-storyboard-video') {
    await prepareCreateStoryboardVideoAnchor(anchor)
    return
  }

  if (tourId === 'create-dubbing') {
    await prepareCreateDubbingAnchor(anchor)
    return
  }

  if (anchor === 'btn-auto-storyboard') {
    dispatchOnboardingPrepareAnchor(anchor)
    await sleepMs(180)
  }
}
