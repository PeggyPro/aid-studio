import type { GlobalSettingData } from '@/types'
import type { StudioNodeData } from '@/types/studio'

export type StudioStyleSelection = NonNullable<GlobalSettingData['selectedStyle']>

/** A selected style is a concrete canvas asset, never an empty generic node. */
export function studioStyleSelectionToNodeData(
  style: StudioStyleSelection
): Partial<StudioNodeData> {
  return {
    title: style.name,
    status: 'success',
    mediaKind: 'image',
    mediaUrl: style.thumbnail,
    composerHidden: true,
    resultSummary: `风格参考：${style.name}`,
    generationOptions: {
      styleId: style.id,
      styleName: style.name,
      stylePreviewUrl: style.thumbnail,
      stylePrompt: style.promptText?.trim() || undefined
    }
  }
}

export function isStudioStyleLibraryImageNode(data: StudioNodeData): boolean {
  return data.kind === 'style_library'
    && data.mediaKind === 'image'
    && Boolean(data.mediaUrl && data.generationOptions?.styleId)
}
