'use client'

import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { StudioImageMultiAnglePanel } from './StudioImageMultiAnglePanel'

export function StudioNodeImageMultiAngleDock({ node }: { node: StudioCanvasNode }) {
  const closeImageMultiAngle = useStudioUiStore((state) => state.closeImageMultiAngle)
  const actions = useStudioImageActions(node)

  return (
    <StudioImageMultiAnglePanel
      node={node}
      submitDisabledReason={actions.reasonFor('multiView')}
      onClose={closeImageMultiAngle}
      onSubmit={(prompt) => actions.multiView(prompt)}
      storyboardId={actions.binding.storyboardId}
      multiViewModelCode={actions.pools.multiView[0]?.modelCode}
      aspectRatio={node.data.generationOptions?.aspectRatio || actions.pools.multiView[0]?.defaultAspectRatio || '1:1'}
    />
  )
}
