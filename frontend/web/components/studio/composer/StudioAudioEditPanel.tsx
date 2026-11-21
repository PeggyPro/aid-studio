'use client'

import { useRef } from 'react'
import { ArrowUpOutlined, CloseOutlined } from '@ant-design/icons'
import { InputNumber } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import type { StudioAudioEdit } from '@/types/studio-audio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioAudioEditor } from '@/stores/studioAudioEditor'
import { runStudioAudioOperation } from '@/hooks/studio/useStudioAudioOperation'
import { audioDerivedTitle, audioEditProblem, AUDIO_OPERATION_LABELS } from '@/utils/studio/studioAudioEdit'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import '../canvas/studio-audio-tools.css'

export function StudioAudioEditPanel({ node, edit, sourceEditor }: { node: StudioCanvasNode; edit: StudioAudioEdit; sourceEditor: boolean }) {
  const submitting = useRef(false)
  const busy = !sourceEditor && (node.data.status === 'generating' || Boolean(node.data.audioRunId))
  const problem = audioEditProblem(edit)
  const patch = (value: Partial<StudioAudioEdit>) => {
    if (busy) return
    if (sourceEditor) useStudioAudioEditor.getState().patch(value)
    else useStudioUiStore.getState().updateNodeData(node.id, { audioEdit: { ...edit, ...value } }, false)
  }
  const cancel = () => {
    if (busy) return
    if (sourceEditor) useStudioAudioEditor.getState().close()
    else { const store = useStudioUiStore.getState(); store.deleteNodes([node.id]); store.selectNodes([edit.sourceNodeId]) }
  }
  const submit = async () => {
    if (busy || problem || submitting.current) return
    submitting.current = true
    try {
      const state = useStudioUiStore.getState()
      if (sourceEditor && state.nodes.find((item) => item.id === node.id)?.data.mediaUrl !== edit.sourceAudioUrl) return
      const frozen = structuredClone(edit)
      const id = sourceEditor ? state.deriveAudioNodes(node.id, [{ title: audioDerivedTitle(frozen), audioEdit: frozen, status: 'queued', composerHidden: false, prompt: '' }])[0] : node.id
      if (!id) return
      if (sourceEditor) useStudioAudioEditor.getState().close()
      await runStudioAudioOperation(frozen, id)
    } finally { submitting.current = false }
  }
  const custom = edit.operation === 'CUSTOM_SPLIT'
  return <div className={`studio-audio-edit${custom ? ' studio-audio-edit--custom' : ''}`}>
    <header><StudioCanvasTooltip title="取消编辑"><button type="button" aria-label="取消音频编辑" disabled={busy} onClick={cancel}><CloseOutlined /></button></StudioCanvasTooltip><strong>{AUDIO_OPERATION_LABELS[edit.operation]}</strong></header>
    {edit.operation === 'TRIM' && <div className="studio-audio-edit__range">
      <label>起点 <InputNumber aria-label="音频裁剪起点" min={0} max={edit.endSeconds - .05} step={.01} precision={2} value={edit.startSeconds} disabled={busy} onChange={(value) => { if (value != null) patch({ startSeconds: value }) }} /></label>
      <label>终点 <InputNumber aria-label="音频裁剪终点" min={edit.startSeconds + .05} max={edit.durationSeconds} step={.01} precision={2} value={edit.endSeconds} disabled={busy} onChange={(value) => { if (value != null) patch({ endSeconds: value }) }} /></label>
    </div>}
    {edit.operation === 'SPEED' && <div className="studio-audio-edit__speed">
      <span>0.2x</span><input aria-label="音频变速滑条" type="range" min="0.2" max="3" step="0.01" value={edit.speed} disabled={busy} onChange={(event) => patch({ speed: Number(event.target.value) })} /><span>3x</span>
      <InputNumber aria-label="音频变速倍数" min={.2} max={3} step={.01} precision={2} value={edit.speed} disabled={busy} onChange={(value) => { if (value != null) patch({ speed: value }) }} suffix="x" />
    </div>}
    {custom && <textarea aria-label="自定义切分要求" maxLength={2000} value={edit.instruction} disabled={busy} placeholder={'请输入所需截取的内容，例如“截取0–2秒”、“删除10–15秒”、“在第5秒和第12秒处切开”、“分别截取0–3秒和8–12秒”'} onChange={(event) => patch({ instruction: event.target.value })} />}
    <footer>{custom && <button type="button" disabled={busy} onClick={cancel}>取消</button>}
      <StudioCanvasTooltip title={problem || (busy ? '处理中' : '生成新音频')}><button type="button" className="studio-audio-edit__submit" disabled={busy || Boolean(problem)} aria-label={custom ? '确认切分' : '生成音频'} onClick={() => void submit()}>
        {busy ? '处理中…' : custom ? '确认切分' : edit.operation === 'TRIM' ? '生成' : <ArrowUpOutlined />}
      </button></StudioCanvasTooltip>
    </footer>
  </div>
}
