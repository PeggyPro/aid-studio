const aligners = new Set<() => void>()

/**
 * 画布平移改的是浮层 left/top，不是滚动。
 * rc-trigger 只在 scroll/resize 时重对齐，所以弹出层会留在打开时的屏幕位置。
 * 浮层重新定位后调用这里，让仍打开的弹出层贴回触发按钮。
 */
export function registerStudioComposerPopupAlign(align: () => void): () => void {
  aligners.add(align)
  return () => {
    aligners.delete(align)
  }
}

export function realignStudioComposerPopups(): void {
  for (const align of aligners) align()
}
