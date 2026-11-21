'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useState } from 'react'
import { AppstoreOutlined, ControlOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import {
  buildStudioStoryboardCompositionNodePatch,
  getStudioStoryboardComposition
} from '@/utils/studio/studioStoryboardComposition'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import {
  StudioStoryboardCompositionClearIcon,
  StudioStoryboardCompositionPanel
} from './StudioStoryboardCompositionPanel'

/** Composer 输入区前的分镜构图功能 Tag：点开双栏面板，悬停可关闭。 */
export function StudioStoryboardCompositionTag({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const [open, setOpen] = useState(false)
  const item = getStudioStoryboardComposition(node.data.storyboardCompositionId)
  if (!item) return null

  const applyComposition = (id: string) => {
    const next = getStudioStoryboardComposition(id)
    if (!next) return
    const patch = buildStudioStoryboardCompositionNodePatch(next)
    updateNodeData(node.id, {
      storyboardCompositionId: next.id,
      model: undefined,
      modelName: undefined,
      modelCostCredits: undefined,
      modelIsFree: undefined,
      imagePurpose: patch.imagePurpose,
      generationOptions: {
        ...(node.data.generationOptions ?? {}),
        ...(patch.generationOptions ?? {})
      }
    })
    setOpen(false)
  }

  const clearComposition = () => {
    updateNodeData(node.id, { storyboardCompositionId: undefined })
    setOpen(false)
  }

  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      nodePlacement
      arrow={false}
      destroyOnHidden={false}
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
      content={
        <StudioStoryboardCompositionPanel
          activeId={item.id}
          onSelect={applyComposition}
        />
      }
    >
      <button
        type="button"
        className="studio-storyboard-composition-tag"
        aria-label={`分镜构图：${item.label}`}
      >
        <i className="studio-storyboard-composition-tag__icon" aria-hidden>
          <StudioChromeIcon name="nodeStoryboard" fallback={<AppstoreOutlined />} />
        </i>
        <span className="studio-storyboard-composition-tag__label">{item.label}</span>
        <StudioChromeIcon name="skillSettings" size="1em" className="studio-storyboard-composition-tag__gear" fallback={<ControlOutlined className="studio-storyboard-composition-tag__gear" aria-hidden />} />
        <StudioStoryboardCompositionClearIcon onClick={clearComposition} />
      </button>
    </StudioCanvasPopover>
  )
}
