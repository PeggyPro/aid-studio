'use client'

import { create } from 'zustand'
import { DEFAULT_DIRECTOR_TIMELINE_DURATION } from '@/utils/studio/directorTimeline'

export interface DirectorPlaybackState {
  isPlaying: boolean
  playhead: number
  /** Full authoring ruler; the user may place new keys after the current motion. */
  duration: number
  /** Last authored motion, used only as the playback/export stop. */
  motionEnd: number
  /** Skip overwriting Three transforms for the object currently dragged by gizmo. */
  editingObjectId: string | null
  setPlaying: (playing: boolean) => void
  togglePlaying: () => void
  setPlayhead: (time: number) => void
  seek: (time: number) => void
  setRange: (duration: number, motionEnd: number) => void
  setEditingObjectId: (id: string | null) => void
  advance: (deltaSeconds: number) => void
  reset: (duration?: number) => void
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

export const useDirectorPlaybackStore = create<DirectorPlaybackState>((set, get) => ({
  isPlaying: false,
  playhead: 0,
  duration: DEFAULT_DIRECTOR_TIMELINE_DURATION,
  motionEnd: DEFAULT_DIRECTOR_TIMELINE_DURATION,
  editingObjectId: null,
  setPlaying: (isPlaying) => set(state => state.isPlaying === isPlaying ? state : {
    isPlaying, playhead: isPlaying && state.playhead >= state.motionEnd ? 0 : state.playhead
  }),
  togglePlaying: () => set((state) => ({ isPlaying: !state.isPlaying, playhead: !state.isPlaying && state.playhead >= state.motionEnd ? 0 : state.playhead })),
  setPlayhead: (time) => set(state => {
    const playhead = clamp(time, 0, state.duration)
    return playhead === state.playhead && state.editingObjectId === null ? state : { playhead, editingObjectId: null }
  }),
  seek: (time) => set(state => {
    const playhead = clamp(time, 0, state.duration)
    return playhead === state.playhead && !state.isPlaying && state.editingObjectId === null
      ? state : { playhead, isPlaying: false, editingObjectId: null }
  }),
  setRange: (duration, end) => set(state => {
    const nextDuration = Math.max(0.1, duration)
    const motionEnd = clamp(end, 0.1, nextDuration)
    if (state.duration === nextDuration && state.motionEnd === motionEnd) return state
    const playhead = Math.min(state.playhead, nextDuration)
    return { duration: nextDuration, motionEnd, playhead, isPlaying: state.isPlaying && playhead < motionEnd }
  }),
  setEditingObjectId: (editingObjectId) => set({ editingObjectId }),
  advance: (deltaSeconds) => {
    if (!get().isPlaying) return
    set((state) => {
      const next = state.playhead + deltaSeconds
      if (next >= state.motionEnd) {
        return { playhead: state.motionEnd, isPlaying: false }
      }
      return { playhead: next }
    })
  },
  reset: (duration = DEFAULT_DIRECTOR_TIMELINE_DURATION) => set({
    isPlaying: false,
    playhead: 0,
    duration: Math.max(0.1, duration),
    motionEnd: Math.max(0.1, duration),
    editingObjectId: null
  })
}))
