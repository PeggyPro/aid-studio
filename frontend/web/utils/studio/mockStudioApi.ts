import { DEFAULT_STUDIO_ZONES } from '@/config/studioMeta'
import type {
  DirectorSceneData,
  StudioCanvasNode,
  StudioMediaKind,
  StudioMode,
  StudioWorkspaceData
} from '@/types/studio'
import { mockOptimizePrompt } from './studioOptimizePrompt'
import { DEFAULT_STUDIO_CONVERSATION } from './studioConversation'
import { serializeStudioFlowLayoutWorkspace } from './studioFlowWorkspace'
import { validateStudioWorkspace } from './studioWorkspaceValidation'

const STORAGE_PREFIX = 'aid-studio-mock:v1:'
const STORAGE_REVISION_PREFIX = 'aid-studio-workspace-revision:v1:'
const STORAGE_BACKUP_PREFIX = 'aid-studio-workspace-backup:v1:'
const MAX_WORKSPACE_JSON_LENGTH = 20 * 1024 * 1024
const MOCK_DELAY_MS = 520

export interface StudioGenerationResult {
  summary: string
  mediaUrl?: string
}

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (callback: () => void) => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', abort)
      callback()
    }
    const timer = globalThis.setTimeout(() => finish(resolve), ms)
    const abort = () => {
      globalThis.clearTimeout(timer)
      finish(() => reject(new DOMException('任务已取消', 'AbortError')))
    }
    if (signal?.aborted) {
      abort()
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
  })
}

function storageKey(scopeKey: string): string {
  return `${STORAGE_PREFIX}${scopeKey}`
}

function revisionKey(scopeKey: string): string {
  return `${STORAGE_REVISION_PREFIX}${scopeKey}`
}

function backupKey(scopeKey: string): string {
  return `${STORAGE_BACKUP_PREFIX}${scopeKey}`
}

function getStorage(): Storage | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

type WorkspaceRevisionEnvelope = { revision: number; writerId?: string; savedAt?: string }

function readRevisionEnvelope(storage: Storage | null, scopeKey: string): WorkspaceRevisionEnvelope {
  try {
    const raw = storage?.getItem(revisionKey(scopeKey))
    const parsed = raw ? JSON.parse(raw) as Partial<WorkspaceRevisionEnvelope> : null
    const revision = Number(parsed?.revision)
    return {
      revision: Number.isSafeInteger(revision) && revision >= 0 ? revision : 0,
      writerId: typeof parsed?.writerId === 'string' ? parsed.writerId : undefined,
      savedAt: typeof parsed?.savedAt === 'string' ? parsed.savedAt : undefined
    }
  } catch {
    return { revision: 0 }
  }
}

export class StudioWorkspaceConflictError extends Error {
  constructor(public readonly expectedRevision: number, public readonly actualRevision: number) {
    super('检测到其他标签页已更新当前画布，请刷新后再继续编辑')
    this.name = 'StudioWorkspaceConflictError'
  }
}

function parseValidatedWorkspace(
  raw: string | null,
  scopeKey: string,
  requestedMode?: StudioMode
): StudioWorkspaceData | null {
  if (!raw || raw.length > MAX_WORKSPACE_JSON_LENGTH) return null
  try {
    return validateStudioWorkspace(
      JSON.parse(raw),
      scopeKey,
      requestedMode,
      createEmptyStudioWorkspace(scopeKey, requestedMode ?? 'flow')
    )?.workspace ?? null
  } catch {
    return null
  }
}

export function getStudioWorkspaceStorageKeys(scopeKey: string) {
  return { data: storageKey(scopeKey), revision: revisionKey(scopeKey), backup: backupKey(scopeKey) }
}

