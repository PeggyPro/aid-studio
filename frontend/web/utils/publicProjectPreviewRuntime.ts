import type {
  ProjectSnapshotFlowNode,
  ProjectSnapshotPreviewData
} from '~/types/business-api'

interface PreviewRuntimeState {
  token: symbol
  snapshot: ProjectSnapshotPreviewData
}

interface PreviewRequestResolution {
  handled: boolean
  payload?: unknown
  error?: Error
}

let activeRuntime: PreviewRuntimeState | null = null

const MUTATION_ACTIONS = new Set([
  'advance', 'autosave', 'save', 'create', 'update', 'delete', 'generate', 'upload',
  'copy', 'publish', 'unpublish', 'submit-audit', 'bind', 'unbind', 'sort', 'split',
  'upscale', 'setfinal', 'unsetfinal', 'use', 'unuse', 'export', 'compose', 'stop',
  'restart', 'resume', 'cancel', 'retry'
])

function normalizeEndpoint(url: string): string {
  const raw = String(url || '').trim()
  if (!raw) return ''
  try {
    return new URL(raw, 'https://preview.local').pathname.replace(/^\/url(?=\/api\/)/, '')
  } catch {
    return raw.split('?')[0].replace(/^\/url(?=\/api\/)/, '')
  }
}

function isMutationEndpoint(method: string, endpoint: string): boolean {
  const normalizedMethod = String(method || '').toLowerCase()
  if (normalizedMethod === 'put' || normalizedMethod === 'patch' || normalizedMethod === 'delete') {
    return true
  }
  if (normalizedMethod === 'get' || normalizedMethod === 'head') return false
  return endpoint.split('/').filter(Boolean).some((rawSegment) => {
    const segment = rawSegment.toLowerCase()
    if (MUTATION_ACTIONS.has(segment)) return true
    return Array.from(MUTATION_ACTIONS).some((action) =>
      segment.startsWith(`${action}-`) ||
      (action === 'setfinal' && segment.startsWith('setfinal')) ||
      (action === 'unsetfinal' && segment.startsWith('unsetfinal')) ||
      (action === 'autosave' && segment.startsWith('autosave'))
    )
  })
}

function envelope(data: unknown, rows?: unknown[]) {
  return {
    code: 200,
    msg: 'success',
    data,
    ...(rows ? { rows, total: rows.length } : {})
  }
}

function nodeEpisodeId(node: ProjectSnapshotFlowNode): number | null {
  const dataId = Number(node.data.episodeId)
  if (Number.isFinite(dataId) && dataId >= 0) return dataId
  const metaId = Number(node.snapshotMeta?.episodeId)
  return Number.isFinite(metaId) && metaId >= 0 ? metaId : null
}

function matchesNumber(value: unknown, expected: unknown): boolean {
  const left = Number(value)
  const right = Number(expected)
  return Number.isFinite(left) && Number.isFinite(right) && left === right
}

function selectByRequest(
  nodes: ProjectSnapshotFlowNode[],
  body: Record<string, unknown>
): ProjectSnapshotFlowNode[] {
  const episodeId = body.episodeId
  const storyboardId = body.storyboardId ?? body.id
  const assetId = body.assetId
  return nodes.filter((node) => {
    if (episodeId != null) {
      const nodeId = nodeEpisodeId(node)
      if (nodeId != null && !matchesNumber(nodeId, episodeId)) return false
    }
    if (storyboardId != null) {
      const candidate = node.data.storyboardId ?? node.data.id
      if (candidate != null && !matchesNumber(candidate, storyboardId)) return false
    }
    if (assetId != null) {
      const candidate = node.data.assetId ?? node.data.id
      if (candidate != null && !matchesNumber(candidate, assetId)) return false
    }
    return true
  })
}

function nodesForEndpoint(snapshot: ProjectSnapshotPreviewData, endpoint: string) {
  return snapshot.nodes
    .filter((node) => normalizeEndpoint(String(node.sourceEndpoint ?? '')) === endpoint)
    .sort((left, right) => Number(left.order) - Number(right.order))
}

function nodesForType(snapshot: ProjectSnapshotPreviewData, type: string) {
  return snapshot.nodes
    .filter((node) => node.type === type)
    .sort((left, right) => Number(left.order) - Number(right.order))
}

