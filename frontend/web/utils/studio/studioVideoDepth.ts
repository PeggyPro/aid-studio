import type { StudioCanvasNode, StudioNodeData } from '@/types/studio'

// 部署开关不替代服务端鉴权/能力校验。真实 Provider 联调通过后才可打开。
export const STUDIO_DEPTH_CAPTURE_ENABLED = process.env.NEXT_PUBLIC_STUDIO_DEPTH_CAPTURE_ENABLED === 'true'
export const STUDIO_DEPTH_DEMO = {
  before: process.env.NEXT_PUBLIC_STUDIO_DEPTH_DEMO_BEFORE_URL || '',
  after: process.env.NEXT_PUBLIC_STUDIO_DEPTH_DEMO_AFTER_URL || ''
}

const acknowledged = new Set<string>()
const preferenceKey = (userId: string | number | undefined) =>
  `aid-studio:depth-capture:intro:v1:${userId ?? 'anonymous'}`

export function hasSeenStudioDepthIntro(userId: string | number | undefined): boolean {
  const key = preferenceKey(userId)
  if (acknowledged.has(key)) return true
  try { return typeof window !== 'undefined' && window.localStorage.getItem(key) === '1' }
  catch { return false }
}

export function acknowledgeStudioDepthIntro(userId: string | number | undefined): void {
  const key = preferenceKey(userId)
  acknowledged.add(key)
  try { window.localStorage.setItem(key, '1') } catch { /* 隐私模式仍在当前页面记住确认。 */ }
}

export function createStudioDepthNodeData(source: StudioCanvasNode): Partial<StudioNodeData> {
  const recordId = Number(source.data.recordId ?? source.data.genRecordId)
  return {
    title: `深度动作捕捉-${source.data.title}`,
    subtitle: `由 ${source.data.title} 发起`,
    zoneId: source.data.zoneId,
    storyboardId: source.data.storyboardId,
    status: 'empty',
    mediaKind: 'video',
    videoPurpose: 'depth_capture',
    composerHidden: false,
    videoToolOptions: {
      sourceNodeId: source.id,
      sourceVideoUrl: source.data.mediaUrl,
      ...(Number.isFinite(recordId) && recordId > 0 ? { sourceRecordId: recordId } : {}),
      depthResolution: '480'
    },
    resultSummary: '选择清晰度后确认提取'
  }
}

export function resolveStudioDepthSource(target: StudioCanvasNode, nodes: StudioCanvasNode[]): StudioCanvasNode | undefined {
  const options = target.data.videoToolOptions
  const source = nodes.find((item) => item.id === options?.sourceNodeId && item.data.kind === 'video')
  if (!source) return undefined
  return {
    ...source,
    data: {
      ...source.data,
      storyboardId: target.data.storyboardId,
      mediaUrl: options?.sourceVideoUrl || source.data.mediaUrl,
      recordId: options?.sourceRecordId,
      genRecordId: undefined
    }
  }
}