export function createEmptyStudioWorkspace(
  scopeKey: string,
  studioMode: StudioMode = 'flow'
): StudioWorkspaceData {
  return {
    version: 3,
    scopeKey,
    studioMode,
    title: 'AID Studio · 未命名作品',
    nodes: [],
    edges: [],
    zones: studioMode === 'flow' ? structuredClone(DEFAULT_STUDIO_ZONES) : [],
    groups: [],
    viewport: { x: 38, y: 72, zoom: studioMode === 'free' ? 1 : 0.78 },
    tasks: [],
    messages: [
      {
        id: 'welcome',
        role: 'assistant',
        content: '我是 Studio Agent。你可以让我先搭结构，也可以从模板或任意节点开始。',
        createdAt: new Date().toISOString()
      }
    ],
    conversation: structuredClone(DEFAULT_STUDIO_CONVERSATION),
    savedAssets: [],
    directorScenes: {},
    personalTemplates: [],
    personalSkills: [],
    reducedMotion: false,
    savedAt: new Date(0).toISOString()
  }
}

export const mockStudioApi = {
  async loadWorkspace(scopeKey: string, requestedMode?: StudioMode): Promise<StudioWorkspaceData> {
    await wait(180)
    const storage = getStorage()
    let sourceScopeKey = scopeKey
    let raw = storage?.getItem(storageKey(scopeKey)) ?? null
    if (!raw && requestedMode === 'flow' && scopeKey.endsWith(':mode-flow')) {
      const exactLegacyScopeKey = scopeKey.slice(0, -':mode-flow'.length)
      const defaultEpisodeScopeKey = exactLegacyScopeKey.replace(/:episode-[^:]+$/, ':episode-default')
      // 旧电影工作区使用 episode-default；剧集必须只消费自己的显式 episode key，
      // 否则同一份默认布局会被重复迁移进多个剧集，造成跨集专属节点/连线污染。
      const legacyScopeKeys = Array.from(new Set([
        exactLegacyScopeKey,
        ...(exactLegacyScopeKey.endsWith(':episode-0') ? [defaultEpisodeScopeKey] : [])
      ]))
      for (const legacyScopeKey of legacyScopeKeys) {
        const legacyRaw = storage?.getItem(storageKey(legacyScopeKey)) ?? null
        if (!legacyRaw) continue
        try {
          const candidate = JSON.parse(legacyRaw) as StudioWorkspaceData
          if (
            ![1, 2, 3].includes(candidate.version) ||
            candidate.scopeKey !== legacyScopeKey ||
            candidate.studioMode !== 'flow'
          ) {
            continue
          }
        } catch {
          continue
        }
        raw = legacyRaw
        sourceScopeKey = legacyScopeKey
        break
      }
    }
    if (!raw) return createEmptyStudioWorkspace(scopeKey, requestedMode ?? 'flow')
    try {
      const parsed = parseValidatedWorkspace(raw, sourceScopeKey, requestedMode)
        ?? parseValidatedWorkspace(storage?.getItem(backupKey(sourceScopeKey)) ?? null, sourceScopeKey, requestedMode)
      if (!parsed) return createEmptyStudioWorkspace(scopeKey, requestedMode ?? 'flow')
      const legacyFlowMigration = sourceScopeKey !== scopeKey
      if (legacyFlowMigration && parsed.studioMode !== 'flow') return createEmptyStudioWorkspace(scopeKey, 'flow')
      if (!legacyFlowMigration && requestedMode === 'free' && parsed.studioMode === 'flow') {
        const flowScopeKey = `${scopeKey}:mode-flow`
        if (!storage?.getItem(storageKey(flowScopeKey))) {
          const migrated = serializeStudioFlowLayoutWorkspace({ ...parsed, scopeKey: flowScopeKey })
          storage?.setItem(storageKey(flowScopeKey), JSON.stringify(migrated))
        }
        return createEmptyStudioWorkspace(scopeKey, 'free')
      }
      const resolvedMode = requestedMode ?? parsed.studioMode ?? 'flow'
      const flowMode = resolvedMode === 'flow'
      const remoteTargetIds = new Set(
        (parsed.tasks ?? []).flatMap((task) =>
          task.remoteTaskId && (task.status === 'running' || task.status === 'queued' || task.status === 'waiting')
            ? [...(task.targetNodeIds ?? []), ...(task.nodeId ? [task.nodeId] : [])]
            : []
        )
      )
      const workspace: StudioWorkspaceData = {
        ...parsed,
        scopeKey,
        studioMode: resolvedMode,
        nodes: (parsed.nodes ?? []).map((node) =>
          !flowMode && (node.data.status === 'generating' || node.data.status === 'queued') && !remoteTargetIds.has(node.id)
            ? {
                ...node,
                data: {
                  ...node.data,
                  status: 'failed' as const,
                  operationRunId: undefined,
                  audioRunId: undefined,
                  errorMessage: '预览任务因页面刷新暂停，可重新生成'
                }
              }
            : node
        ),
        zones: resolvedMode === 'free'
          ? []
          : parsed.zones?.length ? parsed.zones : structuredClone(DEFAULT_STUDIO_ZONES),
        groups: parsed.groups ?? [],
        tasks: (parsed.tasks ?? []).map((task) =>
          !flowMode && (task.status === 'running' || task.status === 'queued') && !task.remoteTaskId
            ? {
                ...task,
                status: 'failed' as const,
                errorMessage: '预览任务因页面刷新暂停，可在任务区局部重试',
                updatedAt: new Date().toISOString()
              }
            : !flowMode && task.remoteTaskId && (task.status === 'running' || task.status === 'queued')
              ? {
                  ...task,
                  status: 'waiting' as const,
                  stage: '正在恢复真实任务状态',
                  updatedAt: new Date().toISOString()
                }
              : task
        ),
        messages: (parsed.messages ?? []).map((item) =>
          !flowMode && (item.status === 'thinking' || item.status === 'generating')
            ? {
                ...item,
                status: 'failed' as const,
                content: item.content || '本次画布生成因页面刷新中断，请重新发送。'
              }
            : item
        ),
        conversation: parsed.conversation ?? structuredClone(DEFAULT_STUDIO_CONVERSATION),
        savedAssets: parsed.savedAssets ?? [],
        directorScenes: parsed.directorScenes ?? {},
        personalTemplates: parsed.personalTemplates ?? [],
        personalSkills: parsed.personalSkills ?? []
      }
      if (legacyFlowMigration) {
        storage?.setItem(
          storageKey(scopeKey),
          JSON.stringify(serializeStudioFlowLayoutWorkspace(workspace))
        )
      }
      return workspace
    } catch {
      return createEmptyStudioWorkspace(scopeKey, requestedMode ?? 'flow')
    }
  },

  readWorkspaceRevision(scopeKey: string): WorkspaceRevisionEnvelope {
    return readRevisionEnvelope(getStorage(), scopeKey)
  },

  async saveWorkspace(
    workspace: StudioWorkspaceData,
    options?: { expectedRevision?: number; writerId?: string }
  ): Promise<{ savedAt: string; revision: number }> {
    await wait(130)
    return this.saveWorkspaceSync(workspace, options)
  },

  saveWorkspaceSync(
    workspace: StudioWorkspaceData,
    options?: { expectedRevision?: number; writerId?: string }
  ): { savedAt: string; revision: number } {
    const storage = getStorage()
    const current = readRevisionEnvelope(storage, workspace.scopeKey)
    if (options?.expectedRevision != null && current.revision !== options.expectedRevision) {
      throw new StudioWorkspaceConflictError(options.expectedRevision, current.revision)
    }
    const savedAt = new Date().toISOString()
    const key = storageKey(workspace.scopeKey)
    const previous = storage?.getItem(key)
    if (previous) storage?.setItem(backupKey(workspace.scopeKey), previous)
    storage?.setItem(key, JSON.stringify({ ...workspace, savedAt }))
    const revision = current.revision + 1
    storage?.setItem(revisionKey(workspace.scopeKey), JSON.stringify({
      revision,
      writerId: options?.writerId,
      savedAt
    } satisfies WorkspaceRevisionEnvelope))
    return { savedAt, revision }
  },

  async generateNode(
    node: StudioCanvasNode,
    signal?: AbortSignal
  ): Promise<StudioGenerationResult> {
    await wait(MOCK_DELAY_MS + Math.round(Math.random() * 420), signal)
    if (/失败|fail/i.test(node.data.prompt)) {
      throw new Error('Mock 模型未能生成有效结果，请修改描述后局部重试')
    }
    const mediaKind = node.data.mediaKind
    return {
      summary: generationSummary(node.data.title, mediaKind),
      mediaUrl: mediaKind === 'image' || mediaKind === 'video'
        ? createMockMediaDataUrl(node.data.title, mediaKind)
        : undefined
    }
  },

  async planPipeline(prompt: string, signal?: AbortSignal): Promise<{ summary: string }> {
    await wait(680, signal)
    if (/失败|fail/i.test(prompt)) throw new Error('结构规划失败，请缩短输入后重试')
    return { summary: `已根据“${prompt.slice(0, 32)}”搭建标准短剧结构` }
  },

  async saveDirectorScene(scene: DirectorSceneData): Promise<DirectorSceneData> {
    await wait(180)
    return { ...structuredClone(scene), updatedAt: new Date().toISOString() }
  },

  async generateDirectorPanorama(prompt: string): Promise<{ mediaUrl: string }> {
    await wait(720)
    return { mediaUrl: createDirectorPanoramaDataUrl(prompt) }
  },

  async recognizeDirectorReference(_mediaUrl: string): Promise<{ actorCount: number; useAsPanorama: boolean }> {
    await wait(760)
    return { actorCount: 3, useAsPanorama: true }
  },

  optimizePrompt: mockOptimizePrompt
}

