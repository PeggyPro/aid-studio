'use client'

import { useEffect, useState } from 'react'
import { Input, Select } from 'antd'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioUiStore } from '@/stores/studioUi'
import { estimateStudioCredits } from '@/utils/studio/studioCreditEstimate'
import { StudioCreditSendButton } from '../composer/StudioCreditSendButton'

const { TextArea } = Input

export function NodeGeneratorControls({
  nodeId,
  compact = false
}: {
  nodeId: string
  compact?: boolean
}) {
  return <NodeGeneratorControlsInner key={nodeId} nodeId={nodeId} compact={compact} />
}

function NodeGeneratorControlsInner({ nodeId, compact }: { nodeId: string; compact: boolean }) {
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  const task = useStudioUiStore((state) => state.tasks.find(
    (item) => item.nodeId === nodeId && (item.status === 'running' || item.status === 'queued')
  ))
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const [promptDraft, setPromptDraft] = useState(node?.data.prompt ?? '')
  const { data: models, loading: modelsLoading } = useStudioModels(node?.data.mediaKind ?? 'image')

  useEffect(() => {
    const defaultModel = models[0]
    if (!node || node.data.model || !defaultModel) return
    updateNodeData(nodeId, {
      model: defaultModel.modelCode,
      modelName: defaultModel.modelName,
      modelCostCredits: defaultModel.costCredits,
      modelIsFree: defaultModel.isFree
    })
  }, [models, node, nodeId, updateNodeData])

  if (!node) return null
  const options = node.data.generationOptions ?? {}
  const isFree = Boolean(node.data.modelIsFree)
  const credits = isFree
    ? 0
    : estimateStudioCredits({
      costCredits: node.data.modelCostCredits,
      mediaKind: node.data.mediaKind,
      count: options.count,
      durationSeconds: options.duration
    })
  const isVideo = node.data.mediaKind === 'video'
  const isAudio = node.data.mediaKind === 'audio'
  const isText = node.data.mediaKind === 'text'
  const commitPrompt = () => {
    if (promptDraft !== node.data.prompt) updateNodeData(nodeId, { prompt: promptDraft })
  }

  return (
    <div className={`studio-generator nodrag nowheel${compact ? ' studio-generator--compact' : ''}`}>
      <TextArea
        aria-label={`${node.data.title}生成描述`}
        value={promptDraft}
        autoSize={{ minRows: compact ? 2 : 3, maxRows: compact ? 4 : 6 }}
        placeholder="描述希望得到的内容、构图和氛围"
        onPointerDown={(event) => event.stopPropagation()}
        onChange={(event) => setPromptDraft(event.target.value)}
        onBlur={commitPrompt}
      />
      <div className="studio-generator__options">
        <Select
          aria-label="生成模型"
          size="small"
          value={node.data.model}
          loading={modelsLoading}
          options={models.map((model) => ({ value: model.modelCode, label: model.modelName }))}
          onChange={(modelCode) => {
            const model = models.find((item) => item.modelCode === modelCode)
            updateNodeData(nodeId, {
              model: modelCode,
              modelName: model?.modelName,
              modelCostCredits: model?.costCredits,
              modelIsFree: model?.isFree
            })
          }}
        />
        {!isText && !isAudio ? (
          <Select
            aria-label="画面比例"
            size="small"
            value={options.aspectRatio ?? '16:9'}
            options={['16:9', '9:16', '1:1', '4:3'].map((value) => ({ value, label: value }))}
            onChange={(aspectRatio) =>
              updateNodeData(nodeId, {
                generationOptions: { ...options, aspectRatio }
              })
            }
          />
        ) : null}
        {isVideo ? (
          <Select
            aria-label="视频时长"
            size="small"
            value={options.duration ?? 5}
            options={[3, 5, 8].map((value) => ({ value, label: `${value} 秒` }))}
            onChange={(duration) =>
              updateNodeData(nodeId, {
                generationOptions: { ...options, duration }
              })
            }
          />
        ) : null}
        {isAudio ? (
          <Select
            aria-label="声音风格"
            size="small"
            value={options.voice ?? '自然叙事'}
            options={['自然叙事', '克制电影感', '轻快对白'].map((value) => ({ value, label: value }))}
            onChange={(voice) =>
              updateNodeData(nodeId, {
                generationOptions: { ...options, voice }
              })
            }
          />
        ) : null}
      </div>
      <div className="studio-generator__actions">
        <span>{task ? task.stage : isFree ? '免费' : `本地预计约 ${credits} 积分`}</span>
        {task ? (
          <span>请在任务区使用后端取消</span>
        ) : (
          <StudioCreditSendButton
            credits={credits}
            isFree={isFree}
            hasContent={Boolean(promptDraft.trim())}
            mode="agent"
            className="studio-generator__send"
            disabled
            disabledReason="此旧生成面板没有真实业务绑定，请使用节点 Composer 中的图片/视频工具"
            onSend={commitPrompt}
          />
        )}
      </div>
    </div>
  )
}
