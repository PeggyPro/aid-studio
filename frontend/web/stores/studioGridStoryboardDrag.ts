'use client'

import { create } from 'zustand'
import type { StudioGridStoryboardDropTarget } from '@/utils/studio/studioGridStoryboardTransfer'

interface StudioGridStoryboardDragState {
  returningNodeId: string | null
  dropTarget: StudioGridStoryboardDropTarget | null
  beginReturn: (nodeId: string) => void
  setDropTarget: (target: StudioGridStoryboardDropTarget | null) => void
  clear: () => void
}

export const useStudioGridStoryboardDragStore = create<StudioGridStoryboardDragState>((set) => ({
  returningNodeId: null,
  dropTarget: null,
  beginReturn: (returningNodeId) => set({ returningNodeId, dropTarget: null }),
  setDropTarget: (dropTarget) => set({ dropTarget }),
  clear: () => set({ returningNodeId: null, dropTarget: null })
}))
