'use client'

/** 原 pages/create/story-script.vue（definePageMeta layout:'create'）：剧本创作步骤薄页面 */

import { useScriptLimits } from '@/hooks/useScriptLimits'
import { Suspense, useCallback, useEffect, useId, useRef, useState } from 'react'
import { message, Modal } from 'antd'
import dynamic from 'next/dynamic'
import { StoryScript, type StoryScriptHandle } from '~/components/steps/StoryScript'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import { readHomeScreenplayRequest, type HomeScreenplayRequest } from '~/utils/homeComposerScreenplay'
import { isPublicProjectPreviewQuery } from '~/utils/publicProjectPreviewNavigation'
import {
  htmlToPlainText,
  htmlPureTextCharCount,
  isHtmlContentEmpty,
  scriptApiTextToEditorHtml,
} from '~/utils/htmlPlain'
import {
  formatEditorSelectionLocation,
  type EditorTextReplacement,
  type EditorTextSelection
} from '~/utils/quill/editorTextSelection'
import {
  editorTextSelectionKey,
  normalizeStoryScriptAgentReferences,
  parseStoryScriptAgentReplacements,
  storyScriptAgentReferencesOverlap
} from '~/utils/storyScriptAgentReference'
import '~/components/steps/story-script-agent/story-script-agent.css'

const StoryScriptEditConversation = dynamic(
  () => import('~/components/steps/story-script-agent/StoryScriptEditConversation').then(module => module.StoryScriptEditConversation),
  { ssr: false, loading: () => <p role="status">正在加载剧本对话…</p> },
)

