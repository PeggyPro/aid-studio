'use client'

import { useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react'
import { message } from 'antd'
import { StudioCanvasAssistantAttachmentPicker, type StudioCanvasAssistantAttachmentPickerHandle } from './StudioCanvasAssistantAttachmentPicker'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import { canvasTextImport } from '@/utils/business/canvas'
import { userAssetCenterDetail } from '@/utils/businessApi'
import { htmlToPlainPreserveLineBreaks } from '@/utils/htmlPlain'
import { createTrackedObjectUrl, revokeObjectUrl } from '@/utils/objectUrl'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasAssistantAttachmentKind, canvasAssistantLibraryAttachment, type CanvasAssistantAttachment, type CanvasAssistantAttachmentKind } from '@/utils/canvas/canvasAssistantAttachment'
function plainContent(content: string) {
  const text = /<[a-z][\s\S]*>/i.test(content) ? htmlToPlainPreserveLineBreaks(content) : content
  if (!text.trim() || text.length > 65536 || new TextEncoder().encode(text).length > 128 * 1024) throw new Error('文本为空或超过节点正文限制，请缩短后导入')
  return text
}

export interface StudioCanvasAssistantAttachmentHandle extends StudioCanvasAssistantAttachmentPickerHandle {
  add: (items: CanvasAssistantAttachment[]) => Promise<string[] | null>
}

