'use client'

import { ScissorOutlined, DashboardOutlined, SlidersOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '@/components/studio/theme/StudioChromeIcon'
import type { StudioCanvasNode } from '@/types/studio'
import type { StudioAudioEdit, StudioAudioOperation } from '@/types/studio-audio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioAudioEditor } from '@/stores/studioAudioEditor'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'

export function StudioNodeMediaAudioTools({ node }: { node: StudioCanvasNode }) {
  const open = (operation: StudioAudioOperation) => {
    const state = useStudioUiStore.getState(), source = state.nodes.find((item) => item.id === node.id)
    if (!source?.data.mediaUrl || source.data.flowBinding) return
    const duration = Number(source.data.audioDurationSeconds) || 0
    const edit: StudioAudioEdit = { operation, sourceNodeId: source.id, sourceAudioUrl: source.data.mediaUrl,
      sourceTitle: source.data.title, sourceRecordId: Number(source.data.recordId) || undefined,
      durationSeconds: duration, startSeconds: 0, endSeconds: duration, speed: 1, instruction: '' }
    if (operation === 'CUSTOM_SPLIT') {
      useStudioAudioEditor.getState().close()
      state.deriveAudioNodes(source.id, [{ title: '音频生成器', status: 'empty', audioEdit: edit, composerHidden: false, prompt: '' }])
    } else useStudioAudioEditor.getState().open({ ...edit, scopeKey: state.scopeKey })
  }
  return <>
    <StudioCanvasToolbarButton showLabel label="裁剪" icon={<StudioChromeIcon name="audioTrim" fallback={<ScissorOutlined />} />} disabled={!node.data.audioDurationSeconds} onClick={() => open('TRIM')} />
    <StudioCanvasToolbarButton showLabel label="变速" icon={<StudioChromeIcon name="audioSpeed" fallback={<DashboardOutlined />} />} onClick={() => open('SPEED')} />
    <StudioCanvasToolbarButton showLabel label="自定义切分" icon={<StudioChromeIcon name="audioSplit" fallback={<SlidersOutlined />} />} onClick={() => open('CUSTOM_SPLIT')} />
  </>
}
