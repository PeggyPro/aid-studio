import type { StudioNodeData } from '@/types/studio'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'

export const STUDIO_LABELS = [
  { id: 'red', name: '红色', color: '#f56573' },
  { id: 'orange', name: '橙色', color: '#ffa64d' },
  { id: 'yellow', name: '黄色', color: '#f2cf42' },
  { id: 'green', name: '绿色', color: '#4dcc82' },
  { id: 'cyan', name: '青色', color: '#42ced4' },
  { id: 'blue', name: '蓝色', color: '#49a6ff' },
  { id: 'purple', name: '紫色', color: '#ab67ed' },
  { id: 'pink', name: '粉色', color: '#ef8ac7' }
] as const
export type StudioLabelId = typeof STUDIO_LABELS[number]['id']
export const studioLabel = (id: unknown) => STUDIO_LABELS.find((label) => label.id === id)

/** 模板提示词不算已产生内容，媒体节点不能仅因填写生成指令而获得标签入口。 */
export function studioNodeHasLabelContent(data: StudioNodeData): boolean {
  if (data.flowBinding) return false
  if (data.mediaUrl?.trim()) return true
  if (data.gridStoryboard || data.directorSceneId) return true
  if (['image', 'video', 'voice'].includes(data.kind)) return false
  if (data.kind === 'text' || data.kind === 'storyboard_script') {
    return Boolean(resolveStudioScriptNodeText(data))
  }
  return Boolean(data.resultSummary?.trim() || (data.prompt?.trim() && data.prompt !== STUDIO_NODE_META[data.kind].defaultPrompt))
}
