import type { DirectorObject, DirectorTransform } from '@/types/studio'

export interface DirectorCrowdGroup {
  id: string
  label: string
  members: DirectorObject[]
}

export interface DirectorActorTree {
  leads: DirectorObject[]
  crowds: DirectorCrowdGroup[]
}

const LEGACY_CROWD_GROUP_ID = 'crowd-legacy'

export function resolveDirectorCrowdGroupId(object: DirectorObject): string | undefined {
  if (object.crowdGroupId) return object.crowdGroupId
  if (object.kind === 'actor' && /^群演/.test(object.name)) return LEGACY_CROWD_GROUP_ID
  return undefined
}

export function groupDirectorSceneActors(actors: DirectorObject[]): DirectorActorTree {
  const leads: DirectorObject[] = []
  const crowdMap = new Map<string, DirectorObject[]>()
  const crowdOrder: string[] = []
  for (const actor of actors) {
    const crowdGroupId = resolveDirectorCrowdGroupId(actor)
    if (!crowdGroupId) {
      leads.push(actor)
      continue
    }
    const existing = crowdMap.get(crowdGroupId)
    if (!existing) {
      crowdMap.set(crowdGroupId, [actor])
      crowdOrder.push(crowdGroupId)
      continue
    }
    existing.push(actor)
  }
  return {
    leads,
    crowds: crowdOrder.map((id, index) => ({
      id,
      label: crowdOrder.length <= 1 ? '群众列表' : `群众列表 ${index + 1}`,
      members: crowdMap.get(id) ?? []
    }))
  }
}

export function listDirectorCrowdMemberIds(objects: DirectorObject[], crowdGroupId: string): string[] {
  return objects.filter((item) => resolveDirectorCrowdGroupId(item) === crowdGroupId).map((item) => item.id)
}

export function isDirectorCrowdGroupFullySelected(memberIds: string[], selectedIds: string[]): boolean {
  return memberIds.length > 0 && memberIds.every((id) => selectedIds.includes(id))
}

export function prioritizeDirectorSelection(ids: string[], primary: string): string[] {
  const unique = [...new Set(ids)]
  if (!unique.includes(primary)) return unique
  return [primary, ...unique.filter((id) => id !== primary)]
}

export function resolveDirectorViewportSelection(options: {
  currentIds: string[]
  nextId: string | null
  additive?: boolean
  selectCrowdGroup?: boolean
  crowdMemberIds?: string[]
}): string[] {
  const { currentIds, nextId, additive, selectCrowdGroup, crowdMemberIds } = options
  if (!nextId) return additive ? currentIds : []
  if (selectCrowdGroup && crowdMemberIds?.length) {
    return prioritizeDirectorSelection(crowdMemberIds, nextId)
  }
  if (additive) {
    if (currentIds.includes(nextId)) return currentIds.filter((id) => id !== nextId)
    return [...currentIds, nextId]
  }
  return [nextId]
}

export function resolveDirectorAttachTargetId(
  scene: {
    objects: DirectorObject[]
    mainCameraLocked?: boolean
    cameraBookmarks?: Array<{ id: string; locked?: boolean }>
  },
  selectedIds: string[]
): string | null {
  for (const id of selectedIds) {
    const object = scene.objects.find((item) => item.id === id)
    if (object) {
      if (object.locked !== true) return id
      continue
    }
    if (id === 'main-camera' && scene.mainCameraLocked !== true) return id
    const bookmark = scene.cameraBookmarks?.find((item) => item.id === id)
    if (bookmark && bookmark.locked !== true) return id
  }
  return selectedIds[0] ?? null
}

export function prioritizeUnlockedDirectorSelection(objects: DirectorObject[], ids: string[]): string[] {
  const unlocked = ids.filter((id) => objects.find((item) => item.id === id)?.locked !== true)
  const locked = ids.filter((id) => !unlocked.includes(id))
  return [...unlocked, ...locked]
}

export function resolveDirectorSelectionDeletion(
  scene: {
    objects: DirectorObject[]
    mainCameraLocked?: boolean
    cameraBookmarks?: Array<{ id: string; locked?: boolean }>
  },
  selectedIds: string[]
): { blocked: boolean; objectIds: string[]; bookmarkIds: string[] } {
  const objectIds = selectedIds.filter((id) => scene.objects.some((item) => item.id === id))
  const bookmarkIds = selectedIds.filter((id) => scene.cameraBookmarks?.some((item) => item.id === id))
  const objectsLocked = objectIds.some((id) => scene.objects.find((item) => item.id === id)?.locked)
  const bookmarksLocked = bookmarkIds.some((id) => scene.cameraBookmarks?.find((item) => item.id === id)?.locked)
  return {
    blocked: objectsLocked || bookmarksLocked,
    objectIds: objectsLocked ? [] : objectIds,
    bookmarkIds: bookmarksLocked ? [] : bookmarkIds
  }
}

export function applyDirectorGroupedTransform(
  objects: DirectorObject[],
  selectedIds: string[],
  primaryId: string,
  nextTransform: DirectorTransform
): DirectorObject[] {
  const idSet = new Set(selectedIds.includes(primaryId) ? selectedIds : [primaryId, ...selectedIds])
  const primary = objects.find((item) => item.id === primaryId)
  if (!primary) return objects

  const prev = primary.transform
  const deltaPosition = [
    nextTransform.position[0] - prev.position[0],
    nextTransform.position[1] - prev.position[1],
    nextTransform.position[2] - prev.position[2]
  ] as const
  const deltaRotation = [
    nextTransform.rotation[0] - prev.rotation[0],
    nextTransform.rotation[1] - prev.rotation[1],
    nextTransform.rotation[2] - prev.rotation[2]
  ] as const
  const deltaScale = [
    prev.scale[0] === 0 ? 1 : nextTransform.scale[0] / prev.scale[0],
    prev.scale[1] === 0 ? 1 : nextTransform.scale[1] / prev.scale[1],
    prev.scale[2] === 0 ? 1 : nextTransform.scale[2] / prev.scale[2]
  ] as const

  return objects.map((item) => {
    if (item.id === primaryId) {
      return item.locked ? item : { ...item, transform: nextTransform }
    }
    if (!idSet.has(item.id) || item.locked) return item
    return {
      ...item,
      transform: {
        position: [
          item.transform.position[0] + deltaPosition[0],
          item.transform.position[1] + deltaPosition[1],
          item.transform.position[2] + deltaPosition[2]
        ],
        rotation: [
          item.transform.rotation[0] + deltaRotation[0],
          item.transform.rotation[1] + deltaRotation[1],
          item.transform.rotation[2] + deltaRotation[2]
        ],
        scale: [
          item.transform.scale[0] * deltaScale[0],
          item.transform.scale[1] * deltaScale[1],
          item.transform.scale[2] * deltaScale[2]
        ]
      }
    }
  })
}
