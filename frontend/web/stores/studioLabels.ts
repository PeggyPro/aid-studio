import { create } from 'zustand'
import type { StudioLabelId } from '@/utils/studio/studioNodeLabels'

/** 颜色高亮是视图状态，按工作区隔离，不进入保存数据或撤销栈。 */
export const useStudioLabels = create<{
  scopeKey: string | null
  active: StudioLabelId | null
  activate: (scopeKey: string, active: StudioLabelId | null) => void
}>((set) => ({ scopeKey: null, active: null, activate: (scopeKey, active) => set({ scopeKey, active }) }))
