'use client'

import { useMemo } from 'react'
import { useCreationStore } from '~/stores/creation'
import { useStudioFlowRuntime } from '~/components/studio/flow/StudioFlowRuntimeProvider'
import { selectStudioFlowStepNode } from '~/utils/studio/studioFlowNodeSelection'
import { createAssistantScriptAdoption } from '~/utils/assistant/assistantScriptAdoption'
import { createAssistantScriptTarget } from '~/utils/assistant/assistantScriptTarget'
import { createAssistantMainSeries } from '~/utils/assistant/assistantMainSeries'
import { AssistantEntryPanel } from '@/components/assistant/AssistantEntryPanel'
import { resolveAssistantScriptContext } from '@/utils/assistant/assistantContext'
import type { ReactNode } from 'react'
import StoryScriptAgentPanel from '@/components/steps/story-script-agent/StoryScriptAgentPanel'
import { useStudioFlowApplyScript } from '@/hooks/studio/useStudioFlowApplyScript'
import {
  useStudioStoryScriptAgent,
  type StudioStoryScriptAgentController
} from '@/components/studio/StudioStoryScriptAgentProvider'
import { StudioFlowAutoPipelineBanner } from './StudioFlowAutoPipelineBanner'
import type { useStudioFlowAutoPipeline } from '@/hooks/studio/useStudioFlowAutoPipeline'
import { resolveFlowShortcutEmptyHint } from '@/config/studioCanvasEmptyGuide'
import '@/components/steps/story-script-agent/story-script-agent.css'

type StudioFlowAutoPipelineController = ReturnType<typeof useStudioFlowAutoPipeline>

export function StudioFlowStoryScriptAgentShell({
  onClose,
  autoPipeline,
  composerContextSlot
}: {
  onClose: () => void
  autoPipeline?: StudioFlowAutoPipelineController
  composerContextSlot?: ReactNode
}) {
  const shared = useStudioStoryScriptAgent()
  if (!shared || shared.episodeId == null) return null

  return (
    <StudioFlowStoryScriptAgentShellContent
      projectId={shared.projectId}
      episodeId={shared.episodeId}
      agent={shared.agent}
      onClose={onClose}
      autoPipeline={autoPipeline}
      composerContextSlot={composerContextSlot}
    />
  )
}

function StudioFlowStoryScriptAgentShellContent({
  projectId,
  episodeId,
  agent,
  onClose,
  autoPipeline,
  composerContextSlot
}: {
  projectId: number
  episodeId: number
  agent: StudioStoryScriptAgentController
  onClose: () => void
  autoPipeline?: StudioFlowAutoPipelineController
  composerContextSlot?: ReactNode
}) {
  const locateScriptTarget = useMemo(() => createAssistantScriptTarget(projectId, episodeId, () => selectStudioFlowStepNode('story-script')), [projectId, episodeId])
  const projectType = useCreationStore(state => state.currentProjectType)
  const seriesAdapter = useMemo(() => projectType === 'series' ? createAssistantMainSeries(projectId) : undefined, [projectId, projectType])
  const applyScript = useStudioFlowApplyScript(projectId, episodeId)
  const flowRuntime = useStudioFlowRuntime()
  const refreshFlow = flowRuntime?.refresh
  const adoptionAdapter = useMemo(() => createAssistantScriptAdoption(projectId, episodeId, async () => {
    await refreshFlow?.({ taskIntent: 'read', bypassBurst: true })
    const current = useCreationStore.getState()
    if (current.currentProjectId === projectId && current.currentEpisodeId === episodeId) selectStudioFlowStepNode('story-script')
  }), [projectId, episodeId, refreshFlow])

  return (
    <div className="studio-flow-agent-embed">
      <AssistantEntryPanel open embedded onClose={onClose}
        scope={{ moduleCode: 'UNIVERSAL', projectId: String(projectId), episodeId: String(episodeId) }}
        adoptionAdapter={adoptionAdapter} onLocateTarget={locateScriptTarget} seriesAdapter={seriesAdapter}
        contextLabel="引用当前已保存剧本"
        resolveContext={() => resolveAssistantScriptContext(projectId, episodeId)}
        legacyPanel={<StoryScriptAgentPanel
        runtimeFeedbackEnabled
        key={`${projectId}:${episodeId}`}
        open
        onClose={onClose}
        skills={agent.skills}
        selectedSkillCode={agent.selectedSkillCode}
        onSkillChange={agent.selectSkill}
        selectedModelCode={agent.selectedModelCode}
        onModelChange={agent.selectModel}
        skillsLoading={agent.skillsLoading}
        skillsError={agent.skillsError}
        onSkillsRequest={() => { void agent.loadSkills() }}
        messages={agent.messages}
        conversationScopeKey={`flow-agent:${projectId}:${episodeId}:${agent.selectedSkillCode || 'no-skill'}`}
        sending={agent.sending}
        paused={agent.paused}
        statusText={agent.statusText}
        lastError={agent.lastError}
        canRetry={agent.canRetry}
        canStop={agent.canStop}
        stopping={agent.stopping}
        onRetry={agent.retry}
        onStop={() => void agent.stop()}
        onResumeReceiving={agent.resumeReceiving}
        onSend={agent.send}
        onSubmitInputRequest={agent.submitInputRequest}
        references={[]}
        onReferencesChange={() => {}}
        onApplyScript={applyScript}
        emptyHint={resolveFlowShortcutEmptyHint(
          agent.skills,
          agent.selectedSkillCode,
          '结合当前项目风格生成剧本，确认后一键带入画布，后续素材与分镜将自动推进。'
        )}
        composerContextSlot={composerContextSlot}
        contextSlot={
          autoPipeline ? (
            <StudioFlowAutoPipelineBanner
              state={autoPipeline.state}
              onCancel={autoPipeline.cancel}
              onDismiss={autoPipeline.dismiss}
              onExpand={autoPipeline.expand}
              onAbandon={autoPipeline.abandon}
              onResume={autoPipeline.resume}
              onStart={autoPipeline.confirmStart}
            />
          ) : null
        }
      />} />
    </div>
  )
}
