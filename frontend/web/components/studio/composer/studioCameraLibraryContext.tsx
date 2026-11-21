'use client'

import { createContext, useContext } from 'react'
import type { StudioCameraMarkDraft, StudioCameraMarkKind } from '@/utils/studio/studioCameraMark'

export type StudioCameraLibraryRequest = {
  seq: number
  tab: StudioCameraMarkKind
  replaceId?: string
  optionKey?: string
}

export type StudioCameraLibraryController = {
  request: StudioCameraLibraryRequest | null
  requestOpen: (input: Omit<StudioCameraLibraryRequest, 'seq'>) => void
  insertMark: (mark: StudioCameraMarkDraft) => void
  replaceMark: (id: string, mark: StudioCameraMarkDraft) => void
}

const StudioCameraLibraryContext = createContext<StudioCameraLibraryController | null>(null)

export const StudioCameraLibraryProvider = StudioCameraLibraryContext.Provider

export function useStudioCameraLibrary() {
  return useContext(StudioCameraLibraryContext)
}
