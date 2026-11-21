'use client'

import { useEffect, useState } from 'react'
import { useUserStore } from '@/stores/user'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasPublicationDetail, canvasPublicationVersionDetail } from '@/utils/canvas/canvasPublicationApi'
import { apiErrorMessage } from '@/utils/apiError'
import { canViewCanvasPublicationProcess } from '@/utils/canvas/canvasPublicationNavigation'
import { canvasPublicationFileUrl, createCanvasResourceAccess } from '@/utils/canvas/canvasPublicationFiles'
import { hydrateCanvasWorkspace, stableCanvasJson } from '@/utils/canvas/canvasWorkspaceAdapter'
import { canvasPublicationNodePresentation } from '@/utils/canvasPublicationNodePresentation'

export interface CanvasPublicationSource {
  publicationId: string
  history: boolean
}

/** Published snapshots and member workspaces are separate authorization/data sources. */
export function useCanvasPublicationWorkspace(scopeKey: string, source?: CanvasPublicationSource) {
  const id = source?.publicationId
  const history = source?.history === true
  const token = useUserStore((state) => state.token)
  const identity = history ? token : ''
  const accessKey = `${scopeKey}:${id}:${history}:${identity}`
  const [settledKey, setSettledKey] = useState('')

  useEffect(() => {
    if (id === undefined) return
    let active = true
    let inflight = false
    let fingerprint = ''
    let timer: ReturnType<typeof setTimeout> | undefined
    let access = createCanvasResourceAccess()
    let preparing: ReturnType<typeof createCanvasResourceAccess> | null = null
    const current = () => active && useStudioUiStore.getState().scopeKey === scopeKey
    useStudioUiStore.getState().beginLoad(scopeKey, { readOnly: true })

    async function load() {
      if (!current() || inflight) return
      inflight = true
      try {
        if (!/^[1-9]\d*$/.test(id!)) throw new Error('作品编号无效')
        const detail = await (history ? canvasPublicationVersionDetail(id!) : canvasPublicationDetail(id!))
        if (!current()) return
        if (detail.publicationId !== id || !detail.canvas) throw new Error('作品版本与请求不一致')
        if (!history && !canViewCanvasPublicationProcess(detail)) throw new Error('作品已停止公开或不可访问')
        const nextFingerprint = stableCanvasJson(detail)
        if (fingerprint === nextFingerprint) return
        const nextAccess = createCanvasResourceAccess()
        preparing = nextAccess
        try {
          const resources = await nextAccess.prepare(detail.canvas.resources, detail.canvas.sceneDocument)
          if (!current()) { nextAccess.dispose(); return }
          const workspace = hydrateCanvasWorkspace({
            scopeKey,
            projectName: detail.projectName,
            objects: detail.canvas.objects,
            resources: resources.map((resource) => ({ ...resource, resourceUrl: canvasPublicationFileUrl(resource.resourceUrl) })),
            currentResults: detail.canvas.currentResults,
            sceneDocument: detail.canvas.sceneDocument,
          })
          const contents = new Map(detail.canvas.objects.map((object) => [object.objectId, object.content]))
          const media = new Map(detail.canvas.resources.map((resource) => [resource.resourceId, resource]))
          workspace.nodes = workspace.nodes.map((node) => {
            const resourceId = node.data.canvasPrimaryResourceId || node.data.canvasResourceIds?.[0]
            const presentation = canvasPublicationNodePresentation(node.data, contents.get(node.id), resourceId ? media.get(resourceId)?.mediaType : undefined)
            return { ...node, data: { ...node.data, mediaKind: presentation.mediaKind, documentContent: presentation.text } }
          })
          // Never restore editing/chat/task state from an author's published payload.
          useStudioUiStore.getState().hydrate({ ...workspace, tasks: [], messages: [] }, { readOnly: true })
          setSettledKey(accessKey)
          access.dispose()
          access = nextAccess
          fingerprint = nextFingerprint
        } catch (error) {
          nextAccess.dispose()
          throw error
        } finally {
          if (preparing === nextAccess) preparing = null
        }
      } catch (error) {
        if (current()) {
          access.dispose()
          access = createCanvasResourceAccess()
          fingerprint = ''
          useStudioUiStore.getState().beginLoad(scopeKey, { readOnly: true })
          useStudioUiStore.getState().setLoadError(apiErrorMessage(error, '制作过程不可访问'))
          setSettledKey(accessKey)
        }
      } finally {
        inflight = false
        if (active) timer = setTimeout(() => void load(), document.hidden ? 60000 : 15000)
      }
    }
    const resume = () => {
      if (document.hidden) return
      clearTimeout(timer)
      void load()
    }
    void load()
    window.addEventListener('focus', resume)
    document.addEventListener('visibilitychange', resume)
    return () => {
      active = false
      access.dispose()
      preparing?.dispose()
      clearTimeout(timer)
      window.removeEventListener('focus', resume)
      document.removeEventListener('visibilitychange', resume)
    }
  }, [scopeKey, id, history, identity, accessKey])
  return Boolean(source && settledKey !== accessKey)
}
