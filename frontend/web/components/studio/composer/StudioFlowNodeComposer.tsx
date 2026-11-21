'use client'

import { Button, Tag, message } from 'antd'
import { EditOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { openStudioFlowBoundEntity } from '@/utils/studio/studioFlowEntityEditor'

const STEP_ACTION: Record<NonNullable<StudioCanvasNode['data']['flowBinding']>['step'], string> = {
  'global-setting': '编辑项目配置',
  'story-script': '打开剧本编辑器',
  'scene-character': '打开素材编辑器',
  'storyboard-script': '打开分镜编辑器',
  'storyboard-video': '打开视频编辑器',
  dubbing: '打开配音编辑器',
  preview: '打开成品时间轴'
}

/** 流程节点仅投影七步流程参数，不显示自由画布新增的 Composer 功能。 */
export function StudioFlowNodeComposer({ node }: { node: StudioCanvasNode }) {
  const globalSetting = useCreationStore((state) => state.formData.globalSetting)
  const storyboardPanels = useCreationStore((state) => state.formData.storyboardScript.panels)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const binding = node.data.flowBinding
  if (!binding) return null
  const parameters = binding.step === 'global-setting' || binding.role === 'step' ? [
    globalSetting.creationMode ? `创作模型：${globalSetting.creationMode}` : '',
    globalSetting.aspectRatio ? `比例：${globalSetting.aspectRatio}` : '',
    globalSetting.modelStrategy ? `模型策略：${globalSetting.modelStrategy}` : '',
    globalSetting.scriptType ? `剧本类型：${globalSetting.scriptType}` : ''
  ].filter(Boolean) : []
  const openEditor = () => {
    if (!openStudioFlowBoundEntity(binding, storyboardPanels, projectId)) {
      message.warning('未找到对应的服务端实体，请刷新流程画布后重试')
    }
  }
  return (
    <div className="studio-flow-composer nodrag nowheel">
      <div className="studio-flow-composer__heading">
        <span>{binding.role === 'step' ? '流程控制节点' : '服务端流程实体'}</span>
        <Tag color={node.data.status === 'failed' ? 'error' : node.data.status === 'success' ? 'success' : 'default'}>
          {node.data.status === 'generating' ? '生成中' : node.data.status === 'queued' ? '排队中' : node.data.status === 'success' ? '已完成' : node.data.status === 'failed' ? '失败' : '待处理'}
        </Tag>
      </div>
      {parameters.length ? (
        <div className="studio-flow-composer__parameters">
          {parameters.map((parameter) => <span key={parameter}>{parameter}</span>)}
        </div>
      ) : null}
      <Button type="primary" icon={<EditOutlined />} onClick={openEditor}>
        {STEP_ACTION[binding.step]}
      </Button>
      <small>{binding.role === 'step'
        ? '全局参数由原流程统一管理。'
        : '模型、比例、清晰度和生成记录均在原流程编辑器中操作。'}</small>
    </div>
  )
}
