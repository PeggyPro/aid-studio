'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioTextBackgroundId } from '@/types/studio'
import {
  STUDIO_TEXT_EDITOR_COMMAND_EVENT,
  STUDIO_TEXT_EDITOR_FOCUS_EVENT,
  consumePendingStudioTextEditorFocus,
  type StudioTextEditorCommand
} from '@/utils/studio/studioTextEditorEvents'

const BACKGROUND_CLASS: Record<StudioTextBackgroundId, string> = {
  neutral: 'bg-[var(--studio-text-background-neutral)]',
  paper: 'bg-[var(--studio-text-background-paper)]',
  mist: 'bg-[var(--studio-text-background-mist)]',
  lavender: 'bg-[var(--studio-text-background-lavender)]',
  sage: 'bg-[var(--studio-text-background-sage)]'
}

function normalizedEditorText(element: HTMLElement): string {
  return (element.innerText ?? element.textContent ?? '').replace(/\u00a0/g, ' ').replace(/\n{3,}/g, '\n\n').trim()
}

export function StudioTextNodeEditor({
  nodeId,
  text,
  background = 'neutral',
  selected,
  onExpand
}: {
  nodeId: string
  text: string
  background?: StudioTextBackgroundId
  selected: boolean
  onExpand: () => void
}) {
  const editorRef = useRef<HTMLDivElement>(null)
  const draftRef = useRef(text)
  const timerRef = useRef<number | null>(null)
  const transactionOpenRef = useRef(false)
  const selectionRangeRef = useRef<Range | null>(null)
  const [editing, setEditing] = useState(false)

  const enterEditing = useCallback(() => {
    setEditing(true)
    window.dispatchEvent(new CustomEvent('studio:text-editing-change', { detail: { nodeId } }))
    window.requestAnimationFrame(() => editorRef.current?.focus())
  }, [nodeId])

  const persist = useCallback(() => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    timerRef.current = null
    const value = draftRef.current
    const store = useStudioUiStore.getState()
    const current = store.nodes.find((node) => node.id === nodeId)
    if (!current || current.data.documentContent === value) return
    store.updateNodeData(nodeId, {
      documentContent: value,
      mediaKind: 'text',
      status: value ? 'success' : 'empty'
    })
  }, [nodeId])

  useEffect(() => {
    const editor = editorRef.current
    if (!editor || document.activeElement === editor || normalizedEditorText(editor) === text) return
    draftRef.current = text
    editor.textContent = text
  }, [nodeId, text])

  useEffect(() => {
    const rememberSelection = () => {
      const editor = editorRef.current
      const selection = window.getSelection()
      const range = selection?.rangeCount ? selection.getRangeAt(0) : null
      if (!editor || !range || !editor.contains(range.commonAncestorContainer)) return
      selectionRangeRef.current = range.cloneRange()
    }
    document.addEventListener('selectionchange', rememberSelection)
    return () => document.removeEventListener('selectionchange', rememberSelection)
  }, [])

  useEffect(() => {
    const focusEditor = (event: Event) => {
      const targetId = String((event as CustomEvent<{ nodeId?: string }>).detail?.nodeId ?? '')
      if (targetId !== nodeId) return
      consumePendingStudioTextEditorFocus(nodeId)
      enterEditing()
    }
    const runCommand = (event: Event) => {
      const detail = (event as CustomEvent<{ nodeId?: string; command?: StudioTextEditorCommand }>).detail
      if (detail?.nodeId !== nodeId || !detail.command) return
      const editor = editorRef.current
      if (!editor) return
      if (detail.command.type === 'copy') {
        void navigator.clipboard.writeText(draftRef.current).then(
          () => message.success('文本已复制'),
          () => message.error('复制失败，请检查浏览器剪贴板权限')
        )
        return
      }
      if (detail.command.type === 'expand') {
        persist()
        onExpand()
        return
      }
      editor.focus()
      const selection = window.getSelection()
      if (selection && selectionRangeRef.current) {
        selection.removeAllRanges()
        selection.addRange(selectionRangeRef.current)
      }
      if (detail.command.type === 'format-block') document.execCommand('formatBlock', false, detail.command.value)
      if (detail.command.type === 'bold') document.execCommand('bold')
      if (detail.command.type === 'italic') document.execCommand('italic')
      if (detail.command.type === 'ordered-list') document.execCommand('insertOrderedList')
      if (detail.command.type === 'unordered-list') document.execCommand('insertUnorderedList')
      if (detail.command.type === 'divider') document.execCommand('insertHorizontalRule')
      draftRef.current = normalizedEditorText(editor)
      if (selection?.rangeCount) selectionRangeRef.current = selection.getRangeAt(0).cloneRange()
      persist()
    }
    window.addEventListener(STUDIO_TEXT_EDITOR_FOCUS_EVENT, focusEditor)
    window.addEventListener(STUDIO_TEXT_EDITOR_COMMAND_EVENT, runCommand)
    if (consumePendingStudioTextEditorFocus(nodeId)) enterEditing()
    return () => {
      window.removeEventListener(STUDIO_TEXT_EDITOR_FOCUS_EVENT, focusEditor)
      window.removeEventListener(STUDIO_TEXT_EDITOR_COMMAND_EVENT, runCommand)
    }
  }, [enterEditing, nodeId, onExpand, persist])

  useEffect(() => {
    if (selected) return
    const editor = editorRef.current
    if (document.activeElement === editor) editor?.blur()
    const frame = window.requestAnimationFrame(() => setEditing(false))
    window.dispatchEvent(new CustomEvent('studio:text-editing-change', { detail: { nodeId: null } }))
    return () => window.cancelAnimationFrame(frame)
  }, [selected])

  useEffect(() => () => {
    persist()
    if (transactionOpenRef.current) useStudioUiStore.getState().commitGraphTransaction()
  }, [persist])

  return (
    <div
      className={`studio-text-node-editor h-full min-h-0 w-full cursor-grab overflow-hidden rounded-lg active:cursor-grabbing ${BACKGROUND_CLASS[background]}`}
      onDoubleClick={enterEditing}
    >
      <div
        ref={editorRef}
        role="textbox"
        aria-label="文本正文"
        aria-multiline="true"
        contentEditable={editing}
        suppressContentEditableWarning
        className={`studio-text-node-editor__content h-full min-h-0 w-full overflow-y-auto whitespace-pre-wrap break-words rounded-md text-sm leading-6 text-[var(--studio-ui-text)] outline-none empty:before:pointer-events-none empty:before:text-[var(--studio-ui-muted)] empty:before:content-['输入正文…'] [&_h1]:my-1 [&_h1]:text-xl [&_h1]:font-semibold [&_h2]:my-1 [&_h2]:text-lg [&_h2]:font-semibold [&_h3]:my-1 [&_h3]:text-base [&_h3]:font-semibold [&_hr]:my-2 [&_hr]:border-0 [&_hr]:border-t [&_hr]:border-[var(--studio-ui-border)] [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:my-1 [&_ul]:list-disc [&_ul]:pl-5${editing ? ' nodrag nowheel is-editing' : ''}`}
        onFocus={() => {
          if (transactionOpenRef.current) return
          useStudioUiStore.getState().beginGraphTransaction()
          transactionOpenRef.current = true
        }}
        onInput={(event) => {
          draftRef.current = normalizedEditorText(event.currentTarget)
          if (timerRef.current !== null) window.clearTimeout(timerRef.current)
          timerRef.current = window.setTimeout(persist, 350)
        }}
        onBlur={() => {
          persist()
          setEditing(false)
          window.dispatchEvent(new CustomEvent('studio:text-editing-change', { detail: { nodeId: null } }))
          if (transactionOpenRef.current) {
            useStudioUiStore.getState().commitGraphTransaction()
            transactionOpenRef.current = false
          }
        }}
        onPointerDown={(event) => {
          if (editing) event.stopPropagation()
        }}
      />
    </div>
  )
}
