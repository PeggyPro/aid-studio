import { createRandomUuid } from '@/utils/clientId'
import { maybeDecryptApiPayload } from '@/utils/apiCrypto'
import { canvasApiBaseUrl, prepareCanvasApiPayload, resolveCanvasApiUrl } from '@/utils/canvas/canvasApiClient'
import { parseCanvasApiJson } from '@/utils/canvas/parseCanvasApiJson'
import type { DirectorSceneData } from '@/types/studio'

export interface DirectorScope {
  projectId: string
  workspaceId?: string
}
export interface DirectorCameraRoom {
  sessionId: string
  roomEpoch: string
  state: string
  stateVersion: number
  peerEpoch: number
  bindingEpoch: number
  cameraId: string
  hostOnline: boolean
  guestOnline: boolean
  expiresAtMs: number
  serverTimeMs: number
  networkPolicy: 'LAN_DIRECT_ONLY'
  signalingPath: string
  negotiationId?: string
  negotiationUntilMs?: number
  peerBindingNonce?: string
  controlFence?: string
  pairingCode?: string
  pairingUrl?: string
  pairingExpiresAtMs?: number
  pairingRequired?: boolean
  guestGrant?: string
  wsTicket?: string
}
export interface DirectorCameraLease {
  leaseSeq: number
  expiresAtMs: number
  serverTimeMs: number
  roomEpoch: string
  peerEpoch: number
  bindingEpoch: number
  permissions: string[]
}
export class DirectorCameraError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly status?: number
  ) {
    super(message)
  }
}
const flights = new Map<string, Promise<unknown>>()
/** Isolated encrypted transport: guest never inherits the user's bearer token or login redirects. */
export function directorCameraPost<T>(path: string, body: unknown, guestGrant?: string | null, keepalive = false): Promise<T> {
  const identity = guestGrant === undefined ? localStorage.getItem('token') : guestGrant
  const key = JSON.stringify([canvasApiBaseUrl(), path, body, identity])
  const active = flights.get(key)
  if (active) return active as Promise<T>
  const promise = (async () => {
    const enc = await prepareCanvasApiPayload(body),
      headers = { ...enc.headers } as Record<string, string>
    if (guestGrant !== undefined) {
      if (guestGrant) headers['X-Virtual-Camera-Grant'] = guestGrant
    } else {
      const token = localStorage.getItem('token')
      if (!token) throw new DirectorCameraError('请先登录后连接手机', 'AUTH_REQUIRED', 401)
      if (token !== identity) throw new DirectorCameraError('登录身份已变化，请重试', 'AUTH_CHANGED', 401)
      headers.Authorization = `Bearer ${token}`
    }
    const abort = new AbortController()
    const timeout = setTimeout(() => abort.abort(), 15000)
    try {
      const response = await fetch(resolveCanvasApiUrl(`/api/user/studio/director/${path}`), {
        method: 'POST',
        headers,
        body: enc.body,
        cache: 'no-store',
        credentials: 'omit',
        keepalive,
        signal: abort.signal
      })
      const data = await maybeDecryptApiPayload<{
        code: number
        msg?: string
        data: T & { errorCode?: string }
      }>(parseCanvasApiJson(await response.text()), enc.aesKey, parseCanvasApiJson)
      if (guestGrant === undefined && localStorage.getItem('token') !== identity)
        throw new DirectorCameraError('登录身份已变化，请重试', 'AUTH_CHANGED', 401)
      if (!response.ok || data.code !== 200)
        throw new DirectorCameraError(data.msg || '虚拟相机请求失败', data.data?.errorCode, response.status)
      return data.data
    } catch (error) {
      if (abort.signal.aborted) throw new DirectorCameraError('配对服务请求超时，请点此重试', 'REQUEST_TIMEOUT')
      if (error instanceof SyntaxError) throw new DirectorCameraError('配对服务响应异常，请检查当前站点的网关配置', 'INVALID_RESPONSE')
      if (error instanceof TypeError) throw new DirectorCameraError('无法访问配对服务，请检查手机网络后重试', 'NETWORK_UNAVAILABLE')
      throw error
    } finally {
      clearTimeout(timeout)
    }
  })().finally(() => flights.delete(key))
  flights.set(key, promise)
  return promise
}
export const directorCameraApi = {
  create: (scope: DirectorScope, sceneId: string, cameraId: string, requestId: string) =>
    directorCameraPost<DirectorCameraRoom>('virtual-camera/session/create', {
      ...scope,
      sceneId,
      cameraId,
      requestId,
      protocolVersion: 1
    }),
  join: (sessionId: string, pairingCode: string) =>
    directorCameraPost<DirectorCameraRoom>(
      'virtual-camera/session/join',
      { sessionId, pairingCode, protocolVersion: 1, deviceName: '手机浏览器' },
      null
    ),
  state: (sessionId: string, grant?: string) =>
    directorCameraPost<DirectorCameraRoom>('virtual-camera/session/state', { sessionId }, grant),
  ticket: (sessionId: string, grant?: string) =>
    directorCameraPost<{ wsTicket: string; signalingPath: string }>(
      'virtual-camera/session/ticket',
      { sessionId },
      grant
    ),
  negotiate: (room: DirectorCameraRoom) =>
    directorCameraPost<DirectorCameraRoom>('virtual-camera/session/negotiate', {
      sessionId: room.sessionId,
      requestId: createRandomUuid(),
      expectedPeerEpoch: room.peerEpoch
    }),
  action: (room: DirectorCameraRoom, action: string, cameraId?: string) =>
    directorCameraPost<DirectorCameraRoom>('virtual-camera/session/action', {
      sessionId: room.sessionId,
      requestId: createRandomUuid(),
      expectedRoomEpoch: room.roomEpoch,
      expectedStateVersion: room.stateVersion,
      action,
      ...(cameraId ? { cameraId } : {})
    }),
  lease: (room: DirectorCameraRoom) =>
    directorCameraPost<DirectorCameraLease>('virtual-camera/session/lease', {
      sessionId: room.sessionId,
      peerEpoch: room.peerEpoch,
      bindingEpoch: room.bindingEpoch
    }),
  close: (sessionId: string) =>
    directorCameraPost<DirectorCameraRoom>('virtual-camera/session/close', { sessionId }, undefined, true),
  detail: (scope: DirectorScope, sceneId: string) =>
    directorCameraPost<{ revision: number; scene: DirectorSceneData }>('scene/detail', { ...scope, sceneId }),
  save: (scope: DirectorScope, scene: DirectorSceneData, requestId: string) =>
    directorCameraPost<{ revision: number }>('scene/save', {
      ...scope,
      sceneId: scene.id,
      requestId,
      expectedRevision: scene.revision ?? 0,
      name: scene.name.slice(0, 100),
      scene: directorCameraScene(scene)
    })
}
/** Server camera registry is a projection; the full canvas scene remains in canvas persistence. */
export function directorCameraScene(scene: DirectorSceneData) {
  const camera = (c: DirectorSceneData['camera']) => ({
    position: c.position,
    target: c.target,
    fov: c.fov,
    roll: c.roll ?? 0
  })
  return {
    id: scene.id,
    name: scene.name,
    objects: [],
    modelAssets: [],
    lights: [],
    camera: camera(scene.camera),
    cameraBookmarks: scene.cameraBookmarks?.map((c) => ({
      id: c.id,
      camera: camera(c.camera),
      lens: c.lens,
      locked: c.locked ?? false
    })),
    mainCameraLens: scene.mainCameraLens,
    mainCameraLocked: scene.mainCameraLocked ?? false,
    activeCameraId: scene.activeCameraId,
    timeline: { duration: scene.timeline?.duration ?? 10, pathClips: (scene.timeline?.pathClips ?? []).flatMap(clip => {
      const take = clip as typeof clip & { takeId?: string }
      return take.source === 'virtual-camera' && take.takeId ? [{
        id: take.id, targetKind: take.targetKind, targetId: take.targetId, source: take.source,
        takeId: take.takeId, sessionId: take.sessionId, start: take.start, end: take.end
      }] : []
    }) },
    updatedAt: scene.updatedAt
  }
}
export function directorCameraErrorText(error: unknown) {
  const e = error as DirectorCameraError
  return (
    (
      {
        INVALID_PAIRING: '配对码已过期或已使用，请在电脑刷新二维码',
        INVALID_GRANT: '配对授权已失效，请重新扫码',
        GUEST_FULL: '已有手机连接，请在电脑重新配对',
        CAPACITY_REACHED: '连接名额暂不可用。请关闭其他设备上的旧机位后重试',
        PEER_OFFLINE: '电脑机位尚未在线，请在电脑端确认配对面板仍然打开',
        SESSION_EXPIRED: '会话已过期，请重新连接',
        SESSION_UNAVAILABLE: '电脑机位会话不可用，请从电脑重新扫码',
        NETWORK_POLICY_REJECTED: '请让手机和电脑连接可互通的同一局域网',
        RTC_NEGOTIATION_FAILED: '局域网直连协商超时，请重试',
        VIDEO_NOT_READY: '未收到完整的机位视频，请重试',
        CAMERA_BUSY: '机位已被占用，请先断开原会话',
        FEATURE_DISABLED: '当前服务尚未启用手机运镜',
        RESOURCE_UNAVAILABLE: '场景资源暂不可用',
        STALE_STATE: '会话状态已变化，请重试',
        FENCE_EXPIRED: '机位控制权已失效，请重新连接'
      } as Record<string, string>
    )[e?.code ?? ''] ??
    e?.message ??
    '连接中断，请重试'
  )
}
