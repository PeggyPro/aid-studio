'use client'

import { Modal } from 'antd'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import { hasProjectSceneCharacterPropAssets } from '~/utils/projectConfigAssetGuard'
import { normalizeScriptContent } from '~/utils/scriptContentFingerprint'
import { scriptExtractBaselineStore } from '~/utils/scriptExtractBaseline'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

const SCRIPT_CHANGE_MODAL_TITLE = '剧本已更新'
const SCRIPT_CHANGE_MODAL_CONTENT =
  '检测到剧本有实质性更新，且素材库已有内容。可继续提取以补充新角色/场景/道具，或重新提取（会删除此前自动提取的内容并重新计费）。'

async function resolvePromptContext(): Promise<{
  projectId: number
  episodeId: number
  comicVersion: number
  scriptHtml: string
  hasExtractedAssets: boolean
} | null> {
  const creationStore = useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(creationStore, getRouteLikeSnapshot())
  if (!ctx) return null
  const scriptHtml = useCreationStore.getState().formData.storyScript.content || ''
  const hasExtractedAssets = await hasProjectSceneCharacterPropAssets(ctx.projectId, ctx.episodeId, {
    sceneCharacter: useCreationStore.getState().formData.sceneCharacter
  })
  return {
    projectId: ctx.projectId,
    episodeId: ctx.episodeId,
    comicVersion: Number(useCreationStore.getState().scriptComicVersion) || 0,
    scriptHtml,
    hasExtractedAssets
  }
}

async function shouldPromptNow(): Promise<boolean> {
  const ctx = await resolvePromptContext()
  if (!ctx) return false
  return scriptExtractBaselineStore.shouldPromptScriptChangeExtract({
    ...ctx,
    isExtracting: useCreationStore.getState().isExtractingAssets
  })
}

function markIgnoredForCurrentChange(ctx: {
  projectId: number
  episodeId: number
  comicVersion: number
  scriptHtml: string
}) {
  const n = normalizeScriptContent(ctx.scriptHtml)
  scriptExtractBaselineStore.setIgnoredScriptChangeKey(
    ctx.projectId,
    ctx.episodeId,
    scriptExtractBaselineStore.buildChangeKey(ctx.comicVersion, n.hash)
  )
}

/**
 * 从剧本页进入素材准备前调用（原 composables/useScriptChangeExtractGate.ts）。
 * @returns true=可继续跳转；false=用户取消（应留在剧本页）
 */
async function confirmLeaveScriptToPrepare(): Promise<boolean> {
  const ctx = await resolvePromptContext()
  if (!ctx) return true
  const need = scriptExtractBaselineStore.shouldPromptScriptChangeExtract({
    ...ctx,
    isExtracting: useCreationStore.getState().isExtractingAssets
  })
  if (!need) return true

  return await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: SCRIPT_CHANGE_MODAL_TITLE,
      content: SCRIPT_CHANGE_MODAL_CONTENT,
      okText: '去提取',
      cancelText: '暂不提取',
      centered: true,
      onOk: () => {
        const store = useCreationStore.getState()
        store.setExtractModalActionMode('continueOrReextract')
        store.setPendingOpenContinueExtractModal(true)
        resolve(true)
      },
      onCancel: () => {
        markIgnoredForCurrentChange(ctx)
        useCreationStore.getState().setScriptChangeLightBannerVisible(false)
        resolve(false)
      }
    })
  })
}

function openContinueExtractModal() {
  useCreationStore.getState().setExtractModalActionMode('continueOrReextract')
  useCreationStore.setState({ showExtractAgentModal: true })
}

function dismissLightBanner() {
  void (async () => {
    const ctx = await resolvePromptContext()
    if (ctx) markIgnoredForCurrentChange(ctx)
    useCreationStore.getState().setScriptChangeLightBannerVisible(false)
  })()
}

async function refreshLightBannerOnPreparePage(options?: { skipIfPendingOpen?: boolean }) {
  if (options?.skipIfPendingOpen && useCreationStore.getState().pendingOpenContinueExtractModal) {
    return
  }
  const need = await shouldPromptNow()
  useCreationStore.getState().setScriptChangeLightBannerVisible(need)
}

function consumePendingOpenExtractModal(): boolean {
  const store = useCreationStore.getState()
  if (!store.pendingOpenContinueExtractModal) return false
  store.setPendingOpenContinueExtractModal(false)
  store.setScriptChangeLightBannerVisible(false)
  openContinueExtractModal()
  return true
}

/** 离开剧本页进入素材准备前的强提示；其它入口仅轻提示 */
export function useScriptChangeExtractGate() {
  return {
    confirmLeaveScriptToPrepare,
    refreshLightBannerOnPreparePage,
    consumePendingOpenExtractModal,
    dismissLightBanner,
    openContinueExtractModal,
    shouldPromptNow
  }
}