function generationSummary(title: string, mediaKind?: StudioMediaKind): string {
  if (mediaKind === 'video') return `${title} 已完成 5 秒镜头预演（Mock）`
  if (mediaKind === 'audio') return `${title} 已完成对白与环境声预演（Mock）`
  if (mediaKind === 'text') return `${title} 已完成文本草案（Mock）`
  return `${title} 已完成视觉草图（Mock）`
}

function createMockMediaDataUrl(title: string, mediaKind: 'image' | 'video'): string {
  const label = mediaKind === 'video' ? 'VIDEO PREVIEW' : 'IMAGE PREVIEW'
  const safeTitle = title.replace(/[<>&"']/g, '').slice(0, 28)
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#102a46"/><stop offset="0.48" stop-color="#0c6f87"/><stop offset="1" stop-color="#5d2c7d"/></linearGradient><radialGradient id="r"><stop stop-color="#4ae7fd" stop-opacity=".5"/><stop offset="1" stop-color="#4ae7fd" stop-opacity="0"/></radialGradient></defs><rect width="640" height="360" rx="28" fill="url(#g)"/><circle cx="480" cy="70" r="170" fill="url(#r)"/><path d="M70 270 180 150l75 88 88-126 150 158Z" fill="#bdf7ff" fill-opacity=".22"/><text x="48" y="62" fill="#4ae7fd" font-family="Arial" font-size="18" font-weight="700">${label}</text><text x="48" y="320" fill="white" font-family="Arial" font-size="28" font-weight="700">${safeTitle}</text></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

function createDirectorPanoramaDataUrl(prompt: string): string {
  const safePrompt = prompt.replace(/[<>&"']/g, '').slice(0, 36)
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1536" height="768" viewBox="0 0 1536 768"><defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#24354f"/><stop offset=".52" stop-color="#5f6f72"/><stop offset="1" stop-color="#22262d"/></linearGradient><radialGradient id="sun"><stop stop-color="#fff7ce" stop-opacity=".9"/><stop offset="1" stop-color="#f5a15d" stop-opacity="0"/></radialGradient></defs><rect width="1536" height="768" fill="url(#sky)"/><circle cx="1180" cy="190" r="210" fill="url(#sun)"/><path d="M0 510 180 380 330 470 520 300 720 480 910 340 1110 455 1300 320 1536 470V768H0Z" fill="#18212a"/><path d="M0 590 250 500 450 610 710 520 980 625 1210 530 1536 610V768H0Z" fill="#10171e"/><text x="64" y="704" fill="#e7eef7" font-family="Arial" font-size="30" opacity=".82">${safePrompt}</text></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}
