'use client'

import { useImperativeHandle, useRef, useState, type ComponentProps, type Ref } from 'react'
import { message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { ImportScriptModal } from '@/components/steps/ImportScriptModal'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { apiErrorMessage } from '@/utils/apiError'
import type { CanvasAssistantAttachment } from '@/utils/canvas/canvasAssistantAttachment'

export interface StudioCanvasAssistantAttachmentPickerHandle {
  openLocal: () => void
  openLibrary: () => void
  openDocuments: () => void
}

export function StudioCanvasAssistantAttachmentPicker({ disabled, onAdd, currentAssets, onOpenDocuments, ref }: {
  disabled: boolean
  onAdd: (items: CanvasAssistantAttachment[]) => boolean | Promise<boolean>
  currentAssets: NonNullable<ComponentProps<typeof ImportScriptModal>['currentProjectAssets']>
  onOpenDocuments?: () => void
  ref?: Ref<StudioCanvasAssistantAttachmentPickerHandle>
}) {
  const input = useRef<HTMLInputElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [libraryOpen, setLibraryOpen] = useState(false)
  const local = () => { if (!disabled) { setLibraryOpen(false); input.current?.click() } }
  const library = () => { if (!disabled) setLibraryOpen(true) }
  const documents = () => {
    if (disabled) return
    if (!onOpenDocuments) { void message.warning('当前服务尚未开放原文文档导入'); return }
    setMenuOpen(false); setLibraryOpen(false); onOpenDocuments()
  }
  const add = async (items: CanvasAssistantAttachment[]) => {
    if (disabled || !items.length) return false
    try { return await onAdd(items) }
    catch (cause) { void message.error(apiErrorMessage(cause, '素材导入失败')); return false }
  }
  useImperativeHandle(ref, () => ({ openLocal: local, openLibrary: library, openDocuments: documents }))
  return <>
    <input ref={input} type="file" multiple accept=".txt,.md,.docx,.pdf,image/*,video/*" className="sr-only" aria-label="上传文本图片或视频" disabled={disabled} onChange={event => {
      const files = Array.from(event.target.files || []); event.target.value = ''
      void add(files)
    }} />
    <StudioCanvasDropdownPopover open={menuOpen} onOpenChange={setMenuOpen} placement="topLeft" ariaLabel="添加素材" getPopupContainer={getStudioComposerPopupContainer} items={[
      { key: 'local', label: '本地上传', icon: <StudioIconGlyph name="upload" /> }, { key: 'library', label: '素材库添加', icon: <StudioIconGlyph name="library" /> },
      ...(onOpenDocuments ? [{ key: 'document', label: '导入原文文档', icon: <StudioIconGlyph name="outline" /> }] : [])
    ]} onSelect={key => { if (key === 'local') local(); else if (key === 'document') documents(); else if (key === 'library') library() }}
      trigger={<StudioCanvasTooltip title={disabled ? '当前不可添加素材' : '添加素材'}><button type="button" className="canvas-agent-attachment-trigger" aria-label="添加素材" aria-haspopup="menu" aria-expanded={menuOpen} disabled={disabled}><PlusOutlined /></button></StudioCanvasTooltip>} />
    <ImportScriptModal key={currentAssets.projectId} open={libraryOpen} title="添加素材" multiple acceptAssetType="mixed" currentProjectAssets={currentAssets} onOpenChange={setLibraryOpen} onLocalFileSelect={local} onImportMultiple={add} />
  </>
}
