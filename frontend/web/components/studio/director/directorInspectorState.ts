import type { DirectorSceneData, DirectorViewMode } from '@/types/studio'

export type DirectorInspectorPanelKind = 'scene' | 'character' | 'prop' | 'camera' | 'light'

export function isDirectorCameraSelection(scene: DirectorSceneData, selectedObjectId: string | null): boolean {
  if (!selectedObjectId) return false
  if (selectedObjectId === 'main-camera') return scene.mainCameraPresent !== false
  return scene.cameraBookmarks?.some((bookmark) => bookmark.id === selectedObjectId) ?? false
}

/** 右侧检查器跟随当前选中项；未选中且处于机位视角时才回退到摄像机面板。 */
export function resolveDirectorInspectorPanelKind(
  scene: DirectorSceneData,
  selectedObjectId: string | null,
  viewMode: DirectorViewMode
): DirectorInspectorPanelKind {
  if (isDirectorCameraSelection(scene, selectedObjectId)) return 'camera'

  const selected = selectedObjectId
    ? scene.objects.find((object) => object.id === selectedObjectId)
    : undefined

  if (selected?.kind === 'actor') return 'character'
  if (selected?.kind === 'prop') return 'prop'
  if (scene.lights.some(light => light.id === selectedObjectId)) return 'light'
  if (viewMode === 'camera') return 'camera'

  return 'scene'
}

export function resolveDirectorInspectorTitle(kind: DirectorInspectorPanelKind): string {
  if (kind === 'scene') return '3D 场景'
  if (kind === 'character') return '角色'
  if (kind === 'prop') return '物件'
  if (kind === 'light') return '灯光'
  return '摄像机'
}
