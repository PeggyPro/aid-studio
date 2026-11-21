export type StudioFlowPipelineStageId =
  | 'p0-create'
  | 'p1-config'
  | 'p2-script'
  | 'p3-rps'
  | 'p4-rps-image'
  | 'p5-storyboard'
  | 'p6-video'
  | 'p7-delivery'

export type StudioFlowPipelinePhase = 'running' | 'await-confirm' | 'paused' | 'completed'

export interface StudioFlowPipelineState {
  projectId: number
  episodeId: number
  stageId: StudioFlowPipelineStageId
  phase: StudioFlowPipelinePhase
  updatedAt: string
}

export const STUDIO_FLOW_PIPELINE_STAGES: Array<{
  id: StudioFlowPipelineStageId
  title: string
  summary: string
}> = [
  { id: 'p0-create', title: '创建项目', summary: '确认作品类型与名称' },
  { id: 'p1-config', title: '项目配置', summary: '确认风格、模型与比例' },
  { id: 'p2-script', title: '剧本', summary: '生成或确认剧本正文' },
  { id: 'p3-rps', title: '形态', summary: '提取角色/场景/道具形态' },
  { id: 'p4-rps-image', title: '形态图', summary: '批量生成形态参考图' },
  { id: 'p5-storyboard', title: '分镜脚本', summary: '自动生成分镜脚本' },
  { id: 'p6-video', title: '分镜视频', summary: '批量生成分镜视频' },
  { id: 'p7-delivery', title: '交付', summary: '预览、导出与发布' }
]

export function studioFlowPipelineStorageKey(projectId: number, episodeId: number): string {
  return `studio-flow-pipeline:${projectId}:${episodeId}`
}

export function readStudioFlowPipelineState(
  projectId: number,
  episodeId: number
): StudioFlowPipelineState | null {
  if (typeof window === 'undefined') return null
  const raw = window.localStorage.getItem(studioFlowPipelineStorageKey(projectId, episodeId))
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as StudioFlowPipelineState
    if (parsed.projectId !== projectId || parsed.episodeId !== episodeId) return null
    return parsed
  } catch {
    return null
  }
}

export function writeStudioFlowPipelineState(state: StudioFlowPipelineState): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(
    studioFlowPipelineStorageKey(state.projectId, state.episodeId),
    JSON.stringify(state)
  )
}

export function nextStudioFlowPipelineStage(
  stageId: StudioFlowPipelineStageId
): StudioFlowPipelineStageId | null {
  const index = STUDIO_FLOW_PIPELINE_STAGES.findIndex((stage) => stage.id === stageId)
  if (index < 0 || index >= STUDIO_FLOW_PIPELINE_STAGES.length - 1) return null
  return STUDIO_FLOW_PIPELINE_STAGES[index + 1]?.id ?? null
}

export function createStudioFlowPipelineState(
  projectId: number,
  episodeId: number
): StudioFlowPipelineState {
  return {
    projectId,
    episodeId,
    stageId: 'p1-config',
    phase: 'await-confirm',
    updatedAt: new Date().toISOString()
  }
}
