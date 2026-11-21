'use client'

import type { ReactNode } from 'react'
import { ExclamationCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import './assistant-confirmation.css'

/** Shared in-thread decision surface for every assistant host. Authorization stays with the caller. */
export function AssistantConfirmationSection({ presentation, label, title = label, role, children, actions, approvalId, modelCode, toolCode, detail }: {
  presentation?: 'canvas'
  label: string
  title?: string
  role?: 'status' | 'alert'
  children?: ReactNode
  actions?: ReactNode
  approvalId?: string
  modelCode?: string
  toolCode?: string
  detail?: ReactNode
  fallbackClassName?: string
}) {
  return <section className={`assistant-decision ${presentation === 'canvas' ? 'canvas-assistant-confirmation' : 'assistant-generation-confirmation'}`} aria-label={label} role={role}
    data-assistant-operation-approval-id={approvalId} data-assistant-operation-model-code={modelCode} data-assistant-operation-tool-code={toolCode}>
    <div className="assistant-decision__heading">
      <span className="assistant-decision__icon" aria-hidden>{role === 'alert' ? <ExclamationCircleOutlined /> : <SafetyCertificateOutlined />}</span>
      <h3>{title}</h3>
      {detail ? <div className="assistant-decision__detail">{detail}</div> : null}
    </div>
    {children ? <div className="assistant-decision__body">{children}</div> : null}
    {actions ? <div className="assistant-decision__actions assistant-generation-confirmation-actions">{actions}</div> : null}
  </section>
}
