'use client'

import { LoadingOutlined, PaperClipOutlined } from '@ant-design/icons'
import { useRef } from 'react'
import type { AudioClientContract } from '@/types/audio-model'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import { StudioCanvasDropdownPopover } from '../canvas/StudioCanvasDropdownMenu'
import { studioAudioUploadTargets, validateStudioAudioAttachment, type StudioAudioUploadTarget } from '@/utils/studio/studioAudioInput'

export function StudioAudioAttachmentButton({ contract, value, disabledReason, uploading, onUpload, onError }: {
  contract?: AudioClientContract | null; value: Record<string, unknown>; disabledReason?: string; uploading: boolean
  onUpload: (path: string, file: File) => Promise<void>; onError: (error: string) => void
}) {
  const input = useRef<HTMLInputElement>(null), selected = useRef<StudioAudioUploadTarget | null>(null)
  const targets = contract ? studioAudioUploadTargets(contract, value) : []
  const reason = uploading ? '附件上传并验证中' : disabledReason || (!targets.length ? '当前能力没有可用的附件位置' : '')
  const menu = targets.length > 1 || targets.some(target => target.rule.mediaType !== 'AUDIO')
  const choose = (target: StudioAudioUploadTarget) => {
    selected.current = target
    if (input.current) {
      input.current.accept = target.rule.formats?.map(format => `.${format.replace(/^\./, '')}`).join(',')
        || (target.rule.mediaType === 'IMAGE' ? 'image/*' : target.rule.mediaType === 'VIDEO' ? 'video/*' : 'audio/*')
      input.current.click()
    }
  }
  const trigger = <StudioCanvasTooltip title={reason || (menu ? '上传音频或参考图片' : '上传音频附件')}><button type="button" className="studio-composer__expand studio-audio-attachment-button" aria-label="上传附件" disabled={!!reason}
    onClick={!menu && targets.length === 1 ? () => choose(targets[0]) : undefined}>{uploading ? <LoadingOutlined spin /> : <PaperClipOutlined />}</button></StudioCanvasTooltip>
  return <>
    {menu && !reason ? <StudioCanvasDropdownPopover nodePlacement ariaLabel="选择附件"
      items={targets.map(target => ({ key: target.path, label: target.label }))} onSelect={path => { const target = targets.find(item => item.path === path); if (target) choose(target) }} trigger={trigger} /> : trigger}
    <input ref={input} type="file" hidden onChange={event => {
      const file = event.target.files?.[0], target = selected.current; event.target.value = ''
      if (!file || !target || reason) return
      onError('')
      try {
        validateStudioAudioAttachment(file, target.rule)
        void onUpload(target.path, file).catch(cause => onError(cause instanceof Error ? cause.message : '附件上传失败'))
      } catch (cause) { onError(cause instanceof Error ? cause.message : '附件格式不支持') }
    }} />
  </>
}
