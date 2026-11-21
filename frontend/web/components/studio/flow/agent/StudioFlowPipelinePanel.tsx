'use client'

import { useEffect, useState } from 'react'
import { Button } from 'antd'
import { PlayCircleOutlined, StepForwardOutlined } from '@ant-design/icons'
import {
  STUDIO_FLOW_PIPELINE_STAGES,
  createStudioFlowPipelineState,
  nextStudioFlowPipelineStage,
  readStudioFlowPipelineState,
  writeStudioFlowPipelineState,
  type StudioFlowPipelineState
} from '@/utils/studio/studioFlowPipeline'

export function StudioFlowPipelinePanel({
  projectId,
  episodeId
}: {
  projectId: number
  episodeId: number
}) {
  const [pipeline, setPipeline] = useState<StudioFlowPipelineState | null>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPipeline(readStudioFlowPipelineState(projectId, episodeId))
    }, 0)
    return () => window.clearTimeout(timer)
  }, [episodeId, projectId])

  const currentStage = STUDIO_FLOW_PIPELINE_STAGES.find((stage) => stage.id === pipeline?.stageId)

  const startPipeline = () => {
    const next = createStudioFlowPipelineState(projectId, episodeId)
    writeStudioFlowPipelineState(next)
    setPipeline(next)
  }

  const confirmStage = () => {
    if (!pipeline) return
    const nextStageId = nextStudioFlowPipelineStage(pipeline.stageId)
    if (!nextStageId) {
      const completed = {
        ...pipeline,
        phase: 'completed' as const,
        updatedAt: new Date().toISOString()
      }
      writeStudioFlowPipelineState(completed)
      setPipeline(completed)
      return
    }
    const next = {
      ...pipeline,
      stageId: nextStageId,
      phase: 'await-confirm' as const,
      updatedAt: new Date().toISOString()
    }
    writeStudioFlowPipelineState(next)
    setPipeline(next)
  }

  if (!pipeline) {
    return (
      <article className="studio-flow-pipeline">
        <header><strong>全自动流水线</strong></header>
        <p>从项目配置到交付分阶段推进；每步需你确认后再进入下一阶段。</p>
        <Button block type="primary" icon={<PlayCircleOutlined />} onClick={startPipeline}>
          启动全自动创作
        </Button>
      </article>
    )
  }

  return (
    <article className="studio-flow-pipeline">
      <header><strong>全自动 · {currentStage?.title ?? pipeline.stageId}</strong></header>
      <p>{currentStage?.summary}</p>
      <p className="studio-flow-pipeline__phase">
        {pipeline.phase === 'completed' ? '流水线已完成' : '等待确认后进入下一阶段'}
      </p>
      {pipeline.phase !== 'completed' ? (
        <Button block type="primary" icon={<StepForwardOutlined />} onClick={confirmStage}>
          确认并进入下一阶段
        </Button>
      ) : null}
    </article>
  )
}