function endpointFallbackType(endpoint: string): string | null {
  const mapping: Record<string, string> = {
    '/api/user/project/detail': 'project',
    '/api/user/episode/list': 'episode',
    '/api/user/episode/detail': 'episode',
    '/api/user/script/detailByProject': 'script',
    '/api/user/asset/rps/list': 'asset',
    '/api/user/storyboard/list': 'storyboard',
    '/api/user/project/gen-config/get': 'generation-config',
    '/api/user/storyboard/record/list-by-storyboard': 'media',
    '/api/user/storyboard/audio/detail': 'audio',
    '/api/user/asset/rps/voice/query': 'voice-binding',
    '/api/user/episode/timeline/get': 'timeline'
  }
  return mapping[endpoint] ?? null
}

function flattenAssetForms(snapshot: ProjectSnapshotPreviewData): Array<Record<string, unknown>> {
  return nodesForType(snapshot, 'asset').flatMap((node) => {
    const forms = Array.isArray(node.data.forms) ? node.data.forms : []
    return forms.map((form) => {
      const row = form as Record<string, unknown>
      return {
        ...row,
        assetId: row.assetId ?? node.data.id,
        episodeId: row.episodeId ?? nodeEpisodeId(node) ?? undefined
      }
    })
  })
}

function flattenAssetFormImages(snapshot: ProjectSnapshotPreviewData): Array<Record<string, unknown>> {
  return flattenAssetForms(snapshot).flatMap((form) => {
    const images = Array.isArray(form.images) ? form.images : []
    return images.map((image) => {
      const row = image as Record<string, unknown>
      return {
        ...row,
        assetId: row.assetId ?? form.assetId,
        formId: row.formId ?? form.id
      }
    })
  })
}

function buildStepState(snapshot: ProjectSnapshotPreviewData) {
  const episodeNode = nodesForType(snapshot, 'episode')[0]
  const rawStep = Number(episodeNode?.snapshotMeta?.currentStep)
  const snapshotCurrentStep = Number.isFinite(rawStep)
    ? Math.min(Math.max(Math.floor(rawStep), 1), 7)
    : 7
  return {
    currentStep: 7,
    steps: Array.from({ length: 7 }, (_, index) => ({
      step: index + 1,
      name: '',
      status: index + 1 < 7 ? 'completed' : 'current'
    })),
    snapshotCurrentStep
  }
}

function readonlyError(endpoint: string): Error {
  return Object.assign(new Error('公开工程为只读预览，不能执行编辑或生成操作'), {
    code: 'PUBLIC_PROJECT_READONLY',
    endpoint
  })
}

function safeEmptyPayload(endpoint: string): unknown {
  if (/\/(?:list|query)(?:\/|$)/i.test(endpoint)) return envelope([], [])
  return envelope(null)
}

function matchesAssetType(data: Record<string, unknown>, requested: unknown): boolean {
  const expected = String(requested ?? '').trim().toLowerCase()
  if (!expected) return true
  return String(data.assetType ?? '').trim().toLowerCase() === expected
}

function matchesRecordType(data: Record<string, unknown>, requested: unknown): boolean {
  const expected = String(requested ?? '').trim().toLowerCase()
  if (!expected) return true
  const genType = String(data.genType ?? data.mediaType ?? '').trim().toLowerCase()
  if (expected === 'image') return genType.includes('image') || genType.includes('grid')
  if (expected === 'compose') return genType.includes('compose') || genType.includes('dubbing')
  if (expected === 'video') {
    return !genType.includes('compose') && !genType.includes('dubbing') && (
      genType.includes('video') || genType.includes('i2v') || genType.includes('multi') ||
      genType.includes('edge')
    )
  }
  return genType === expected
}

/** 激活单个公开工程快照；返回的清理函数只会关闭本次激活，避免路由竞态误清新快照。 */
export function activatePublicProjectPreviewRuntime(snapshot: ProjectSnapshotPreviewData): () => void {
  const token = Symbol(`public-project-preview:${snapshot.snapshotId}`)
  activeRuntime = { token, snapshot }
  return () => {
    if (activeRuntime?.token === token) activeRuntime = null
  }
}

export function isPublicProjectPreviewRuntimeActive(): boolean {
  return activeRuntime != null
}

