'use client'

import { useStudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { StudioImageLightingPanel } from './StudioImageLightingPanel'

export function StudioNodeImageLightingDock({ node }: { node: StudioCanvasNode }) {
  const closeImageLighting = useStudioUiStore((state) => state.closeImageLighting)
  const actions = useStudioImageActions(node)

  return (
    <StudioImageLightingPanel
      node={node}
      submitDisabledReason={actions.reasonFor('edit')}
      onClose={closeImageLighting}
      onSubmit={(prompt) => actions.edit(prompt, '打光', node.data.imagePurpose)}
      storyboardId={actions.binding.storyboardId}
      editModelCode={actions.pools.edit[0]?.modelCode}
      aspectRatio={node.data.generationOptions?.aspectRatio || actions.pools.edit[0]?.defaultAspectRatio || '1:1'}
      quality={actions.pools.edit[0]?.defaultSizeCode || node.data.generationOptions?.quality || '2K'}
    />
  )
}
