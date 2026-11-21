import { isImageAsset, isScriptAsset, isVideoAsset } from '@/components/steps/import-script/importScriptModalState'
import type { StudioNodeKind } from '@/types/studio'

export type CanvasAssistantAttachmentAsset = { id?: string | number; name?: string; title?: string; canvasNodeId?: string; canvasObjectVersion?: number; url?: string; content?: string; importSource?: string; categoryCode?: string; raw?: Record<string, unknown>; [key: string]: unknown }
export type CanvasAssistantAttachment = File | CanvasAssistantAttachmentAsset
export type CanvasAssistantAttachmentKind = Extract<StudioNodeKind, 'text' | 'image' | 'video'>

export function canvasAssistantAttachmentKind(item: CanvasAssistantAttachment): CanvasAssistantAttachmentKind {
  if (item instanceof File) {
    if (!item.size) throw new Error('请选择非空文件')
    if (/\.(txt|md|docx|pdf)$/i.test(item.name)) {
      if (item.size > 10 * 1024 * 1024) throw new Error('文本文件不能超过 10 MB')
      return 'text'
    }
    if (item.type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(item.name)) return 'image'
    if (item.type.startsWith('video/') || /\.(mp4|webm|mov|avi|mkv|m4v)$/i.test(item.name)) return 'video'
    throw new Error('请选择文本、图片或视频文件')
  }
  if (isVideoAsset(item)) return 'video'
  if (isImageAsset(item)) return 'image'
  if (isScriptAsset(item)) return 'text'
  throw new Error('所选素材不支持创建画布节点')
}

export function canvasAssistantLibraryAttachment(item: CanvasAssistantAttachmentAsset): CanvasAssistantAttachmentAsset {
  const kind = canvasAssistantAttachmentKind(item)
  const id = String(item.raw?.id ?? item.id ?? '')
  const categoryCode = String(item.raw?.categoryCode || item.categoryCode || '').trim()
  if (!/^[1-9][0-9]{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) throw new Error('素材身份无效，请重新选择')
  if (!['OFFICIAL_ASSET', 'ASSET_CENTER'].includes(String(item.importSource))) throw new Error('素材来源无效，请重新选择')
  if (item.importSource === 'ASSET_CENTER' && !categoryCode) throw new Error('素材缺少分类，请重新选择')
  return { id, categoryCode, importSource: item.importSource, name: item.name || item.title, type: kind,
    url: kind === 'image' ? String(item.url || item.thumbnail || '') : undefined,
    content: kind === 'text' && item.importSource === 'OFFICIAL_ASSET' ? item.content : undefined }
}
