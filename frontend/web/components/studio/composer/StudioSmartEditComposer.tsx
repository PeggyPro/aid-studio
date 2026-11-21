'use client'

import { message } from 'antd'
import { useState } from 'react'
import { LinkOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { openSmartEditEditor, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { useCanvasProjectRuntime } from '../free/CanvasProjectRuntimeContext'
import { StudioComposerFrame } from './StudioComposerFrame'
import { StudioModelPicker } from './StudioModelPicker'
import { useSmartEditGeneration } from '@/hooks/studio/useSmartEditGeneration'
import { startSmartEditJob } from '@/utils/studio/smartEditJob'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'

export function StudioSmartEditComposer({ node }: { node: StudioCanvasNode }) {
  const runtime = useCanvasProjectRuntime()
  const generateNode = useSmartEditGeneration()
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const [ratioOpen, setRatioOpen] = useState(false)
  const exporting = useStudioSmartEdit((state) => state.exportProgress[`${scopeKey}:${node.id}`] !== undefined)
  const disabled = exporting || node.data.status === 'generating' || Boolean(runtime && (!runtime.canEdit || !runtime.canMutate || runtime.nodeEditLockOwner(node.id)))
  const options = node.data.generationOptions || {}
  const patchOptions = (patch: typeof options) => useStudioUiStore.getState().updateNodeData(node.id, { generationOptions: { ...options, ...patch } })
  return <StudioComposerFrame node={node} showCredits={false} allowEmptySubmit readOnly={disabled}
    placeholder="描述想剪成什么效果" submitDisabledReason={node.data.status === 'generating' ? '正在剪辑' : exporting ? '正在导出' : disabled ? '当前画布不可编辑' : null}
    onSubmit={async (prompt) => {
      const result = await startSmartEditJob(node.id, prompt, { generateNode, canEdit: !disabled })
      if (!result.ok) message.warning(result.message)
      else openSmartEditEditor(node.id)
    }} bottomLeft={<>
      <StudioComposerChip icon={<LinkOutlined />} label="从画布选择素材" arrow={false} disabled={disabled} onClick={() => useStudioUiStore.getState().beginReferencePick(node.id)} />
      <StudioCanvasDropdownPopover open={ratioOpen} onOpenChange={setRatioOpen} nodePlacement
        ariaLabel={'\u6210\u7247\u6bd4\u4f8b'} activeKey={options.aspectRatio || '16:9'}
        items={['16:9', '9:16', '1:1', '4:3'].map((ratio) => ({ key: ratio, label: ratio }))}
        onSelect={(value) => patchOptions({ aspectRatio: value })}
        trigger={<StudioComposerChip label={options.aspectRatio || '16:9'} disabled={disabled} active={ratioOpen} />} />
      <label className="studio-composer-chip studio-smart-edit-composer-chip">生成时长 <input type="number" aria-label="生成片段时长" min={1} max={60} value={options.duration || 5} disabled={disabled} style={{ width: 40 }} onChange={(event) => patchOptions({ duration: Math.max(1, Math.min(60, Number(event.target.value))) })} /> 秒</label>
      {node.data.smartEdit?.playbook !== 'mix' ? <StudioModelPicker value={node.data.model} mediaKind="video" onChange={(model, item) => { if (!disabled) useStudioUiStore.getState().updateNodeData(node.id, { model, modelName: item.modelName }) }} /> : null}
    </>} />
}
