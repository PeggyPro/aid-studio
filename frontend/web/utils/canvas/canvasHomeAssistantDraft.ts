import type { AssistantMode, AssistantModelSelection } from '@/types/assistant-api'
import type { CanvasAssistantAttachment } from './canvasAssistantAttachment'
import { assistantCommandId } from '@/utils/assistant/assistantRequestCoordinator'

const KEY = 'aid:home-canvas-assistant-draft:v1'
const LAUNCH_KEY = 'aid:home-canvas-assistant-launch:v1'
const MAX_AGE = 30 * 60 * 1000

export interface CanvasHomeAssistantChoices {
  moduleCode: 'UNIVERSAL' | 'SCREENWRITER'
  mode: AssistantMode
  executionPreference: 'MANUAL' | 'AUTO'
  selectedSkillCode: string
  selectedModels: AssistantModelSelection[]
}

export interface CanvasHomeAssistantInput extends CanvasHomeAssistantChoices {
  prompt: string
  attachments: CanvasAssistantAttachment[]
}

export interface CanvasHomeAssistantLaunch extends Omit<CanvasHomeAssistantInput, 'attachments'> {
  id: string
  projectId: string
  accountId: string
  createdAt: number
  attachmentCount: number
  phase: 'pending' | 'preparing' | 'submitted'
}

interface LaunchFiles {
  id: string
  accountId: string
  projectId: string
  createdAt: number
  attachments: CanvasAssistantAttachment[]
}

function accountId() {
  try {
    const token = localStorage.getItem('token') || ''
    // Non-secret session fingerprint prevents a draft crossing a logout/login boundary.
    if (!token) return ''
    let fingerprint = 2166136261
    let secondFingerprint = 5381
    for (const char of token) {
      fingerprint = Math.imul(fingerprint ^ char.charCodeAt(0), 16777619)
      secondFingerprint = Math.imul(secondFingerprint, 33) ^ char.charCodeAt(0)
    }
    // Profile hydration must not invalidate the same login's draft.
    return `${fingerprint >>> 0}:${secondFingerprint >>> 0}`
  }
  catch { return '' }
}

/** Only the newly reserved tab receives the draft; no prompt is put in a URL or shared storage. */
export function writeCanvasHomeAssistantDraft(storage: Storage, projectId: string, prompt: string) {
  const identity = accountId()
  if (!identity) throw new Error('登录状态已失效，草稿仍保留在首页')
  storage.setItem(KEY, JSON.stringify({ projectId, prompt, accountId: identity, createdAt: Date.now() }))
}

export function readCanvasHomeAssistantDraft(projectId: string, consume = false): string {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return ''
    const draft = JSON.parse(raw)
    if (typeof draft.createdAt !== 'number' || Date.now() - draft.createdAt > MAX_AGE || draft.createdAt > Date.now()
      || !draft.accountId || draft.accountId !== accountId()) {
      sessionStorage.removeItem(KEY)
      return ''
    }
    if (draft.projectId !== projectId || typeof draft.prompt !== 'string' || !draft.prompt.trim() || draft.prompt.length > 100_000) return ''
    if (consume) sessionStorage.removeItem(KEY)
    return draft.prompt
  } catch { return '' }
}

async function openLaunchFiles() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('aid-canvas-home-assistant', 1)
    request.onupgradeneeded = () => {
      request.result.createObjectStore('attachments', { keyPath: 'id' }).createIndex('createdAt', 'createdAt')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

async function withLaunchFiles<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openLaunchFiles()
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction('attachments', mode)
      const request = operation(tx.objectStore('attachments'))
      tx.oncomplete = () => resolve(request.result)
      tx.onabort = () => reject(tx.error || request.error || new Error('浏览器未能暂存画布素材'))
    })
  } finally { db.close() }
}

export async function prepareCanvasHomeAssistantLaunch(projectId: string, input: CanvasHomeAssistantInput): Promise<CanvasHomeAssistantLaunch> {
  const identity = accountId()
  if (!identity) throw new Error('登录状态已失效，内容仍保留在首页')
  const { attachments, ...choices } = input
  const launch: CanvasHomeAssistantLaunch = { ...choices, id: assistantCommandId(), projectId, accountId: identity,
    createdAt: Date.now(), attachmentCount: attachments.length, phase: 'pending' }
  if (attachments.length) {
    await withLaunchFiles('readwrite', store => {
      const stale = store.index('createdAt').openCursor(IDBKeyRange.upperBound(Date.now() - MAX_AGE))
      stale.onsuccess = () => {
        const cursor = stale.result
        if (cursor) { cursor.delete(); cursor.continue() }
      }
      return store.put({ id: launch.id, projectId, accountId: identity, createdAt: launch.createdAt, attachments } satisfies LaunchFiles)
    })
  }
  return launch
}

export function writeCanvasHomeAssistantLaunch(storage: Storage, launch: CanvasHomeAssistantLaunch) {
  if (launch.accountId !== accountId()) throw new Error('登录状态已变化，内容仍保留在首页')
  storage.setItem(LAUNCH_KEY, JSON.stringify(launch))
}

export function readCanvasHomeAssistantLaunch(projectId: string): CanvasHomeAssistantLaunch | null {
  try {
    const raw = sessionStorage.getItem(LAUNCH_KEY)
    if (!raw) return null
    const launch: CanvasHomeAssistantLaunch = JSON.parse(raw)
    if (!launch.accountId || launch.accountId !== accountId() || !Number.isFinite(launch.createdAt)
      || launch.createdAt > Date.now() || Date.now() - launch.createdAt > MAX_AGE) {
      sessionStorage.removeItem(LAUNCH_KEY)
      void clearCanvasHomeAssistantFiles(launch).catch(() => {})
      return null
    }
    return launch.projectId === projectId && typeof launch.id === 'string' && typeof launch.prompt === 'string'
      && launch.prompt.trim() && launch.prompt.length <= 100_000 ? launch : null
  } catch { return null }
}

export function advanceCanvasHomeAssistantLaunch(launch: CanvasHomeAssistantLaunch, phase: CanvasHomeAssistantLaunch['phase']): boolean {
  const current = readCanvasHomeAssistantLaunch(launch.projectId)
  if (current?.id !== launch.id || current.phase !== launch.phase) return false
  writeCanvasHomeAssistantLaunch(sessionStorage, { ...current, phase })
  return true
}

export function clearCanvasHomeAssistantLaunch(launch: CanvasHomeAssistantLaunch) {
  if (readCanvasHomeAssistantLaunch(launch.projectId)?.id === launch.id) sessionStorage.removeItem(LAUNCH_KEY)
}

export async function readCanvasHomeAssistantFiles(launch: CanvasHomeAssistantLaunch): Promise<CanvasAssistantAttachment[]> {
  if (!launch.attachmentCount) return []
  const record: LaunchFiles | undefined = await withLaunchFiles('readwrite', store => {
    const request = store.get(launch.id)
    request.onsuccess = () => {
      if (request.result?.accountId === accountId() && request.result?.projectId === launch.projectId) store.delete(launch.id)
    }
    return request
  })
  if (!record || record.accountId !== accountId() || record.projectId !== launch.projectId
    || record.createdAt !== launch.createdAt || record.attachments.length !== launch.attachmentCount) {
    throw new Error('首页素材已过期，请在画布中重新选择')
  }
  return record.attachments
}

export async function clearCanvasHomeAssistantFiles(launch: CanvasHomeAssistantLaunch) {
  if (launch.attachmentCount) await withLaunchFiles('readwrite', store => store.delete(launch.id))
}
