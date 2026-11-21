import type { CanvasResource } from '@/types/canvas-api'
export interface CanvasPublicationDraft {
  projectName: string
  description: string
  publicCanvas: boolean
  allowCopy: boolean
  video: {
    resource: CanvasResource
    nodeId?: string
    sourceKey?: string
  } | null
  cover: {
    resource: CanvasResource
    nodeId?: string
    sourceKey?: string
  } | null
}
const key = (owner: string, project: string) =>
  `aid:canvas:publication-draft:${owner}:${project}`
export function durableCanvasResource({
  displayUrl: _displayUrl,
  ...resource
}: CanvasResource): CanvasResource {
  return resource
}
export const canvasPublicationDraft = {
  read(owner: string, project: string): CanvasPublicationDraft | null {
    try {
      const value = JSON.parse(
        localStorage.getItem(key(owner, project)) || 'null',
      )
      return value && typeof value.description === 'string' ? value : null
    } catch {
      return null
    }
  },
  write(owner: string, project: string, value: CanvasPublicationDraft) {
    localStorage.setItem(
      key(owner, project),
      JSON.stringify({
        ...value,
        video: value.video
          ? {
              ...value.video,
              resource: durableCanvasResource(value.video.resource),
            }
          : null,
        cover: value.cover
          ? {
              ...value.cover,
              resource: durableCanvasResource(value.cover.resource),
            }
          : null,
      }),
    )
  },
  clear(owner: string, project: string) {
    try {
      localStorage.removeItem(key(owner, project))
    } catch {}
  },
}
