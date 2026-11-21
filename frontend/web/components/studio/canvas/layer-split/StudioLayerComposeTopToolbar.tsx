'use client'

import {
  ColumnHeightOutlined,
  ColumnWidthOutlined,
  DownloadOutlined,
  ExpandOutlined,
  HighlightOutlined,
  RotateRightOutlined
} from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import {
  flipLayerTransform,
  maximizeLayerTransform,
  patchLayerInSeparation,
  rotateLayerTransform
} from '@/utils/studio/studioLayerSeparation'
import { STUDIO_MASK_BRUSH_DEFAULT, fullCropRect } from '@/utils/studio/studioImageAiTools'
import '@/components/studio/composer/studio-image-ai-tools.css'

export function StudioLayerComposeTopToolbar({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const openImageEditSession = useStudioUiStore((state) => state.openImageEditSession)
  const state = node.data.layerSeparation
  if (!state) return null
  const active = state.layers.find((item) => item.id === state.activeLayerId) ?? state.layers[0]
  if (!active) return null

  const patchActive = (transform = active.transform) => {
    updateNodeData(node.id, {
      layerSeparation: patchLayerInSeparation(state, active.id, { transform })
    })
  }

  return (
    <div className="studio-layer-split-top" role="toolbar" aria-label="图层编辑">
      <button
        type="button"
        aria-label="图层重绘"
        onClick={() => {
          openImageEditSession({
            mode: 'repaint',
            nodeId: node.id,
            aspectRatio: node.data.generationOptions?.aspectRatio || '1:1',
            rect: fullCropRect(),
            maskTool: 'brush',
            brushSize: STUDIO_MASK_BRUSH_DEFAULT,
            repaintPrompt: ''
          })
        }}
      >
        <HighlightOutlined />
        <span>图层重绘</span>
      </button>
      <span className="studio-layer-split-top__divider" aria-hidden />
      <button type="button" aria-label="旋转" onClick={() => patchActive(rotateLayerTransform(active.transform))}>
        <RotateRightOutlined />
      </button>
      <button type="button" aria-label="水平翻转" onClick={() => patchActive(flipLayerTransform(active.transform, 'x'))}>
        <ColumnWidthOutlined />
      </button>
      <button type="button" aria-label="垂直翻转" onClick={() => patchActive(flipLayerTransform(active.transform, 'y'))}>
        <ColumnHeightOutlined />
      </button>
      <a
        aria-label="下载"
        href={active.mediaUrl}
        download
        target="_blank"
        rel="noreferrer"
      >
        <DownloadOutlined />
      </a>
      <button type="button" aria-label="放大铺满" onClick={() => patchActive(maximizeLayerTransform(active.transform))}>
        <ExpandOutlined />
      </button>
    </div>
  )
}
