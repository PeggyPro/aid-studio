'use client'

import { create } from 'zustand'
import type { StudioAudioEdit } from '@/types/studio-audio'

/** Transient source-node editor; derived instructions live in workspace node data instead. */
export const useStudioAudioEditor = create<{
  session: (StudioAudioEdit & { scopeKey: string }) | null
  open: (session: StudioAudioEdit & { scopeKey: string }) => void
  patch: (patch: Partial<StudioAudioEdit>) => void
  close: () => void
}>((set) => ({
  session: null,
  open: (session) => set({ session }),
  patch: (patch) => set((state) => ({ session: state.session ? { ...state.session, ...patch } : null })),
  close: () => set({ session: null })
}))
