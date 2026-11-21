'use client'

import { FormOutlined } from '@ant-design/icons'
import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

export function StudioInlineNodeTitle({
  title,
  displayTitle = title,
  editable = false,
  disabledReason,
  className = '',
  onActivate,
  onCommit
}: {
  title: string
  displayTitle?: string
  editable?: boolean
  disabledReason?: string
  className?: string
  onActivate?: () => void
  onCommit?: (title: string) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(title)

  useEffect(() => {
    if (!editing) return
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [editing])

  const startEditing = (event: MouseEvent<HTMLElement>) => {
    if (!editable) return
    event.preventDefault()
    event.stopPropagation()
    setDraft(title)
    setEditing(true)
  }

  const commit = () => {
    if (!editing) return
    const next = draft.trim().slice(0, 100)
    setEditing(false)
    setDraft(next || title)
    if (editable && next && next !== title) onCommit?.(next)
  }

  return (
    <span className={`studio-inline-node-title${editing ? ' is-editing' : ''}${className ? ` ${className}` : ''}`}>
      {editing && editable ? (
        <input
          ref={inputRef}
          className="studio-inline-node-title__input nodrag nowheel"
          value={draft}
          maxLength={100}
          aria-label="节点标题"
          onPointerDown={(event) => event.stopPropagation()}
          onClick={(event) => event.stopPropagation()}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            event.stopPropagation()
            if (event.key === 'Enter') event.currentTarget.blur()
            if (event.key === 'Escape') {
              setDraft(title)
              setEditing(false)
            }
          }}
        />
      ) : (
        <>
          {displayTitle ? (
            <StudioCanvasTooltip title={disabledReason ?? (editable ? `${title}（双击修改标题）` : title)}>
            <button
              type="button"
              className="studio-inline-node-title__display nodrag"
              aria-label={onActivate ? `打开${title}操作栏` : title}
              onClick={(event) => {
                event.stopPropagation()
                if (onActivate) onActivate()
                else if (editable) startEditing(event)
              }}
              onDoubleClick={startEditing}
            >
              <strong>{displayTitle}</strong>
            </button>
            </StudioCanvasTooltip>
          ) : null}
          {editable ? (
            <StudioCanvasTooltip title="修改节点标题">
            <button
              type="button"
              className="studio-inline-node-title__edit nodrag"
              aria-label={`修改节点标题：${title}`}
              onClick={startEditing}
              onPointerDown={(event) => event.stopPropagation()}
            >
              <FormOutlined />
            </button>
            </StudioCanvasTooltip>
          ) : null}
        </>
      )}
    </span>
  )
}
