'use client'

import { create } from 'zustand'
import type { DirectorCamera, DirectorLensSpec } from '@/types/studio'

interface DirectorCameraPreviewState {
  sceneId: string | null
  cameraId: string | null
  camera: DirectorCamera | null
  lens: DirectorLensSpec | null
  setPreview: (sceneId: string, cameraId: string, camera: DirectorCamera, lens: DirectorLensSpec) => void
  clearPreview: (sceneId?: string, cameraId?: string) => void
}

export const useDirectorCameraPreviewStore = create<DirectorCameraPreviewState>((set, get) => ({
  sceneId: null,
  cameraId: null,
  camera: null,
  lens: null,
  setPreview: (sceneId, cameraId, camera, lens) => set({ sceneId, cameraId, camera, lens }),
  clearPreview: (sceneId, cameraId) => {
    const current = get()
    if (sceneId && current.sceneId !== sceneId) return
    if (cameraId && current.cameraId !== cameraId) return
    set({ sceneId: null, cameraId: null, camera: null, lens: null })
  },
}))

export function previewDirectorCamera(
  sceneId: string,
  cameraId: string,
  camera: DirectorCamera,
  lens: DirectorLensSpec,
): void {
  useDirectorCameraPreviewStore.getState().setPreview(sceneId, cameraId, camera, lens)
}

export function clearDirectorCameraPreview(sceneId?: string, cameraId?: string): void {
  useDirectorCameraPreviewStore.getState().clearPreview(sceneId, cameraId)
}