function StoryScriptStepClient() {
  const storyScriptRef = useRef<StoryScriptHandle | null>(null)
  const [agentState, setAgentState] = useState<'idle' | 'open' | 'collapsed'>('idle')
  const [homeLaunch, setHomeLaunch] = useState<{ projectId: number; request: HomeScreenplayRequest } | null>(null)
  const agentPanelId = useId()
  const agentTriggerId = `${agentPanelId}-trigger`
  function openAgent() {
    setAgentState('open')
    window.requestAnimationFrame(() => document.getElementById(agentPanelId)?.focus({ preventScroll: true }))
  }
  const [agentReferenceState, setAgentReferenceState] = useState<{
    projectId: number | null
    episodeId: number | null
    selections: EditorTextSelection[]
  } | null>(null)
  const route = useRouteLike()
  const content = useCreationStore((s) => s.formData.storyScript.content)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const scriptLimits = useScriptLimits()
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const routeProjectId = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const projectId =
    Number.isFinite(routeProjectId) && routeProjectId > 0
      ? routeProjectId
      : currentProjectId && currentProjectId > 0
        ? currentProjectId
        : null
  const storeMatchesProject = projectId != null && currentProjectId === projectId
  const routeEpisodeId = Number(route.query.episodeId)
  const episodeId =
    storeMatchesProject && currentProjectType === 'movie'
      ? 0
      : route.query.episodeId != null && Number.isFinite(routeEpisodeId) && routeEpisodeId >= 0
        ? routeEpisodeId
        : storeMatchesProject && currentEpisodeId != null && currentEpisodeId > 0
          ? currentEpisodeId
          : null
  const currentScriptScope = storeMatchesProject && episodeId != null
    && (currentProjectType === 'movie' ? episodeId === 0 : currentEpisodeId === episodeId)
  const isPublicPreview = isPublicProjectPreviewQuery(route.query)
  useEffect(() => {
    if (!currentScriptScope || projectId == null || episodeId !== 0 || isPublicPreview) return
    const request = readHomeScreenplayRequest(projectId)
    if (request) setHomeLaunch({ projectId, request })
  }, [currentScriptScope, projectId, episodeId, isPublicPreview])
  const initialRequest = currentScriptScope && episodeId === 0 && !isPublicPreview
    && homeLaunch?.projectId === projectId ? homeLaunch.request : undefined
  const panelState = agentState === 'idle' && initialRequest ? 'open' : agentState
  const focusScriptEditor = useCallback(() => storyScriptRef.current?.focusEditor() ?? false, [])
  const agentReferences =
    agentReferenceState?.projectId === projectId && agentReferenceState.episodeId === episodeId
      ? agentReferenceState.selections
      : []
  function onUpdate(v: string) {
    useCreationStore.getState().updateFormData({ storyScript: { content: v } })
  }

  function addAgentReference(selection: EditorTextSelection) {
    const existing = agentReferences
    if (
      existing.some(
        (reference) => editorTextSelectionKey(reference) === editorTextSelectionKey(selection)
      )
    ) {
      openAgent()
      return
    }
    if (existing.some((reference) => storyScriptAgentReferencesOverlap(reference, selection))) {
      message.warning('该选段与已有批注重叠，请重新选择不重叠的内容')
      return
    }
    setAgentReferenceState({
      projectId,
      episodeId,
      selections: normalizeStoryScriptAgentReferences([...existing, selection])
    })
    openAgent()
  }

  function applyAgentScript(raw: string, references?: EditorTextSelection[]) {
    const stillOpen = () => {
      const current = useCreationStore.getState()
      return projectId != null && episodeId != null && current.currentProjectId === projectId
        && (current.currentProjectType === 'movie' ? 0 : current.currentEpisodeId) === episodeId
    }
    const assertCurrentScript = () => {
      if (!stillOpen()) throw new Error('当前打开的剧本已切换，请返回原剧本后再带入')
    }
    const normalizedReferences = normalizeStoryScriptAgentReferences(references)
    if (normalizedReferences.length) {
      let replacements: EditorTextReplacement[]
      if (normalizedReferences.length === 1) {
        replacements = [
          {
            selection: normalizedReferences[0],
            replacement: htmlToPlainText(scriptApiTextToEditorHtml(raw))
          }
        ]
      } else {
        const parsed = parseStoryScriptAgentReplacements(raw, normalizedReferences.length)
        if (!parsed) {
          message.warning('Agent 未返回完整的多批注修改结果，请补充要求后重新生成')
          return
        }
        replacements = parsed.map((item) => ({
          selection: normalizedReferences[item.referenceIndex],
          replacement: htmlToPlainText(scriptApiTextToEditorHtml(item.replacement))
        }))
      }
      const applySelections = () => {
        assertCurrentScript()
        const result =
          storyScriptRef.current?.applyAgentSelectionEdits(replacements) ?? 'unavailable'
        if (result === 'applied') {
          message.success(
            normalizedReferences.length > 1
              ? `已应用 ${normalizedReferences.length} 个剧本批注`
              : '已替换所选剧本段落'
          )
          return
        }
        if (result === 'stale') {
          message.warning('至少一个原选段已发生变化，请重新选择后再带入')
          return
        }
        if (result === 'limit') {
          message.warning('替换后将超过当前剧本字数上限')
          return
        }
        if (result === 'empty') {
          message.warning('Agent 暂未返回可替换内容')
          return
        }
        message.warning('编辑器尚未就绪，请稍后重试')
      }
      Modal.confirm({
        title:
          normalizedReferences.length > 1
            ? `应用 ${normalizedReferences.length} 个剧本批注？`
            : '替换选中的剧本段落？',
        content:
          normalizedReferences.length > 1
            ? '应用前会逐一核对所有选段原文；任一选段发生变化时，本次不会修改任何内容。'
            : `${formatEditorSelectionLocation(normalizedReferences[0])}，替换前会核对原文，避免覆盖已经修改的内容。`,
        okText: '确认替换',
        cancelText: '取消',
        onOk: applySelections
      })
      return
    }
    const nextHtml = scriptApiTextToEditorHtml(raw)
    if (!nextHtml) {
      message.warning('Agent 文档暂无可带入内容')
      return
    }
    const maxLength = currentProjectType === 'series' ? scriptLimits?.episodeMaxChars : scriptLimits?.movieMaxChars
    if (maxLength !== undefined && htmlPureTextCharCount(nextHtml) > maxLength) {
      message.warning(`生成内容超过当前剧本 ${maxLength.toLocaleString('zh-CN')} 字上限，请先精简`)
      return
    }
    const apply = () => {
      assertCurrentScript()
      if (useCreationStore.getState().formData.storyScript.content !== content) throw new Error('当前剧本已修改，请重新确认后带入')
      onUpdate(nextHtml)
      message.success('已带入当前剧本')
    }
    if (isHtmlContentEmpty(content) && stillOpen()) {
      apply()
      return
    }
    Modal.confirm({
      title: '替换当前剧本？',
      content: '带入 Agent 文档会覆盖编辑器内已有内容。',
      okText: '确认带入',
      cancelText: '取消',
      onOk: apply
    })
  }

  return (
    <div className="story-script-agent-layout">
      <div className="story-script-agent-layout__editor">
        <StoryScript
          ref={storyScriptRef}
          value={content}
          onChange={onUpdate}
          agentOpen={panelState === 'open'}
          agentPanelId={agentPanelId}
          agentTriggerId={agentTriggerId}
          onAgentToggle={openAgent}
          onAgentReference={addAgentReference}
        />
      </div>
      {panelState !== 'idle' ? <StoryScriptEditConversation
        id={agentPanelId}
        state={panelState}
        projectId={currentScriptScope ? projectId : null}
        episodeId={currentScriptScope ? episodeId : null}
        references={agentReferences}
        scriptEmpty={isHtmlContentEmpty(content)}
        initialRequest={initialRequest}
        onClearReferences={() => setAgentReferenceState(null)}
        onApplyHistoricalScript={applyAgentScript}
        onLocateEditor={focusScriptEditor}
        onClose={() => {
          setAgentState('collapsed')
          window.requestAnimationFrame(() => document.getElementById(agentTriggerId)?.focus({ preventScroll: true }))
        }}
      /> : null}
    </div>
  )
}

export default function StoryScriptStepPage() {
  return (
    <Suspense fallback={null}>
      <StoryScriptStepClient />
    </Suspense>
  )
}
