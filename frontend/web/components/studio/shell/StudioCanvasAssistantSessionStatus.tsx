'use client'

import { useEffect } from 'react'
import { Button, message } from 'antd'
import type { AssistantErrorPresentation } from '@/utils/assistant/assistantRequestCoordinator'
import { assistantContextChangeText } from '@/utils/assistant/assistantContextChange'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

/** Deterministic preparation failures stay out of the transcript; the input and session remain. */
export function canvasAssistantCreationNotice(title: string): string {
  const head = title.trim().replace(/[。.!！?？]+$/u, '')
  return head ? `${head}。本次创作准备未完成，输入与会话已保留。` : '本次创作准备未完成，输入与会话已保留。'
}

/** Resolved before the shell renders, so the conversation markup stays an expression. */
export function canvasAssistantSessionFeedback(input: {
  initializationError?: string
  requestError?: string | null
  requestPresentation?: AssistantErrorPresentation | null
  disabledReason?: string
  catalogPresentation?: AssistantErrorPresentation | null
  identityFailed: boolean
  workspaceReady: boolean
}): { error?: string; presentation?: AssistantErrorPresentation; toast: string | null } {
  const error = assistantContextChangeText(input.requestError) ? input.initializationError || input.disabledReason : input.initializationError || input.requestError || input.disabledReason
  let presentation: AssistantErrorPresentation | undefined
  if (input.initializationError) presentation = input.identityFailed || !input.workspaceReady ? undefined : input.catalogPresentation || undefined
  else if (input.requestError) presentation = input.requestPresentation || undefined
  else if (input.disabledReason) presentation = { kind: 'CREATION' }
  const persistentSelectionError = presentation?.code === 'ASSISTANT_SELECTED_MODEL_UNAVAILABLE'
  const toast = error && error !== input.disabledReason && presentation?.kind === 'CREATION' && !persistentSelectionError ? canvasAssistantCreationNotice(error) : null
  return toast ? { toast } : { error, presentation, toast: null }
}

/** Progress never replaces the welcome area or the editor. Recovery is explicit on failure. */
export function StudioCanvasAssistantSessionStatus({ pending, error, errorPresentation, toast, onRetry, retryLabel = '重试连接助手', retryHint = '重新读取登录身份、能力与会话；未确认操作沿用原标识核实' }: {
  pending: boolean; error?: string | null; errorPresentation?: AssistantErrorPresentation | null; toast?: string | null; onRetry?: () => void; retryLabel?: string; retryHint?: string
}) {
  useEffect(() => {
    if (!toast) return
    void message.error(toast)
  }, [toast])
  if (toast || (!pending && !error)) return null
  if (!error) return <div className="canvas-assistant-session-status" role="status">
    <span className="canvas-assistant-session-status__dot" aria-hidden="true" />
    <span className="canvas-assistant-status-shimmer">正在连接助手，可先写下想法</span>
  </div>
  const kind = errorPresentation?.kind || 'UNKNOWN'
  const connection = kind === 'CONNECTION'
  const actionLabel = connection ? retryLabel : kind === 'ACCESS' ? '重新核对身份' : '重新同步助手'
  const actionHint = connection ? retryHint : '重新读取助手状态；未确认操作仍按原标识核实'
  const visibleLabel = connection ? retryLabel === '重新连接画布' ? '重新连接' : '重试连接' : kind === 'ACCESS' ? '核对身份' : '重新同步'
  const description = kind === 'CREATION' ? '本次创作准备未完成，输入与会话已保留。' : kind === 'ACCESS' ? '请确认当前登录身份和访问权限。' : ''
  return <div className="assistant-inline-error canvas-assistant-session-status is-recovery" role="alert">
    <span>{description ? `${error} ${description}` : error}</span>
    {onRetry && kind !== 'CREATION' ? <StudioCanvasTooltip title={actionHint}><Button type="link" size="small" aria-label={actionLabel} onClick={onRetry}>{visibleLabel}</Button></StudioCanvasTooltip> : null}
  </div>
}