/**
 * 把公开快照按 sourceEndpoint 投影成正常流程页原本读取的响应。
 * 所有 /api/user/** 都在浏览器发出前收口：读取走冻结快照，写入直接拒绝。
 */
export function resolvePublicProjectPreviewRequest(
  method: string,
  url: string,
  input?: unknown
): PreviewRequestResolution {
  const runtime = activeRuntime
  if (!runtime) return { handled: false }
  const endpoint = normalizeEndpoint(url)
  if (!endpoint.startsWith('/api/user/')) return { handled: false }
  if (isMutationEndpoint(method, endpoint)) {
    return { handled: true, error: readonlyError(endpoint) }
  }

  const body = input && typeof input === 'object'
    ? (input as Record<string, unknown>)
    : {}
  const snapshot = runtime.snapshot
  const endpointType = endpointFallbackType(endpoint)
  const directNodes = nodesForEndpoint(snapshot, endpoint)
  const endpointNodes = directNodes.length || !endpointType
    ? directNodes
    : nodesForType(snapshot, endpointType)
  const selected = selectByRequest(endpointNodes, body)

  switch (endpoint) {
    case '/api/user/project/detail': {
      const project = selected[0]?.data ?? nodesForType(snapshot, 'project')[0]?.data ?? {}
      return {
        handled: true,
        payload: envelope({
          ...project,
          id: snapshot.projectId,
          projectName: snapshot.projectName,
          projectType: snapshot.projectType
        })
      }
    }
    case '/api/user/episode/list': {
      const rows = endpointNodes
        .map((node) => node.data)
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/episode/detail':
    case '/api/user/script/detailByProject':
    case '/api/user/asset/rps/voice/query':
    case '/api/user/episode/timeline/get':
      return { handled: true, payload: envelope(selected[0]?.data ?? null) }
    case '/api/user/storyboard/audio/detail': {
      const audio = selected[0]
      const assets = Array.isArray(audio?.snapshotMeta?.assets)
        ? audio.snapshotMeta.assets
        : undefined
      return {
        handled: true,
        payload: envelope(audio ? { ...audio.data, ...(assets ? { assets } : {}) } : null)
      }
    }
    case '/api/user/storyboard/list':
    case '/api/user/project/gen-config/get': {
      const rows = selected.map((node) => node.data)
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/asset/rps/list': {
      const rows = selected
        .map((node) => node.data)
        .filter((data) => matchesAssetType(data, body.assetType))
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/storyboard/record/list-by-storyboard': {
      const rows = selected
        .map((node) => node.data)
        .filter((data) => matchesRecordType(data, body.type))
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/asset/rps/form/list': {
      const rows = flattenAssetForms(snapshot).filter((form) =>
        body.assetId == null || matchesNumber(form.assetId, body.assetId)
      )
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/asset/rps/form-image/list': {
      const rows = flattenAssetFormImages(snapshot).filter((image) =>
        (body.assetId == null || matchesNumber(image.assetId, body.assetId)) &&
        (body.formId == null || matchesNumber(image.formId, body.formId))
      )
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/script/list': {
      const rows = selectByRequest(nodesForType(snapshot, 'script'), body).map((node) => node.data)
      return { handled: true, payload: envelope(rows, rows) }
    }
    case '/api/user/storyboard/detail': {
      const rows = selectByRequest(nodesForType(snapshot, 'storyboard'), body)
      return { handled: true, payload: envelope(rows[0]?.data ?? null) }
    }
    case '/api/user/step/status':
      return { handled: true, payload: envelope(buildStepState(snapshot)) }
    case '/api/user/task/list':
      return { handled: true, payload: envelope([], []) }
    default: {
      const audioTaskMatch = endpoint.match(/^\/api\/user\/storyboard\/audio\/(\d+)$/)
      if (audioTaskMatch) {
        const taskId = Number(audioTaskMatch[1])
        const audio = nodesForType(snapshot, 'audio').find((node) =>
          matchesNumber(node.data.id ?? node.data.taskId, taskId)
        )
        const assets = Array.isArray(audio?.snapshotMeta?.assets)
          ? audio.snapshotMeta.assets
          : undefined
        return {
          handled: true,
          payload: envelope(audio ? { ...audio.data, ...(assets ? { assets } : {}) } : null)
        }
      }
      return { handled: true, payload: safeEmptyPayload(endpoint) }
    }
  }
}
