'use client'

import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEventHandler, type ReactNode } from 'react'
import { Button, ConfigProvider } from 'antd'
import { ArrowsAltOutlined, CloseOutlined, CompressOutlined, ScissorOutlined, VerticalAlignMiddleOutlined } from '@ant-design/icons'
import { closeSmartEditEditor, requestCloseSmartEditEditor, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { useStudioUiStore } from '@/stores/studioUi'
import { STUDIO_PANEL_MOTION_MS } from '@/hooks/studio/useStudioPanelPresence'

export function smartEditDockBounds(viewportHeight: number) {
  const max = Math.max(160, viewportHeight - 120)
  return { min: Math.min(260, max), max }
}

/** One native dialog owns the top layer, focus, popup containers and both editor shells. */
export function StudioSmartEditShell({ title, preview, children, onKeyDown }: { title: string; preview: ReactNode; children: ReactNode; onKeyDown?: KeyboardEventHandler<HTMLElement> }) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const drag = useRef<{ pointerId: number; y: number; height: number } | null>(null)
  const chrome = useStudioSmartEdit((state) => state.chrome)
  const closing = useStudioSmartEdit((state) => state.closing)
  const session = useStudioSmartEdit((state) => state.session)
  const notice = useStudioSmartEdit((state) => state.notice)
  const [height, setHeight] = useState(() => Math.round(window.innerHeight / 2))
  const [viewportHeight, setViewportHeight] = useState(() => window.innerHeight)
  const bounds = smartEditDockBounds(viewportHeight)
  const dockHeight = Math.max(bounds.min, Math.min(bounds.max, height))
  const resize = (value: number) => setHeight(Math.max(bounds.min, Math.min(bounds.max, value)))

  useLayoutEffect(() => {
    useStudioSmartEdit.setState({ dockHeight: chrome === 'docked' ? dockHeight : 0 })
  }, [chrome, dockHeight])

  useEffect(() => {
    const update = () => setViewportHeight(window.innerHeight)
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])
  useEffect(() => {
    const { nodeId, scopeKey } = useStudioSmartEdit.getState()
    return () => queueMicrotask(() => {
      if (nodeId && useStudioUiStore.getState().scopeKey === scopeKey && !useStudioSmartEdit.getState().nodeId) {
        document.querySelector<HTMLButtonElement>(`[data-smart-edit-open="${CSS.escape(nodeId)}"]`)?.focus({ preventScroll: true })
      }
    })
  }, [])
  useLayoutEffect(() => {
    const dialog = dialogRef.current!
    // Switching modal/non-modal requires closing first; the component and timeline stay mounted.
    dialog.close()
    if (chrome === 'expanded') dialog.showModal()
    else dialog.show()
    return () => dialog.close()
  }, [chrome])
  useEffect(() => {
    if (!closing) return
    const timer = setTimeout(() => {
      if (useStudioSmartEdit.getState().session === session && useStudioSmartEdit.getState().closing) closeSmartEditEditor()
    }, window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : STUDIO_PANEL_MOTION_MS)
    return () => clearTimeout(timer)
  }, [closing, session])
  return <dialog ref={dialogRef} aria-label="剪辑成片精修台" aria-modal={chrome === 'expanded'}
    className={`studio-smart-edit-editor is-${chrome}${closing ? ' is-closing' : ''}`}
    style={{ '--smart-edit-dock-height': `${dockHeight}px` } as CSSProperties}
    onCancel={(event) => { event.preventDefault(); requestCloseSmartEditEditor() }}
    onPointerDown={(event) => event.stopPropagation()}
    onKeyDown={(event) => { event.stopPropagation(); if (event.key === 'Escape') { event.preventDefault(); requestCloseSmartEditEditor() } else onKeyDown?.(event) }}>
    <ConfigProvider getPopupContainer={() => dialogRef.current!}>
      <header className="studio-smart-edit-editor__header"
        onPointerDown={(event) => {
          if (chrome !== 'docked' || event.button !== 0 || (event.target as HTMLElement).closest('button,input')) return
          event.preventDefault()
          event.currentTarget.setPointerCapture(event.pointerId)
          drag.current = { pointerId: event.pointerId, y: event.clientY, height: dockHeight }
        }}
        onPointerMove={(event) => { if (drag.current?.pointerId === event.pointerId) resize(drag.current.height + drag.current.y - event.clientY) }}
        onPointerUp={(event) => { drag.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId) }}
        onPointerCancel={() => { drag.current = null }} onLostPointerCapture={() => { drag.current = null }}>
        <div className="studio-smart-edit-editor__identity"><ScissorOutlined /><strong>剪辑成片</strong><span>{title}</span></div>
        {chrome === 'docked' ? <div className="studio-smart-edit-editor__resize">
          <span role="separator" aria-label="调整剪辑栏高度" aria-orientation="horizontal" aria-valuemin={bounds.min} aria-valuemax={bounds.max} aria-valuenow={dockHeight} tabIndex={0}
            onKeyDown={(event) => {
              const next = event.key === 'ArrowUp' ? dockHeight + 24 : event.key === 'ArrowDown' ? dockHeight - 24 : event.key === 'Home' ? bounds.min : event.key === 'End' ? bounds.max : null
              if (next !== null) { event.preventDefault(); resize(next) }
            }}><VerticalAlignMiddleOutlined /><span>拖动调整高度</span></span>
          <Button size="small" aria-label="减小剪辑栏高度" disabled={dockHeight <= bounds.min} onClick={() => resize(dockHeight - 48)}>−</Button>
          <Button size="small" aria-label="增大剪辑栏高度" disabled={dockHeight >= bounds.max} onClick={() => resize(dockHeight + 48)}>＋</Button>
        </div> : null}
        <div className="studio-smart-edit-editor__group">
          <Button size="small" aria-label={chrome === 'expanded' ? '缩小到画布' : '放大精修'} icon={chrome === 'expanded' ? <CompressOutlined /> : <ArrowsAltOutlined />}
            onClick={() => useStudioSmartEdit.setState({ chrome: chrome === 'expanded' ? 'docked' : 'expanded', buffering: true })}>{chrome === 'expanded' ? '缩小' : '全屏'}</Button>
          <Button size="small" aria-label="关闭精修台" icon={<CloseOutlined />} onClick={requestCloseSmartEditEditor} />
        </div>
      </header>
      {notice ? <div className="studio-smart-edit-editor__feedback" role={notice.type === 'error' || notice.type === 'warning' ? 'alert' : 'status'}><span>{notice.text}</span><Button size="small" aria-label="关闭剪辑提示" icon={<CloseOutlined />} onClick={() => useStudioSmartEdit.setState({ notice: null })} /></div> : null}
      {chrome === 'expanded' ? <div className="studio-smart-edit-editor__screen">{preview}</div> : null}
      {children}
    </ConfigProvider>
  </dialog>
}
