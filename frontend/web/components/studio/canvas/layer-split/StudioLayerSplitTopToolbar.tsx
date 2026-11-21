'use client'

import { CloseOutlined, RedoOutlined, UndoOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  redoLayerSplitSession,
  undoLayerSplitSession
} from '@/utils/studio/studioLayerSplit'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioLayerSplitTopToolbar({ projectMode = false }: { projectMode?: boolean }) {
  const session = useStudioUiStore((state) => state.layerSplitSession)
  const closeLayerSplitSession = useStudioUiStore((state) => state.closeLayerSplitSession)
  const patchLayerSplitSession = useStudioUiStore((state) => state.patchLayerSplitSession)

  if (!session) return null

  return (
    <div className="studio-layer-split-top" role="toolbar" aria-label="图层分离">
      <button type="button" aria-label="关闭图层分离" onClick={() => closeLayerSplitSession()}>
        <CloseOutlined />
        <span>图层分离</span>
      </button>
      {!projectMode ? <>
        <span className="studio-layer-split-top__divider" aria-hidden />
        <button type="button" aria-label="撤销" disabled={session.historyPast.length === 0}
          onClick={() => patchLayerSplitSession(undoLayerSplitSession(session))}>
          <UndoOutlined />
        </button>
        <button type="button" aria-label="重做" disabled={session.historyFuture.length === 0}
          onClick={() => patchLayerSplitSession(redoLayerSplitSession(session))}>
          <RedoOutlined />
        </button>
      </> : null}
    </div>
  )
}
