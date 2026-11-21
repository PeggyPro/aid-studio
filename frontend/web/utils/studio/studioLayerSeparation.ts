import type {
  StudioLayerItem,
  StudioLayerSeparationState,
  StudioLayerSplitRect,
  StudioLayerTransform,
  StudioNodeData
} from '@/types/studio'

const DEFAULT_LAYER_RECT: StudioLayerSplitRect = { x: 0.15, y: 0.15, w: 0.7, h: 0.7 }

export function createDefaultLayerTransform(rect?: StudioLayerSplitRect): StudioLayerTransform {
  return {
    rect: rect ? { ...rect } : { ...DEFAULT_LAYER_RECT },
    rotationDeg: 0,
    flipX: false,
    flipY: false,
    opacity: 1,
    visible: true
  }
}

export function rotateLayerTransform(t: StudioLayerTransform): StudioLayerTransform {
  return {
    ...t,
    rect: { ...t.rect },
    rotationDeg: (t.rotationDeg + 90) % 360
  }
}

export function flipLayerTransform(t: StudioLayerTransform, axis: 'x' | 'y'): StudioLayerTransform {
  return {
    ...t,
    rect: { ...t.rect },
    flipX: axis === 'x' ? !t.flipX : t.flipX,
    flipY: axis === 'y' ? !t.flipY : t.flipY
  }
}

export function maximizeLayerTransform(t: StudioLayerTransform): StudioLayerTransform {
  return {
    ...t,
    rect: { x: 0, y: 0, w: 1, h: 1 }
  }
}

export function patchLayerInSeparation(
  state: StudioLayerSeparationState,
  layerId: string,
  patch: Partial<StudioLayerItem>
): StudioLayerSeparationState {
  return {
    ...state,
    dirty: true,
    layers: state.layers.map((layer) => {
      if (layer.id !== layerId) return layer
      return {
        ...layer,
        ...patch,
        transform: patch.transform
          ? {
              ...patch.transform,
              rect: { ...patch.transform.rect }
            }
          : { ...layer.transform, rect: { ...layer.transform.rect } }
      }
    })
  }
}

export function buildLayerSeparationFromSplitResult(input: {
  sourceNodeId: string
  backgroundResourceId?: string
  backgroundUrl: string
  canvasWidth?: number
  canvasHeight?: number
  layers: Array<{ id: string; resourceId?: string; mediaUrl: string; label?: string; rect?: StudioLayerSplitRect }>
}): StudioLayerSeparationState {
  const layers: StudioLayerItem[] = input.layers.map((layer) => ({
    id: layer.id,
    ...(layer.resourceId ? { resourceId: layer.resourceId } : {}),
    mediaUrl: layer.mediaUrl,
    ...(layer.label ? { label: layer.label } : {}),
    transform: createDefaultLayerTransform(layer.rect)
  }))
  return {
    sourceNodeId: input.sourceNodeId,
    ...(input.backgroundResourceId ? { backgroundResourceId: input.backgroundResourceId } : {}),
    backgroundUrl: input.backgroundUrl,
    ...(input.canvasWidth ? { canvasWidth: input.canvasWidth } : {}),
    ...(input.canvasHeight ? { canvasHeight: input.canvasHeight } : {}),
    backgroundLocked: true,
    layers,
    activeLayerId: layers[0]?.id ?? null,
    dirty: false
  }
}

export function buildLayerExtractNodeData(layer: StudioLayerItem): Partial<StudioNodeData> {
  return {
    title: layer.label || layer.id,
    mediaUrl: layer.mediaUrl,
    mediaKind: 'image',
    status: 'success',
    imagePurpose: 'layer_extract',
    prompt: '',
    ...(layer.resourceId ? {
      canvasResourceIds: [layer.resourceId],
      canvasPrimaryResourceId: layer.resourceId
    } : {})
  }
}

/** 重绘结果写回：有 layerSeparation 则只改活动层，否则交由调用方整节点替换。 */
export function applyRepaintResultToLayerSeparation(
  state: StudioLayerSeparationState,
  mediaUrl: string
): StudioLayerSeparationState | null {
  if (!state.activeLayerId) return null
  return patchLayerInSeparation(state, state.activeLayerId, { mediaUrl })
}
