export interface CanvasImageEditOutputSettings {
  prompt: string
  expectedImageCount: number
  size?: string
}

/**
 * 报价与提交共用同一份图片编辑输出参数。
 * 受保护编辑的输出尺寸由源图或扩图目标画布决定，不能再传 size 规格档。
 */
export function buildCanvasImageEditOutputSettings(input: {
  operation: 'REDRAW' | 'ERASE' | 'EXPAND'
  prompt?: string
  quality?: string
  count?: number
  target?: { targetWidth: number; targetHeight: number } | null
  finenessLabel?: string
  protectedCanvas?: boolean
}): CanvasImageEditOutputSettings {
  const expectedImageCount = Number.isInteger(input.count) && Number(input.count) > 0
    ? Number(input.count)
    : 1
  const quality = String(input.quality || '').trim()

  if (input.operation === 'EXPAND') {
    const target = input.target
      ? `${input.target.targetWidth}x${input.target.targetHeight}`
      : '目标画布尺寸'
    return {
      prompt: `自然延展图片背景与边缘，保持主体、画风和光影一致；目标画布 ${quality || target}，精细度${input.finenessLabel || '中'}。`,
      expectedImageCount
    }
  }

  return {
    prompt: input.operation === 'REDRAW' ? String(input.prompt || '').trim() : '',
    expectedImageCount,
    ...(input.protectedCanvas !== true && quality ? { size: quality } : {})
  }
}
