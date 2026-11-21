'use client'

import { createContext, useContext } from 'react'

/** Dock owns presentation; changing it never moves or remounts the editor. */
export type StudioComposerPresentation = {
  centered: boolean
  expandOnly?: boolean
  toggleCentered: () => void
  getOverlayContainer: () => HTMLElement | null
}

export const StudioComposerPresentationContext = createContext<StudioComposerPresentation | null>(null)

export const useStudioComposerPresentation = () => useContext(StudioComposerPresentationContext)
