import { STUDIO_NODE_META } from '@/config/studioMeta'
import type { CanvasProjectRole } from '@/types/canvas-api'
import type { StudioNodeData, StudioNodeKind } from '@/types/studio'
import { createTrackedObjectUrl, revokeObjectUrl } from '@/utils/objectUrl'
import {
  uploadAudioToOssWithToast,
  uploadImageToOssWithToast,
  uploadVideoToOssWithToast
} from '@/utils/ossUpload'
import { readMediaFileAspectRatio } from '@/utils/studio/studioMediaFileAspect'

export type StudioCanvasDropFileKind = 'image' | 'video' | 'voice' | 'text'

const TEXT_EXTENSIONS = new Set(['.txt', '.md', '.markdown'])
const IMAGE_EXTENSIONS = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.avif', '.heic', '.heif'])
const VIDEO_EXTENSIONS = new Set(['.mp4', '.webm', '.mov', '.m4v', '.mkv', '.avi'])
const AUDIO_EXTENSIONS = new Set(['.mp3', '.wav', '.m4a', '.aac', '.ogg', '.flac', '.webm'])

function fileExtension(name: string): string {
  const index = name.lastIndexOf('.')
  return index >= 0 ? name.slice(index).toLowerCase() : ''
}

/**
 * 系统文件拖入在 dragenter 时 types 经常还是空的。
 * 这时必须先接住，浏览器才会在 drop 里给出文件；已经标明的文本拖拽不能当成文件。
 */
export function isStudioCanvasExternalFileDrag(types: ArrayLike<string> | null | undefined): boolean {
  if (!types) return false
  if (types.length === 0) return true
  for (let index = 0; index < types.length; index += 1) {
    const type = types[index]
    if (type === 'Files' || type === 'application/x-moz-file') return true
  }
  return false
}

export function resolveStudioCanvasDropFileKind(file: File): StudioCanvasDropFileKind | null {
  if (file.type.startsWith('image/')) return 'image'
  if (file.type.startsWith('video/')) return 'video'
  if (file.type.startsWith('audio/')) return 'voice'
  const extension = fileExtension(file.name)
  if (file.type.startsWith('text/') || TEXT_EXTENSIONS.has(extension)) return 'text'
  if (VIDEO_EXTENSIONS.has(extension)) return 'video'
  if (IMAGE_EXTENSIONS.has(extension)) return 'image'
  if (AUDIO_EXTENSIONS.has(extension)) return 'voice'
  return null
}

/** 已接入项目画布时，导入必须能写到服务端；本地草稿画布不在这里拦截。 */
export function studioCanvasFileImportBlockedReason(runtime: {
  canMutate: boolean
  role: CanvasProjectRole | null
  collaborationError: string | null
} | null | undefined): string | null {
  if (!runtime || runtime.canMutate) return null
  if (runtime.role === 'VIEWER') return '当前账号只能查看画布'
  return runtime.collaborationError || (runtime.role
    ? '画布尚未连接，暂时不能导入素材'
    : '画布权限尚未确认，暂时不能导入素材')
}

export function studioCanvasDropKindToNodeKind(kind: StudioCanvasDropFileKind): StudioNodeKind {
  return kind === 'voice' ? 'voice' : kind
}

function dropFileTitle(file: File, nodeKind: StudioNodeKind): string {
  const trimmed = file.name.replace(/\.[^.]+$/, '').trim()
  return trimmed || STUDIO_NODE_META[nodeKind].label
}

export type StudioCanvasDropImport = Partial<StudioNodeData> & {
  title: string
  /** 本地 blob 预览；OSS 成功后由调用方 revoke */
  localPreviewUrl?: string
  /** 异步读取/登记完成后原子回填；失败保留可重试的占位节点。 */
  finalizeUpload: () => Promise<Partial<StudioNodeData>>
}

type CanvasDropUploader = (file: File, mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO') => Promise<{ resourceUrl: string; resourceId: string | number }>

/** 同步准备占位；解码、文件读取和资源上传只在节点创建后开始。 */
export function importStudioCanvasDropFile(
  file: File,
  canvasUploader?: CanvasDropUploader
): StudioCanvasDropImport | null {
  const dropKind = resolveStudioCanvasDropFileKind(file)
  if (!dropKind) return null

  const nodeKind = studioCanvasDropKindToNodeKind(dropKind)
  const title = dropFileTitle(file, nodeKind)

  if (dropKind !== 'text') {
    const localPreviewUrl = createTrackedObjectUrl(file)
    const mediaKind = dropKind === 'voice' ? 'audio' as const : dropKind
    return {
      title,
      status: 'generating',
      canvasTransient: true,
      mediaUrl: localPreviewUrl,
      mediaKind,
      resultSummary: '正在导入素材',
      localPreviewUrl,
      finalizeUpload: async () => {
        const aspectRatio = dropKind === 'voice' ? null : await readMediaFileAspectRatio(file)
        const resource = canvasUploader
          ? await canvasUploader(file, dropKind === 'voice' ? 'AUDIO' : dropKind === 'image' ? 'IMAGE' : 'VIDEO')
          : null
        const mediaUrl = canvasUploader ? resource?.resourceUrl : dropKind === 'image'
            ? await uploadImageToOssWithToast(file)
            : dropKind === 'video' ? await uploadVideoToOssWithToast(file) : await uploadAudioToOssWithToast(file)
        if (!mediaUrl) throw new Error('素材上传未返回有效地址，请重新选择文件')
        return {
          mediaUrl, mediaKind, mediaAspectRatio: aspectRatio ?? undefined,
          ...(resource ? { canvasResourceIds: [String(resource.resourceId)], canvasPrimaryResourceId: String(resource.resourceId) } : {})
        }
      }
    }
  }

  if (file.size > 2 * 1024 * 1024) {
    throw new Error('文本文件不能超过 2MB')
  }
  return {
    title,
    status: 'generating',
    canvasTransient: true,
    mediaKind: 'text',
    resultSummary: '正在读取文本',
    finalizeUpload: async () => {
      const content = (await file.text()).trim()
      if (!content) throw new Error('文本文件没有可导入的内容')
      return { documentContent: content, mediaKind: 'text' }
    }
  }
}

export function revokeDropLocalPreview(url: string | undefined) {
  if (url) revokeObjectUrl(url)
}
