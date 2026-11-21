'use client'

/* eslint-disable @next/next/no-img-element -- 素材图片来自运行时素材库。 */
import { useRef, useState } from 'react'
import { SwapOutlined } from '@ant-design/icons'
import { message } from 'antd'
import type { StudioNodeData } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasProjectRuntime } from '../free/CanvasProjectRuntimeContext'
import { apiErrorMessage } from '@/utils/apiError'
import { isStudioMaterialNodeKind } from '@/utils/studio/studioNodeKind'
import { resolveStudioCatalogMaterial } from '@/utils/studio/studioMaterialLibrary'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioMaterialLibraryModal, type StudioMaterialPick } from './StudioMaterialLibraryModal'

export function StudioStyleLibraryNodePreview({
  nodeId,
  data,
  canReplace = true,
  onIntrinsicSize
}: {
  nodeId: string
  data: StudioNodeData
  canReplace?: boolean
  onIntrinsicSize?: (width: number, height: number) => void
}) {
  const runtime = useCanvasProjectRuntime()
  const [pickerOpen, setPickerOpen] = useState(false)
  const [replacing, setReplacing] = useState(false)
  const [failedUrl, setFailedUrl] = useState<string>()
  const inFlight = useRef(false)
  const kind = data.kind
  if (!isStudioMaterialNodeKind(kind)) return null

  const replaceLabel = kind === 'style_library' ? '替换风格' : '替换素材'
  const pending = replacing || data.status === 'queued'
  const replace = async (pick: StudioMaterialPick) => {
    if (pick.kind !== 'node' || pick.nodeKind !== kind || inFlight.current || (runtime && !runtime.canMutate)) return
    const state = useStudioUiStore.getState()
    const before = state.nodes.find((node) => node.id === nodeId)?.data
    if (!before || before.kind !== kind) return
    const isCurrent = () => {
      const current = useStudioUiStore.getState()
      const target = current.nodes.find((node) => node.id === nodeId)?.data
      return current.scopeKey === state.scopeKey && target?.kind === kind
        && target.mediaUrl === before.mediaUrl && target.canvasPrimaryResourceId === before.canvasPrimaryResourceId
        && target.generationOptions?.styleId === before.generationOptions?.styleId
    }
    inFlight.current = true
    setReplacing(true)
    try {
      const patch = runtime
        ? await resolveStudioCatalogMaterial(pick, runtime.resolveResourceReference, isCurrent)
        : { ...pick.data, errorMessage: undefined }
      if (isCurrent()) useStudioUiStore.getState().updateNodeData(nodeId, patch, true)
    } catch (error) {
      if (isCurrent()) message.error(apiErrorMessage(error, '替换素材失败，请重试'))
    } finally {
      inFlight.current = false
      setReplacing(false)
    }
  }

  return (
    <div className="studio-image-node-preview studio-style-library-preview">
      {data.mediaUrl && data.mediaUrl !== failedUrl ? (
        <img
          data-studio-source-image="true"
          src={data.mediaUrl}
          alt={data.title}
          draggable={false}
          onError={() => setFailedUrl(data.mediaUrl)}
          onLoad={(event) => onIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)}
        />
      ) : <p className="studio-style-library-preview__empty">素材预览不可用</p>}
      {pending || data.errorMessage ? (
        <p className="studio-style-library-preview__status" role="status">
          {pending ? '正在准备素材…' : data.errorMessage}
        </p>
      ) : null}
      {canReplace ? (
        <StudioCanvasTooltip title={pending ? '素材正在准备' : replaceLabel}>
          <button
            type="button"
            className="studio-style-library-preview__replace nodrag nopan"
            aria-label={replaceLabel}
            aria-disabled={pending}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={(event) => {
              event.stopPropagation()
              if (!pending) setPickerOpen(true)
            }}
          >
            <SwapOutlined />
          </button>
        </StudioCanvasTooltip>
      ) : null}
      {pickerOpen && canReplace ? (
        <StudioMaterialLibraryModal
          open
          replacementKind={kind}
          onPick={(pick) => void replace(pick)}
          onOpenChange={setPickerOpen}
        />
      ) : null}
    </div>
  )
}
