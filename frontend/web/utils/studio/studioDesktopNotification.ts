import type { UserGenerationKind } from '@/types/generation-api'

export type StudioDesktopNotificationPermission = NotificationPermission | 'unsupported'

export type StudioDesktopNotificationPageState = {
  permission: StudioDesktopNotificationPermission
  visible: boolean
  focused: boolean
}

export type StudioGenerationNoticeTask = {
  kind: UserGenerationKind | 'director_space' | 'previz_scene'
  nodeId?: string
  nodeTitle?: string
  prompt?: string
  projectName?: string
  operation?: 'generate' | 'upscale'
}

export const STUDIO_DESKTOP_NOTIFICATION_ICON_PATH = '/media/studio/notification-icon.png'

export function shouldShowStudioDesktopNotification(state: StudioDesktopNotificationPageState): boolean {
  return state.permission === 'granted' && (!state.visible || !state.focused)
}

function clipNoticeText(value: string, max: number): string {
  const text = value.replace(/\s+/g, ' ').trim()
  if (!text) return ''
  if (text.length <= max) return text
  return `${text.slice(0, Math.max(1, max - 1))}…`
}

function generationKindLabel(kind: StudioGenerationNoticeTask['kind']): string {
  if (kind === 'director_space') return '空间'
  if (kind === 'previz_scene') return '三维场景'
  return kind === 'text' ? '文本' : kind === 'video' ? '视频' : kind === 'audio' ? '音频' : '图片'
}

export function studioGenerationCompleteNotice(task: StudioGenerationNoticeTask): {
  title: string
  body: string
  toastTitle: string
} {
  const label = generationKindLabel(task.kind)
  const toastTitle = task.operation === 'upscale' ? '高清处理完成' : `${label}生成完成`
  const spatial = task.kind === 'director_space' || task.kind === 'previz_scene'
  const project = clipNoticeText(task.projectName || '', 20) || (task.kind === 'director_space' ? '3D预演' : task.kind === 'previz_scene' ? '3D预演' : '自由画布')
  const nodeTitle = clipNoticeText(task.nodeTitle || '', 24)
  const named = nodeTitle ? `「${nodeTitle}」` : ''
  const prompt = clipNoticeText(task.prompt || '', 80)
  const headline = `${project} · ${label}${spatial ? '' : '节点'}${named}`
  return {
    title: `视觉AID · ${toastTitle}`,
    body: prompt ? `${headline}\n${prompt}` : `${headline}已完成`,
    toastTitle
  }
}

export function isNotifiableStudioGenerationSuccess(
  kind: UserGenerationKind,
  latest?: { textContent?: string | null } | null
): boolean {
  if (!latest) return false
  if (kind === 'text' && !String(latest.textContent ?? '').trim()) return false
  return true
}

export function studioDesktopNotificationIconUrl(origin?: string): string {
  const base = origin || (typeof location === 'undefined' ? '' : location.origin)
  if (!base) return STUDIO_DESKTOP_NOTIFICATION_ICON_PATH
  return new URL(STUDIO_DESKTOP_NOTIFICATION_ICON_PATH, base.endsWith('/') ? base : `${base}/`).href
}

function readPermission(): StudioDesktopNotificationPermission {
  if (typeof Notification === 'undefined') return 'unsupported'
  return Notification.permission
}

function readPageState(): Pick<StudioDesktopNotificationPageState, 'visible' | 'focused'> {
  if (typeof document === 'undefined') return { visible: true, focused: true }
  return {
    visible: document.visibilityState === 'visible',
    focused: typeof document.hasFocus === 'function' ? document.hasFocus() : true
  }
}

export async function requestStudioDesktopNotificationPermission(): Promise<StudioDesktopNotificationPermission> {
  if (typeof Notification === 'undefined' || typeof Notification.requestPermission !== 'function') {
    return 'unsupported'
  }
  if (Notification.permission !== 'default') return Notification.permission
  try {
    const next = await Notification.requestPermission()
    return next || readPermission()
  } catch {
    return readPermission()
  }
}

export function notifyStudioGenerationComplete(
  task: StudioGenerationNoticeTask,
  page?: Partial<Pick<StudioDesktopNotificationPageState, 'visible' | 'focused'>> & { origin?: string }
): boolean {
  const state: StudioDesktopNotificationPageState = {
    permission: readPermission(),
    ...readPageState(),
    ...(page?.visible !== undefined ? { visible: page.visible } : {}),
    ...(page?.focused !== undefined ? { focused: page.focused } : {})
  }
  if (!shouldShowStudioDesktopNotification(state)) return false
  const notice = studioGenerationCompleteNotice(task)
  try {
    const notification = new Notification(notice.title, {
      body: notice.body,
      icon: studioDesktopNotificationIconUrl(page?.origin),
      tag: task.nodeId ? `studio-generation-${task.nodeId}` : undefined,
      silent: false
    })
    notification.onclick = () => {
      try {
        window.focus()
      } catch {
        /* ignore */
      }
      notification.close()
    }
    return true
  } catch {
    return false
  }
}
