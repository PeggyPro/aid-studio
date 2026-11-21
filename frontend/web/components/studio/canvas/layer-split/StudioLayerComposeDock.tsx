'use client'

import { CloseOutlined } from '@ant-design/icons'
import { useStudioLayerSplitActions } from '@/hooks/studio/useStudioLayerSplitActions'
import type { StudioCanvasNode } from '@/types/studio'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioLayerComposeDock({
  node,
  sourceNode
}: {
  node: StudioCanvasNode
  sourceNode: StudioCanvasNode
}) {
  const { mergeLayers } = useStudioLayerSplitActions(sourceNode)
  const layers = node.data.layerSeparation?.layers ?? []
  const canMerge = layers.length > 0

  return (
    <div className="studio-layer-compose-panel" role="toolbar" aria-label="图层合并">
      <CloseOutlined aria-hidden className="opacity-50" />
      <p className="studio-layer-compose-panel__copy m-0">
        调整图层位置或尺寸，合并生成新图片（不消耗积分）
      </p>
      <button
        type="button"
        disabled={!canMerge}
        className="studio-layer-split-panel__btn studio-layer-split-panel__btn--primary"
        onClick={() => { void mergeLayers(node) }}
      >
        合并
      </button>
    </div>
  )
}
