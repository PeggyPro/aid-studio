'use client'

import { message } from 'antd'
import { useCallback, useRef, useState } from 'react'
import type { CanvasProjectDetail } from '@/types/canvas-api'
import { canvasProjectCreate } from '@/utils/business/canvas'
import { canvasOperationId } from '@/utils/canvas/canvasId'
import { canvasRetryLedger } from '@/utils/canvas/canvasRetryLedger'
import { canvasProjectHref, reserveCanvasProjectPage } from '@/utils/canvas/canvasProjectNavigation'
import { clearCanvasHomeAssistantFiles, prepareCanvasHomeAssistantLaunch, type CanvasHomeAssistantInput, type CanvasHomeAssistantLaunch } from '@/utils/canvas/canvasHomeAssistantDraft'

export const DEFAULT_CANVAS_PROJECT_NAME = '未命名项目'
export const DEFAULT_CANVAS_PROJECT_DESC = '无'

export { canvasProjectHref }

let createRequest: Promise<CanvasProjectDetail> | null = null
const createFingerprint = JSON.stringify([DEFAULT_CANVAS_PROJECT_NAME, DEFAULT_CANVAS_PROJECT_DESC])

/**
 * 所有默认创建入口共用一次在途请求和 operationId，避免快速重复点击或响应丢失时创建重复项目。
 */
function createDefaultCanvasProject(): Promise<CanvasProjectDetail> {
  if (createRequest) return createRequest
  const operationId = canvasRetryLedger.getOrCreate('project:create:default', createFingerprint, () => canvasOperationId('create'))
  const request = canvasProjectCreate({
    projectName: DEFAULT_CANVAS_PROJECT_NAME,
    projectDesc: DEFAULT_CANVAS_PROJECT_DESC,
    operationId
  })
  const activeRequest = request
    .then((project) => {
      if (!project?.projectId) throw new Error('未收到画布项目编号，请重试')
      canvasRetryLedger.clear('project:create:default', createFingerprint)
      return project
    })
    .finally(() => {
      if (createRequest === activeRequest) createRequest = null
    })
  createRequest = activeRequest
  return activeRequest
}

export function useCreateDefaultCanvasProject() {
  const activeRef = useRef(false)
  const draftProjectRef = useRef<CanvasProjectDetail | null>(null)
  const draftProjectTokenRef = useRef<string | null>(null)
  const [creating, setCreating] = useState(false)

  const createAndEnter = useCallback(async (assistantDraft?: string | CanvasHomeAssistantInput) => {
    if (activeRef.current) return
    activeRef.current = true
    setCreating(true)
    const mainToken = window.localStorage.getItem('token')
    if (draftProjectTokenRef.current !== mainToken) draftProjectRef.current = null
    const pendingPage = reserveCanvasProjectPage()
    let launch: CanvasHomeAssistantLaunch | undefined
    try {
      const project = assistantDraft && draftProjectRef.current || await createDefaultCanvasProject()
      if (assistantDraft && window.localStorage.getItem('token') !== mainToken) {
        pendingPage.cancel()
        message.warning('登录状态已变化，请重新进入首页后创作')
        return
      }
      if (assistantDraft) {
        draftProjectRef.current = project
        draftProjectTokenRef.current = mainToken
      }
      const projectId = String(project.projectId)
      if (assistantDraft && typeof assistantDraft !== 'string') launch = await prepareCanvasHomeAssistantLaunch(projectId, assistantDraft)
      pendingPage.assign(projectId, launch || (typeof assistantDraft === 'string' ? assistantDraft : undefined))
      draftProjectRef.current = null
      return true
    } catch (error: unknown) {
      pendingPage.cancel()
      if (launch) void clearCanvasHomeAssistantFiles(launch).catch(() => {})
      const detail = error as { msg?: string; message?: string }
      message.error(assistantDraft && draftProjectRef.current ? '画布已创建，草稿带入失败。请重试，草稿仍保留。' : detail?.msg || detail?.message || '创建自由画布失败')
    } finally {
      activeRef.current = false
      setCreating(false)
    }
  }, [])

  return { createAndEnter, creating }
}
