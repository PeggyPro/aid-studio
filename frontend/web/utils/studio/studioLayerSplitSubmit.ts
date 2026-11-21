import type { LayerSplitData } from '@/types/business-api'
import type { StudioNodeData } from '@/types/studio'
import { buildLayerSeparationFromSplitResult } from '@/utils/studio/studioLayerSeparation'

export function applyLayerSplitSuccess(input: {
  sourceNodeId: string
  resultNodeId: string
  data: LayerSplitData
}): {
  sourcePatch: Partial<StudioNodeData>
  resultPatch: Partial<StudioNodeData>
} {
  const layerSeparation = buildLayerSeparationFromSplitResult({
    sourceNodeId: input.sourceNodeId,
    backgroundResourceId: input.data.backgroundResourceId,
    backgroundUrl: input.data.backgroundUrl,
    canvasWidth: input.data.canvasWidth,
    canvasHeight: input.data.canvasHeight,
    layers: input.data.layers
  })
  const resourceIds = [...new Set([
    ...(input.data.backgroundResourceId ? [input.data.backgroundResourceId] : []),
    ...input.data.layers.map((layer) => layer.resourceId).filter((id): id is string => Boolean(id))
  ])]

  return {
    // Layer separation creates a new node; the source image remains unchanged.
    sourcePatch: {},
    resultPatch: {
      status: 'success',
      mediaUrl: input.data.backgroundUrl,
      mediaKind: 'image',
      imagePurpose: 'layer_separation',
      composerHidden: true,
      layerSeparation,
      canvasResourceIds: resourceIds,
      ...(input.data.backgroundResourceId ? { canvasPrimaryResourceId: input.data.backgroundResourceId } : {}),
      resultSummary: '\u56fe\u5c42\u5206\u79bb',
      errorMessage: undefined
    }
  }
}
