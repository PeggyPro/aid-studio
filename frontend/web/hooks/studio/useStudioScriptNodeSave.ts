'use client'

import { useCallback } from 'react'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { saveStoryScriptAsNewVersion } from '@/utils/storyScriptVersionSave'
import { scriptApiTextToEditorHtml } from '@/utils/htmlPlain'
import type { StudioFlowBinding } from '@/types/studio'
import { resolveStudioScriptNodeText, resolveStudioScriptScopeFromBinding } from '@/utils/studio/studioScriptNode'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

export function useStudioScriptNodeSave(nodeId: string, flowBinding?: StudioFlowBinding) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const runtime = useStudioFlowRuntime()
  const canvasRuntime = useCanvasProjectRuntime()

  return useCallback(
    async (originalText: string) => {
      const trimmed = originalText.trim()
      if (!trimmed) {
        message.warning('剧本内容不能为空')
        return false
      }

      const scopeKey = useStudioUiStore.getState().scopeKey
      return runStudioRequestSingleFlight(`${scopeKey}:${nodeId}:save-script`, async () => {
        const currentNode = useStudioUiStore.getState().nodes.find((node) => node.id === nodeId)
        if (!currentNode || (canvasRuntime && !flowBinding && (!canvasRuntime.canEdit || !canvasRuntime.canMutate || canvasRuntime.isNodeBusy(nodeId)))) {
          message.error('节点已删除、正在处理或画布暂不可编辑，请核对后保存')
          return false
        }
        updateNodeData(
          nodeId,
          {
            ...(flowBinding
              ? { prompt: trimmed, resultSummary: trimmed.slice(0, 180) }
              : { documentContent: trimmed }),
            mediaKind: 'text',
            status: 'success'
          },
          true
        )

        const scope = resolveStudioScriptScopeFromBinding(flowBinding)
        const projectId = scope.projectId ?? useCreationStore.getState().currentProjectId
        const isFlowScript =
          flowBinding?.entityType === 'story_script' || flowBinding?.step === 'story-script'

        if (projectId && isFlowScript) {
          const store = useCreationStore.getState()
          const episodeId =
            store.currentProjectType === 'movie'
              ? 0
              : Number(scope.episodeId ?? store.currentEpisodeId ?? 0)
          try {
            await saveStoryScriptAsNewVersion({ projectId, episodeId }, trimmed, (row) => {
              if (row?.originalText) {
                store.updateFormData({
                  storyScript: {
                    content: scriptApiTextToEditorHtml(String(row.originalText))
                  }
                })
              } else {
                store.updateFormData({
                  storyScript: { content: scriptApiTextToEditorHtml(trimmed) }
                })
              }
            })
            await runtime?.refresh({ taskIntent: 'read' })
          } catch (error) {
            message.error(error instanceof Error ? error.message : '剧本保存失败，请重试')
            return false
          }
        }

        if (canvasRuntime && !flowBinding) {
          try {
            await canvasRuntime.saveNow()
            const savedState = useStudioUiStore.getState()
            const savedNode = savedState.nodes.find((node) => node.id === nodeId)
            if (savedState.scopeKey !== scopeKey || savedState.dirtyRevision > 0 || !savedNode
              || resolveStudioScriptNodeText(savedNode.data) !== trimmed) throw new Error('画布已切换或节点内容已变化，请重新核对保存结果')
          } catch (error) {
            message.error(error instanceof Error ? error.message : '画布节点保存失败，草稿已保留')
            return false
          }
        }
        message.success('剧本已保存')
        return true
      })
    },
    [canvasRuntime, flowBinding, nodeId, runtime, updateNodeData]
  )
}
