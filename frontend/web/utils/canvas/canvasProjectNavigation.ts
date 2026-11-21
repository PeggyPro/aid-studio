import { writeCanvasHomeAssistantDraft, writeCanvasHomeAssistantLaunch, type CanvasHomeAssistantLaunch } from './canvasHomeAssistantDraft'

export const CANVAS_PROJECT_PAGE_TARGET = '_blank'
export const CANVAS_PROJECT_PAGE_REL = 'noopener noreferrer'

export function canvasProjectHref(projectId: string): string {
  const query = new URLSearchParams({ studioMode: 'free', projectId: String(projectId) })
  return `/create/studio/?${query.toString()}`
}

export function canvasProjectPageUrl(projectId: string, origin?: string): string {
  const path = canvasProjectHref(projectId)
  const base = origin ?? (typeof window === 'undefined' ? '' : window.location.origin)
  return base ? new URL(path, base).toString() : path
}

/** 已有项目与异步创建共用同一新标签流程，避免误判 noopener 的空句柄。 */
export function openCanvasProjectPage(projectId: string): void {
  reserveCanvasProjectPage().assign(projectId)
}

/**
 * 创建等异步动作必须在用户手势里先占住标签，await 后再写入地址。
 * 不能在 await 之后再 window.open，否则会被浏览器当弹窗拦截。
 */
export function reserveCanvasProjectPage(): {
  assign: (projectId: string, assistantDraft?: string | CanvasHomeAssistantLaunch) => void
  cancel: () => void
} {
  if (typeof window === 'undefined') {
    return { assign() {}, cancel() {} }
  }
  const tab = window.open('/canvas-opening.html', CANVAS_PROJECT_PAGE_TARGET)
  if (tab) {
    try { tab.opener = null } catch { /* ignore locked opener */ }
  }
  return {
    assign(projectId, assistantDraft) {
      const href = canvasProjectPageUrl(projectId)
      if (assistantDraft) {
        const storage = tab && !tab.closed ? tab.sessionStorage : window.sessionStorage
        if (typeof assistantDraft === 'string') writeCanvasHomeAssistantDraft(storage, projectId, assistantDraft)
        else writeCanvasHomeAssistantLaunch(storage, assistantDraft)
      }
      if (tab && !tab.closed) {
        tab.location.replace(href)
        return
      }
      window.location.assign(canvasProjectHref(projectId))
    },
    cancel() {
      if (tab && !tab.closed) tab.close()
    }
  }
}
