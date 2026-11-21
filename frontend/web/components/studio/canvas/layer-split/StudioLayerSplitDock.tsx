'use client'

import { useStudioLayerSplitActions } from '@/hooks/studio/useStudioLayerSplitActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import {
  STUDIO_LAYER_SPLIT_CREDIT_DISPLAY,
  patchActiveLayerSplitPrompt
} from '@/utils/studio/studioLayerSplit'
import { studioRegionThumbStyle } from '@/utils/studio/studioRegionThumbStyle'
import '@/components/studio/composer/studio-image-ai-tools.css'
import { StudioCreditAmount } from '@/components/studio/composer/StudioCreditSendButton'

export function StudioLayerSplitDock({ node }: { node: StudioCanvasNode }) {
  const session = useStudioUiStore((state) => state.layerSplitSession)
  const patchLayerSplitSession = useStudioUiStore((state) => state.patchLayerSplitSession)
  const closeLayerSplitSession = useStudioUiStore((state) => state.closeLayerSplitSession)
  const { startSplit } = useStudioLayerSplitActions(node)

  if (!session || session.nodeId !== node.id) return null

  const active = session.regions.find((item) => item.id === session.activeRegionId)
  const promptValue = active ? active.prompt : session.draftPrompt

  return (
    <div className="studio-layer-split-panel" role="dialog" aria-label="图层分离面板">
      <h3 className="studio-layer-split-panel__title">图层分离</h3>
      {session.regions.length > 0 ? (
        <ul className="studio-layer-split-panel__regions">
          {session.regions.map((region, index) => (
            <li key={region.id}>
              <button
                type="button"
                className={`studio-layer-split-panel__region${region.id === session.activeRegionId ? ' is-active' : ''}`}
                onClick={() => patchLayerSplitSession({ activeRegionId: region.id, draftPrompt: region.prompt })}
              >
                {node.data.mediaUrl ? (
                  <span
                    className="studio-layer-split-panel__thumb"
                    style={studioRegionThumbStyle(node.data.mediaUrl, region.rect, 28)}
                  />
                ) : (
                  <span className="studio-layer-split-panel__thumb-fallback">区</span>
                )}
                <span>选区 {index + 1}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <textarea
        className="studio-layer-split-panel__prompt"
        value={promptValue}
        rows={3}
        placeholder="描述或框选希望拆分的图层，留空将自动识别并拆分"
        onChange={(event) => {
          patchLayerSplitSession(patchActiveLayerSplitPrompt(session, event.target.value))
        }}
      />
      <div className="studio-layer-split-panel__actions">
        <button
          type="button"
          className="studio-layer-split-panel__btn studio-layer-split-panel__btn--ghost"
          onClick={() => closeLayerSplitSession()}
        >
          取消
        </button>
        <button
          type="button"
          className="studio-layer-split-panel__btn studio-layer-split-panel__btn--primary"
          onClick={() => { void startSplit() }}
        >
          <StudioCreditAmount credits={STUDIO_LAYER_SPLIT_CREDIT_DISPLAY} />
          拆分
        </button>
      </div>
    </div>
  )
}
