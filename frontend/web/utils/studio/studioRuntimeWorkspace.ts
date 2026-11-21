import type { CanvasRuntimeMember, CanvasRuntimeOutputKind, CanvasRuntimeStatus } from '@/types/canvas-runtime-api'
import type { StudioNodeKind } from '@/types/studio'

export type StudioRuntimeWorkspaceView = 'run' | 'library'

const NODE_KIND_OUTPUT: Partial<Record<StudioNodeKind, CanvasRuntimeOutputKind>> = {
  text: 'TEXT',
  image: 'IMAGE',
  video: 'VIDEO'
}

const NODE_KIND_LABEL: Partial<Record<StudioNodeKind, string>> = {
  text: '文本节点',
  image: '图片节点',
  video: '视频节点'
}

const STATUS_COPY: Record<CanvasRuntimeStatus, string> = {
  CREATED: '已创建',
  RUNNING: '运行中',
  WAITING_INPUT: '等待回复',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
}

export function studioRuntimeOutputKind(kind: StudioNodeKind): CanvasRuntimeOutputKind | null {
  return NODE_KIND_OUTPUT[kind] ?? null
}

export function studioRuntimeNodeKindLabel(kind: StudioNodeKind): string {
  return NODE_KIND_LABEL[kind] ?? '当前节点'
}

export function compatibleCanvasRuntimeMembers(
  members: CanvasRuntimeMember[],
  outputKind: CanvasRuntimeOutputKind
): CanvasRuntimeMember[] {
  return members.filter((item) => item.outputKind === outputKind)
}

export function studioRuntimeStatusCopy(status: string): string {
  return STATUS_COPY[status as CanvasRuntimeStatus] ?? status
}

export function studioRuntimeMemberTypeCopy(type: string): string {
  if (type === 'SKILL') return 'Skill'
  if (type === 'AGENT') return 'Agent'
  return type
}

export function studioRuntimeRunLabel(run: {
  memberType?: string | null
  memberCode?: string | null
  status: string
  runId?: string | number | null
}): string {
  const title = run.memberCode || studioRuntimeMemberTypeCopy(String(run.memberType || '运行'))
  const id = run.runId == null || String(run.runId).trim() === '' ? '' : ` · ${run.runId}`
  return `${title} · ${studioRuntimeStatusCopy(run.status)}${id}`
}