export function StudioCanvasAssistantAttachments({ disabled, onReferencesAdded, onOpenDocuments, ref }: { disabled: boolean; onReferencesAdded?: (ids: string[]) => void; onOpenDocuments?: () => void; ref?: Ref<StudioCanvasAssistantAttachmentHandle> }) {
  const runtime = useCanvasProjectRuntime()
  const nodes = useStudioUiStore(state => state.nodes)
  const scopeKey = useStudioUiStore(state => state.scopeKey)
  const savedAt = useStudioUiStore(state => state.savedAt)
  const picker = useRef<StudioCanvasAssistantAttachmentPickerHandle>(null)
  const inFlight = useRef(false)
  const [busy, setBusy] = useState(false)
  const blocked = disabled || busy || !runtime || !runtime.connected || !runtime.canEdit
  const projectId = runtime?.project?.projectId || '', projectName = runtime?.project?.projectName || '当前画布'
  const workspaceRevision = runtime?.project?.workspace.revision
  const nodeResources = runtime?.nodeResources, getNodeVersion = runtime?.getNodeVersion
  const currentAssets = useMemo(() => ({ projectId, projectName, snapshotSavedAt: savedAt, assets: nodes.filter(node => ['text', 'image', 'video'].includes(node.data.kind) && !node.data.canvasTransient && (getNodeVersion?.(node.id) ?? 0) > 0).filter(node => node.data.kind === 'text' ? !!node.data.documentContent : !!node.data.mediaUrl && (!!nodeResources?.(node.id).length || /^[1-9][0-9]*$/.test(String(node.data.canvasCurrentResultId || '')))).map(node => ({
    id: node.id, canvasNodeId: node.id, canvasObjectVersion: getNodeVersion?.(node.id), canvasWorkspaceRevision: workspaceRevision, name: node.data.title || node.data.label,
    type: node.data.kind === 'text' ? 'text' : node.data.kind, content: node.data.documentContent || '', url: node.data.mediaUrl || '', thumbnail: node.data.mediaUrl || '',
    categoryCode: node.data.kind.toUpperCase(), categoryName: node.data.kind === 'text' ? '文本' : node.data.kind === 'image' ? '图片' : '视频'
  })) }), [nodes, savedAt, projectId, projectName, workspaceRevision, nodeResources, getNodeVersion])

  const add = async (items: CanvasAssistantAttachment[]) => {
    if (blocked || inFlight.current || !runtime?.project || !items.length) return null
    const kinds = items.map(item => {
      const kind = canvasAssistantAttachmentKind(item)
      if (item instanceof File) return kind
      if (item.canvasNodeId) {
        const source = useStudioUiStore.getState().nodes.find(node => node.id === item.canvasNodeId)
        if (!source || runtime.getNodeVersion(source.id) !== item.canvasObjectVersion) throw new Error('画布素材已变化，请重新选择')
      } else canvasAssistantLibraryAttachment(item)
      return kind
    })
    const expectedScope = scopeKey, expectedWorkspace = runtime.project.workspace.workspaceId
    inFlight.current = true; setBusy(true)
    const jobs: Array<{ item: CanvasAssistantAttachment; kind: CanvasAssistantAttachmentKind; nodeId: string; localUrl?: string }> = []
    const current = (nodeId: string) => useStudioUiStore.getState().scopeKey === expectedScope && runtime.project?.workspace.workspaceId === expectedWorkspace && useStudioUiStore.getState().nodes.some(node => node.id === nodeId)
    let succeeded = 0
    const referenceOrder: string[] = []
    try {
      // All placeholders are created and selected before decoding, parsing or uploading.
      items.forEach((item, index) => {
        if (!(item instanceof File) && item.canvasNodeId) {
          referenceOrder.push(String(item.canvasNodeId)); return
        }
        const kind = kinds[index], localUrl = item instanceof File && kind !== 'text' ? createTrackedObjectUrl(item) : undefined
        try {
          const title = item instanceof File ? item.name.replace(/\.[^.]+$/, '') : String(item.name || item.title || '导入素材')
          const nodeId = useStudioUiStore.getState().addNode(kind, undefined, { title, status: 'generating', canvasTransient: true, mediaKind: kind, ...(localUrl ? { mediaUrl: localUrl } : {}) })
          jobs.push({ item, kind, nodeId, localUrl })
          referenceOrder.push(nodeId)
        } catch (cause) { revokeObjectUrl(localUrl); throw cause }
      })
      onReferencesAdded?.(referenceOrder)
      for (const job of jobs) {
        try {
          let patch
          let sourceVersion = job.item instanceof File ? undefined : job.item.canvasObjectVersion
          if (!(job.item instanceof File) && job.item.canvasNodeId) {
            const sourceNodeId = job.item.canvasNodeId
            const source = useStudioUiStore.getState().nodes.find(node => node.id === sourceNodeId)
            if (!source || runtime.getNodeVersion(source.id) !== sourceVersion || (job.kind === 'text' ? source.data.documentContent !== job.item.content : source.data.mediaUrl !== job.item.url)) throw new Error('源素材已变化，请重新选择')
          }
          if (job.kind === 'text') {
            let content: string
            if (job.item instanceof File) content = (await canvasTextImport(expectedWorkspace, job.item)).textContent
            else if (job.item.canvasNodeId || job.item.importSource === 'OFFICIAL_ASSET') content = String(job.item.content || '')
            else {
              const id = String(job.item.raw?.id ?? job.item.id), categoryCode = String(job.item.raw?.categoryCode || job.item.categoryCode || job.item.raw?.assetType || '').trim()
              if (!categoryCode) throw new Error('素材缺少分类，请重新选择')
              const detail = await userAssetCenterDetail({ categoryCode, id })
              const value = detail.content || {}
              content = String([value.originalText, value.storyScript, value.dialogueText, value.ttsText, value.promptText, value.introduction, value.summary].find(text => typeof text === 'string' && text.trim()) || '')
            }
            patch = { documentContent: plainContent(content), mediaKind: 'text' as const }
          } else {
            const resource = job.item instanceof File ? await runtime.uploadResource(job.item, job.kind === 'video' ? 'VIDEO' : 'IMAGE') : await runtime.resolveResourceReference({
              id: String(job.item.id), kind: job.item.canvasNodeId ? 'node' : job.item.importSource === 'OFFICIAL_ASSET' ? 'library' : 'asset',
              ...(job.item.canvasNodeId ? { nodeId: String(job.item.canvasNodeId) } : { assetId: String(job.item.raw?.id ?? job.item.id) }), title: String(job.item.name || ''), mediaKind: job.kind,
              ...(job.item.importSource === 'ASSET_CENTER' ? { sourceCategoryCode: String(job.item.raw?.categoryCode || job.item.categoryCode || '').trim() } : {})
            })
            if (resource.mediaType !== job.kind.toUpperCase()) throw new Error('素材类型与节点不一致')
            // Resolving a generated result registers and saves its source resource binding.
            // Pin that authoritative version after the resolver's own original-version check.
            if (!(job.item instanceof File) && job.item.canvasNodeId) {
              const resolvedVersion = runtime.getNodeVersion(job.item.canvasNodeId)
              if (resolvedVersion === null || resolvedVersion <= 0) throw new Error('源素材已变化，请重新选择')
              sourceVersion = resolvedVersion
            }
            patch = { mediaUrl: resource.resourceUrl, mediaKind: job.kind, canvasResourceIds: [String(resource.resourceId)], canvasPrimaryResourceId: String(resource.resourceId) }
          }
          if (!current(job.nodeId)) continue
          if (!(job.item instanceof File) && job.item.canvasNodeId) {
            const sourceNodeId = job.item.canvasNodeId
            const source = useStudioUiStore.getState().nodes.find(node => node.id === sourceNodeId)
            if (!source || runtime.getNodeVersion(source.id) !== sourceVersion || (job.kind === 'text' ? source.data.documentContent !== job.item.content : source.data.mediaUrl !== job.item.url)) throw new Error('源素材已变化，请重新选择')
          }
          useStudioUiStore.getState().updateNodeData(job.nodeId, { ...patch, status: 'success', canvasTransient: false, errorMessage: undefined })
          succeeded += 1
        } catch (cause) {
          if (current(job.nodeId)) useStudioUiStore.getState().updateNodeData(job.nodeId, { status: 'failed', errorMessage: apiErrorMessage(cause, '导入失败，请重新选择素材'), mediaUrl: undefined })
        } finally { revokeObjectUrl(job.localUrl) }
      }
      if (useStudioUiStore.getState().scopeKey !== expectedScope) return null
      if (succeeded) {
        try { await runtime.saveNow() }
        catch (cause) { void message.error('素材已导入，画布保存未完成：' + apiErrorMessage(cause)); return null }
      }
      if (succeeded !== jobs.length) { void message.error('部分素材未导入，请查看画布节点上的失败原因'); return null }
      return referenceOrder
    } catch (cause) {
      for (const job of jobs) {
        const node = useStudioUiStore.getState().nodes.find(item => item.id === job.nodeId)
        if (current(job.nodeId) && node?.data.canvasTransient && node.data.status === 'generating') useStudioUiStore.getState().updateNodeData(job.nodeId, { status: 'failed', errorMessage: apiErrorMessage(cause, '导入未完成，请重新选择素材'), mediaUrl: undefined })
      }
      throw cause
    } finally { jobs.forEach(job => revokeObjectUrl(job.localUrl)); inFlight.current = false; setBusy(false) }
  }
  useImperativeHandle(ref, () => ({
    add,
    openLocal: () => picker.current?.openLocal(),
    openLibrary: () => picker.current?.openLibrary(),
    openDocuments: () => picker.current?.openDocuments()
  }))
  return <StudioCanvasAssistantAttachmentPicker key={scopeKey} ref={picker} disabled={blocked} currentAssets={currentAssets} onAdd={async items => Boolean(await add(items))} onOpenDocuments={onOpenDocuments} />
}
