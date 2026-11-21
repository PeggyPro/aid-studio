import { createRandomUuid } from '@/utils/clientId'
import { canvasApiBaseUrl } from '@/utils/canvas/canvasApiClient'
import { directorCameraApi, DirectorCameraError, type DirectorCameraRoom, type DirectorScope } from './directorCameraApi'

interface HostRoomIntent {
  scope: DirectorScope
  sceneId: string
  cameraId: string
  requestId: string
  sessionId?: string
}

const flights = new Map<string, Promise<unknown>>()
const memory = new Map<string, HostRoomIntent>()

function readIntent(key: string): HostRoomIntent | undefined {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? 'null')
    return value?.scope?.projectId && value.sceneId && value.cameraId && value.requestId ? value : memory.get(key)
  } catch {
    return memory.get(key)
  }
}

function writeIntent(key: string, value?: HostRoomIntent) {
  try {
    if (value) localStorage.setItem(key, JSON.stringify(value))
    else localStorage.removeItem(key)
    memory.delete(key)
  } catch {
    if (value) memory.set(key, value)
    else memory.delete(key)
  }
}

function serialize<T>(key: string, run: () => Promise<T>): Promise<T> {
  const pending = flights.get(key) ?? Promise.resolve()
  const next = pending.catch(() => {}).then(async () => {
    if (navigator.locks) return await navigator.locks.request(key, run)
    return run()
  }).finally(() => {
    if (flights.get(key) === next) flights.delete(key)
  })
  flights.set(key, next)
  return next
}

function create(intent: HostRoomIntent) {
  return directorCameraApi.create(intent.scope, intent.sceneId, intent.cameraId, intent.requestId)
}

function roomGone(error: unknown) {
  return error instanceof DirectorCameraError && (error.status === 404 || error.code === 'SESSION_EXPIRED')
}

export function directorHostRooms(actorId: string, token: string | null) {
  const key = `aid-vcam-host:${canvasApiBaseUrl()}:${actorId}`
  const sameLogin = () => token === localStorage.getItem('token')
  const forget = (intent: HostRoomIntent) => {
    if (readIntent(key)?.requestId === intent.requestId) writeIntent(key)
  }
  const closeRoom = async (sessionId: string) => {
    try { await directorCameraApi.close(sessionId) }
    catch (error) { if (!roomGone(error)) throw error }
  }
  const release = async (intent: HostRoomIntent, isCurrent = sameLogin) => {
    let sessionId = intent.sessionId
    if (!sessionId) {
      // The server reserves a request ID before it writes the room. Until that
      // write completes, replay can return SESSION_UNAVAILABLE for the same ID.
      for (const delay of [0, 300, 1000]) {
        if (delay) await new Promise(resolve => setTimeout(resolve, delay))
        if (!isCurrent() || !sameLogin()) return
        try {
          sessionId = (await create(intent)).sessionId
          break
        } catch (error) {
          if (!(error instanceof DirectorCameraError)) throw error
          if (error.code === 'RESOURCE_UNAVAILABLE' || error.code === 'SESSION_EXPIRED') {
            forget(intent)
            return
          }
          if (error.code !== 'SESSION_UNAVAILABLE') throw error
        }
      }
      if (!sessionId) throw new DirectorCameraError('旧连接正在回收，请稍后重试', 'SESSION_RECOVERING')
      // If close fails, the next attempt can close by ID even if the scene is gone.
      writeIntent(key, { ...intent, sessionId })
    }
    if (!sameLogin()) return
    await closeRoom(sessionId)
    forget(intent)
  }

  return {
    open(scope: DirectorScope, sceneId: string, cameraId: string, isCurrent: () => boolean, prepare: () => Promise<void>) {
      return serialize(key, async () => {
        if (!isCurrent() || !sameLogin()) return null
        const previous = readIntent(key)
        if (previous) await release(previous, isCurrent)
        if (!isCurrent() || !sameLogin()) return null
        // An older client persisted only its request ID in this tab. Replaying
        // that exact request locates its room if the server retained it.
        const legacyKey = `aid-vcam-create:${scope.projectId}:${sceneId}:${cameraId}`
        let legacy: string | null = null
        try { legacy = sessionStorage.getItem(legacyKey) } catch {}
        if (legacy && /^[a-zA-Z0-9_-]{1,64}$/.test(legacy) && previous?.requestId !== legacy) {
          const legacyIntent = { scope, sceneId, cameraId, requestId: legacy }
          writeIntent(key, legacyIntent)
          await release(legacyIntent, isCurrent)
        }
        try { sessionStorage.removeItem(legacyKey) } catch {}
        if (!isCurrent() || !sameLogin()) return null
        await prepare()
        if (!isCurrent() || !sameLogin()) return null
        const intent = { scope, sceneId, cameraId, requestId: createRandomUuid() }
        writeIntent(key, intent)
        let room: DirectorCameraRoom
        try { room = await create(intent) }
        catch (error) {
          // An HTTP client error is a definitive rejection. Keep uncertain
          // requests so the next tab can replay and release a committed room.
          if (error instanceof DirectorCameraError && error.status && error.status >= 400 && error.status < 500
            && error.code !== 'AUTH_CHANGED') forget(intent)
          throw error
        }
        const opened = { ...intent, sessionId: room.sessionId }
        writeIntent(key, opened)
        if (isCurrent() && sameLogin()) return room
        if (sameLogin()) await release(opened)
        return null
      })
    },
    close(sessionId: string) {
      // Start immediately on pagehide. The endpoint is idempotent, and a late
      // cleanup can only remove the journal entry for its own session.
      if (!sameLogin()) return Promise.resolve()
      return closeRoom(sessionId).then(() => {
        const intent = readIntent(key)
        if (intent?.sessionId === sessionId) forget(intent)
      })
    }
  }
}
