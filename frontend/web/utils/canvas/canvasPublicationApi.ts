import type {
  CanvasCopyJob,
  CanvasModelUploadResult,
  CanvasPage,
  CanvasPublicationCard,
  CanvasPublicationDetail,
  CanvasPublicationState,
  CanvasResource,
  CanvasSceneDocument,
} from '@/types/canvas-api'
import {
  canvasApiBaseUrl,
  canvasApiPost,
  canvasApiReadPost,
} from './canvasApiClient'
import { normalizeCanvasPayload } from '@/utils/business/canvas'
import { unwrap } from '@/utils/business/shared'
import { runStudioReadSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

function read<T>(path: string, data: unknown) {
  return canvasApiReadPost<T>('/api/user/canvas/publication/' + path, data)
    .then(unwrap)
    .then(normalizeCanvasPayload)
}
function write<T>(path: string, data: unknown) {
  return canvasApiPost<T>('/api/user/canvas/publication/' + path, data)
    .then(unwrap)
    .then(normalizeCanvasPayload)
}
function publicRead<T>(path: string, data: unknown = {}) {
  return runStudioReadSingleFlight(
    () => canvasApiBaseUrl(),
    '/api/public/canvas/' + path,
    data,
    (snapshot) =>
      canvasApiPost<T>('/api/public/canvas/' + path, snapshot, {
        anonymous: true,
        requestTimeoutMs: 30000,
      })
        .then(unwrap)
        .then(normalizeCanvasPayload),
  )
}
export const canvasPublicationChannel = () =>
  publicRead<{
    items: Array<{ code: string; name: string; enabled: boolean }>
  }>('channel')
export const canvasPublicationList = (data: {
  pageNum: number
  pageSize: number
  keyword?: string
}) => publicRead<CanvasPage<CanvasPublicationCard>>('list', data)
export const canvasPublicationDetail = (publicationId: string) =>
  publicRead<CanvasPublicationDetail>('detail', { publicationId })
export const canvasPublicationStatus = (projectId: string) =>
  read<CanvasPublicationState>('status', { projectId })
export const canvasPublicationVersions = (projectId: string) =>
  read<
    Array<
      CanvasPublicationCard & { reviewStatus: string; reviewReason?: string }
    >
  >('versions', { projectId })
export const canvasPublicationVersionDetail = (publicationId: string) =>
  read<CanvasPublicationDetail>('version/detail', { publicationId })
export const canvasPublicationChange = (
  projectId: string,
  expectedVersion: string | number,
  action: 'WITHDRAW' | 'DISABLE_COPY' | 'CANCEL_REVIEW',
) =>
  write<CanvasPublicationState>('change', {
    projectId,
    expectedVersion,
    action,
  })
export const canvasPublicationScenesSave = (
  projectId: string,
  expectedVersion: string | number,
  document: CanvasSceneDocument,
) =>
  write<{ sceneVersion: string | number; stateVersion: string | number }>(
    'scenes/save',
    { projectId, expectedVersion, document },
  )
export const canvasPublicationCopy = (
  publicationId: string,
  operationId: string,
  projectName?: string,
) =>
  write<CanvasCopyJob>('copy', {
    publicationId,
    operationId,
    ...(projectName ? { projectName } : {}),
  })
export const canvasPublicationCopyStatus = (operationId: string) =>
  read<CanvasCopyJob>('copy/status', { operationId })
export const canvasPublicationCopyAction = (
  operationId: string,
  action: 'cancel' | 'retry',
) => write<CanvasCopyJob>('copy/' + action, { operationId })
export async function canvasPublicationModelUpload(
  workspaceId: string,
  operationId: string,
  mediaType: 'MODEL' | 'SCENE_PACKAGE',
  rootPath: string,
  files: Array<{ path: string; file: File }>,
  sceneAsset = false,
) {
  const data = new FormData()
  for (const [key, value] of Object.entries({
    workspaceId,
    operationId,
    mediaType,
    rootPath,
  }))
    data.append(key, value)
  for (const item of files) {
    data.append('paths', item.path)
    data.append('files', item.file)
  }
  const result = await write<CanvasModelUploadResult>(sceneAsset ? 'model/scene-asset' : 'model/upload', data)
  const root = result.bundle?.files.find(
    (file) => file.path === result.bundle.rootPath,
  )
  if (
    result.workspaceId !== workspaceId ||
    !root?.resourceUrl ||
    root.resourceId !== result.resourceId
  ) {
    throw new Error('模型登记回执与当前工作区或根文件不一致，请保留原文件重试')
  }
  // The upload response binds IDs and a bundle; its root file supplies the URL.
  // Never invent a resource/content URL or assume extra response fields exist.
  return {
    ...result,
    mediaType,
    resourceUrl: root.resourceUrl,
  } satisfies CanvasResource & CanvasModelUploadResult
}
