'use client'

import './publication-feedback.css'

/** Keep submission failures visible beside the action, outside a scrolling form body. */
export function PublicationFormFeedback({ error, pending }: { error: string; pending: boolean }) {
  return (
    <div className="publication-form-feedback">
      {error ? <p role="alert">{error}</p> : pending ? (
        <p role="status">上次提交结果尚未确认，请重试核实原请求，避免重复发布。</p>
      ) : null}
    </div>
  )
}
