'use client'

import { Modal } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { studioEditorSourceChanged } from '@/utils/studio/studioEditorSource'

/** Keep the draft mounted, but never silently apply old coordinates to a newly selected result. */
export function StudioEditorSourceGuard() {
  const session = useStudioUiStore(state => state.imageEditSession ?? state.videoEditSession
    ?? state.videoClipSession ?? state.touchEditSession ?? state.layerSplitSession)
  const node = useStudioUiStore(state => state.nodes.find(item => item.id === session?.nodeId))
  const changed = Boolean(session && node && studioEditorSourceChanged(session.sourceIdentity, node))
  const restart = () => {
    const state = useStudioUiStore.getState()
    if (state.imageEditSession === session && state.imageEditSession) {
      state.openImageEditSession({ ...state.imageEditSession, rect: { x: 0, y: 0, w: 1, h: 1 }, selectedCells: [], regionSelected: false })
    } else if (state.videoEditSession === session && state.videoEditSession) state.openVideoEditSession(state.videoEditSession)
    else if (state.videoClipSession === session && state.videoClipSession) state.openVideoClipSession({ ...state.videoClipSession,
      range: { startSeconds: 0, endSeconds: 0, durationSeconds: 0 }, cropRect: undefined })
    else if (state.touchEditSession === session && state.touchEditSession) state.openTouchEditSession(state.touchEditSession)
    else if (state.layerSplitSession === session && state.layerSplitSession) state.openLayerSplitSession(state.layerSplitSession)
  }
  const close = () => {
    const state = useStudioUiStore.getState()
    if (state.imageEditSession === session) state.closeImageEditSession()
    else if (state.videoEditSession === session) state.closeVideoEditSession()
    else if (state.videoClipSession === session) state.closeVideoClipSession()
    else if (state.touchEditSession === session) state.closeTouchEditSession()
    else if (state.layerSplitSession === session) state.closeLayerSplitSession()
  }
  return <Modal open={changed} title="当前编辑的素材已更新" getContainer={false} closable={false}
    keyboard={false} mask={{ closable: false }} okText="在新素材上重新编辑" cancelText="关闭当前编辑" onOk={restart} onCancel={close}>
    <p>任务结果或素材选择发生了变化。当前编辑草稿仍保留，尚未提交。</p>
    <p>重新编辑会清除旧素材上的蒙版、选区或裁切范围，重绘文字会保留。</p>
  </Modal>
}
