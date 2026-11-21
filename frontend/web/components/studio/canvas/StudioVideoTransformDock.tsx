'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { CheckOutlined, CloseOutlined, DisconnectOutlined, LinkOutlined, LoadingOutlined, RetweetOutlined } from '@ant-design/icons'
import { Tooltip } from 'antd'
import { forwardRef, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioLayerSplitRect, StudioVideoClipRange, StudioVideoClipSession } from '@/types/studio'
import { useStudioVideoTransform } from '@/hooks/studio/useStudioVideoTransform'
import { StudioVideoClipTimeline } from '@/components/studio/composer/StudioVideoClipTimeline'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import { applyCropHandleDelta, type StudioCropHandle } from '@/utils/studio/studioImageAiTools'
import { createStudioVideoClipRange } from '@/utils/studio/studioVideoClip'
import { editVideoRange, videoCropPixels, videoEditorKeyRange } from '@/utils/studio/studioVideoEditing'
import type { StudioVideoClipDockHandle } from './StudioVideoClipDock'
import './studio-video-transform.css'

const HANDLES: StudioCropHandle[] = ['nw', 'n', 'ne', 'w', 'e', 'sw', 's', 'se']
const SHORTCUTS = [
  ['移动选区', '←', '→'], ['精确模式', '按住 Shift'],
  ['扩展/收缩选区', '↑', '↓'], ['快速调整', '⌘/Ctrl + ↑/↓'],
  ['设置入点/出点', 'I', 'O'], ['播放/暂停', 'Space'],
  ['退出', 'Esc'], ['确认剪辑', 'Enter']
]

export const StudioVideoTransformDock = forwardRef<StudioVideoClipDockHandle, {
  node: StudioCanvasNode
  session: StudioVideoClipSession
}>(function StudioVideoTransformDock({ node, session }, ref) {
  const rootRef = useRef<HTMLDivElement>(null)
  const cropSurfaceRef = useRef<HTMLDivElement>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const latestSession = useRef(session)
  latestSession.current = session
  const [metadata, setMetadata] = useState({ width: 0, height: 0, duration: 0 })
  const [playhead, setPlayhead] = useState(0)
  const [helpOpen, setHelpOpen] = useState(false)
  const patch = useStudioUiStore((state) => state.patchVideoClipSession)
  const close = useStudioUiStore((state) => state.closeVideoClipSession)
  const { busy, progress, confirm } = useStudioVideoTransform(node, session)
  const cropMode = session.mode === 'crop'
  const ready = metadata.duration > 0 && metadata.width >= 2 && metadata.height >= 2
  const crop = session.cropRect ?? { x: 0.1, y: 0.1, w: 0.8, h: 0.8 }
  const pixels = videoCropPixels(crop, metadata.width, metadata.height)
  const drag = useRef<{ id: number; handle: StudioCropHandle | 'move'; x: number; y: number; width: number; height: number; rect: StudioLayerSplitRect } | null>(null)
  const lastLayout = useRef<Parameters<StudioVideoClipDockHandle['reposition']> | null>(null)

  const reposition = useCallback<StudioVideoClipDockHandle['reposition']>((selection, viewport, preview) => {
      lastLayout.current = [selection, viewport, preview]
      const root = rootRef.current
      if (root) {
        const position = placeStudioImageEditToolbar(selection, viewport, {
          width: cropMode ? 244 : 960, height: root.getBoundingClientRect().height || 74
        })
        root.style.left = `${position.left}px`
        root.style.top = `${position.top}px`
        root.style.width = `${position.width}px`
      }
      const surface = cropSurfaceRef.current
      if (surface && preview) {
        // 预览可能含 object-fit 留白；框坐标始终映射到真实视频像素区域。
        const ratio = metadata.width / metadata.height || preview.width / preview.height
        const width = Math.min(preview.width, preview.height * ratio)
        const height = width / ratio
        surface.style.left = `${preview.left + (preview.width - width) / 2}px`
        surface.style.top = `${preview.top + (preview.height - height) / 2}px`
        surface.style.width = `${width}px`
        surface.style.height = `${height}px`
      }
  }, [cropMode, metadata.width, metadata.height])
  useImperativeHandle(ref, () => ({ reposition }), [reposition])
  useLayoutEffect(() => {
    if (lastLayout.current) reposition(...lastLayout.current)
  }, [reposition])

  useEffect(() => {
    let frame = 0
    let detach: (() => void) | undefined
    const connect = () => {
      const video = document.querySelector<HTMLVideoElement>(`.react-flow__node[data-id="${CSS.escape(node.id)}"] .studio-video-node-preview video`)
      if (!video) { frame = requestAnimationFrame(connect); return }
      videoRef.current = video
      video.pause()
      const onMeta = () => {
        if (!Number.isFinite(video.duration) || video.duration <= 0) return
        setMetadata({ width: video.videoWidth, height: video.videoHeight, duration: video.duration })
        const current = latestSession.current.range
        if (Math.abs(current.durationSeconds - video.duration) > 0.01) {
          patch({ range: createStudioVideoClipRange(video.duration) })
        }
      }
      const onTime = () => setPlayhead(video.currentTime)
      const onEnded = () => {
        if (latestSession.current.loopSelection) {
          video.currentTime = latestSession.current.range.startSeconds
          void video.play().catch(() => undefined)
        }
      }
      const tick = () => {
        const selection = latestSession.current
        if (!video.paused && !video.seeking && selection.loopSelection
          && (video.currentTime >= selection.range.endSeconds || video.currentTime < selection.range.startSeconds)) {
          video.currentTime = selection.range.startSeconds
        }
        frame = requestAnimationFrame(tick)
      }
      onMeta()
      onTime()
      video.addEventListener('loadedmetadata', onMeta)
      video.addEventListener('timeupdate', onTime)
      video.addEventListener('ended', onEnded)
      frame = requestAnimationFrame(tick)
      detach = () => {
        video.pause()
        video.removeEventListener('loadedmetadata', onMeta)
        video.removeEventListener('timeupdate', onTime)
        video.removeEventListener('ended', onEnded)
      }
    }
    connect()
    return () => { cancelAnimationFrame(frame); detach?.(); videoRef.current = null }
  }, [node.id, node.data.mediaUrl, patch])

  const changeRange = useCallback((range: StudioVideoClipRange) => {
    if (busy) return
    const previous = latestSession.current.range
    patch({ range })
    const video = videoRef.current
    if (video && video.readyState >= 1) {
      const endChanged = range.startSeconds === previous.startSeconds && range.endSeconds !== previous.endSeconds
      video.currentTime = Math.max(0, Math.min(video.duration, endChanged && video.paused ? range.endSeconds - 1 / 30 : range.startSeconds))
    }
  }, [busy, patch])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select, [contenteditable="true"]')) return
      const key = event.key.toLowerCase()
      if (!['escape', 'enter', ' ', 'arrowleft', 'arrowright', 'arrowup', 'arrowdown', 'i', 'o'].includes(key)) return
      if (cropMode && !['escape', 'enter'].includes(key)) return
      event.preventDefault()
      event.stopImmediatePropagation()
      if (busy) return
      if (key === 'escape') { if (helpOpen) setHelpOpen(false); else close(); return }
      if (!ready) return
      if (key === 'enter') { if (!event.repeat) void confirm(); return }
      const video = videoRef.current
      if (key === ' ') {
        if (video && !event.repeat) {
          if (video.paused) void video.play().catch(() => undefined)
          else video.pause()
        }
      } else if (key === 'i' || key === 'o') {
        changeRange(editVideoRange(session.range, key === 'i' ? 'start' : 'end', video?.currentTime ?? 0, Boolean(session.snapToSeconds && !event.shiftKey)))
      } else {
        const step = event.shiftKey ? 1 / 30 : event.ctrlKey || event.metaKey ? 5 : 1
        changeRange(videoEditorKeyRange(session.range, event.key, step, Boolean(session.snapToSeconds && !event.shiftKey)))
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [busy, changeRange, close, confirm, cropMode, helpOpen, ready, session])

  const beginCrop = (event: ReactPointerEvent<HTMLElement>, handle: StudioCropHandle | 'move') => {
    if (busy || !ready || event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()
    const rect = cropSurfaceRef.current?.getBoundingClientRect()
    if (!rect) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { id: event.pointerId, handle, x: event.clientX, y: event.clientY, width: rect.width, height: rect.height, rect: crop }
  }
  const moveCrop = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = drag.current
    if (!active || active.id !== event.pointerId) return
    const dx = (event.clientX - active.x) / active.width
    const dy = (event.clientY - active.y) / active.height
    const rect = active.rect
    patch({ cropRect: active.handle === 'move'
      ? { ...rect, x: Math.max(0, Math.min(1 - rect.w, rect.x + dx)), y: Math.max(0, Math.min(1 - rect.h, rect.y + dy)) }
      : applyCropHandleDelta(rect, active.handle, dx, dy, null) })
  }
  const toggleLoop = () => {
    patch({ loopSelection: !session.loopSelection })
    const video = videoRef.current
    if (!session.loopSelection && video) {
      video.currentTime = session.range.startSeconds
      void video.play().catch(() => undefined)
    }
  }
  const help = <div className="studio-video-shortcuts">
    <div className="studio-video-shortcuts__heading"><strong>快捷键</strong><button aria-label="关闭快捷键" onClick={() => setHelpOpen(false)}><CloseOutlined /></button></div>
    <div className="studio-video-shortcuts__grid">{SHORTCUTS.map(([label, ...keys]) => <div key={label}><span>{label}</span><span>{keys.map((key) => <kbd key={key}>{key}</kbd>)}</span></div>)}</div>
  </div>

  return <>
    {cropMode ? <div ref={cropSurfaceRef} className="studio-video-crop-surface nodrag nopan" style={{ left: -9999 }}
      onPointerDown={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}>
      <div className="studio-video-crop-frame" style={{ left: `${crop.x * 100}%`, top: `${crop.y * 100}%`, width: `${crop.w * 100}%`, height: `${crop.h * 100}%` }}
        onPointerDown={(event) => beginCrop(event, 'move')} onPointerMove={moveCrop}
        onPointerUp={(event) => { moveCrop(event); drag.current = null }} onPointerCancel={() => { drag.current = null }}>
        <span className="studio-video-crop-frame__size">{ready ? `${pixels.width} × ${pixels.height}` : '读取视频中…'}</span>
        <div className="studio-video-crop-frame__grid" />
        {HANDLES.map((handle) => <button key={handle} type="button" disabled={busy || !ready} className={`studio-video-crop-handle is-${handle}`} aria-label={`调整裁剪框 ${handle}`} onPointerDown={(event) => beginCrop(event, handle)} />)}
      </div>
    </div> : null}
    <div ref={rootRef} className={`studio-video-transform-dock nodrag nopan nowheel${cropMode ? ' is-crop' : ''}`} role="toolbar" aria-label={cropMode ? '画面裁剪' : '视频截取'} aria-busy={busy} style={{ left: -9999, top: 0 }}
      onPointerDown={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}>
      <Tooltip title="退出（Esc）"><button type="button" disabled={busy} aria-label="退出编辑" onClick={() => close()}><CloseOutlined /></button></Tooltip>
      {cropMode ? <span className="studio-video-transform-dock__size">{ready ? `${pixels.width} × ${pixels.height}` : '读取视频中…'}</span> : <>
        <StudioCanvasPopover content={help} open={helpOpen} onOpenChange={setHelpOpen} trigger="click" placement="topLeft" classNames={{ root: 'studio-video-shortcuts-popover' }}>
          <Tooltip title="快捷键"><button type="button" aria-label="快捷键" aria-expanded={helpOpen}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><rect x="2" y="5" width="20" height="14" rx="3" /><path d="M5 9h2m2 0h2m2 0h2m2 0h2M5 12h2m2 0h2m2 0h2m2 0h2M7 15h10" /></svg></button></Tooltip>
        </StudioCanvasPopover>
        <div className="studio-video-transform-dock__timeline" inert={busy || !ready}>
          <StudioVideoClipTimeline videoUrl={node.data.mediaUrl!} value={session.range} onChange={changeRange} showCount={false} snapToSeconds={session.snapToSeconds} playheadSeconds={playhead} />
        </div>
        <Tooltip title={`${session.snapToSeconds ? '关闭' : '开启'}整数秒吸附（Shift 临时精细调整）`}><button type="button" disabled={busy} aria-label={`${session.snapToSeconds ? '关闭' : '开启'}整数秒吸附`} aria-pressed={Boolean(session.snapToSeconds)} onClick={() => patch({ snapToSeconds: !session.snapToSeconds })}>{session.snapToSeconds ? <LinkOutlined /> : <DisconnectOutlined />}</button></Tooltip>
        <Tooltip title={`${session.loopSelection ? '关闭' : '开启'}选取循环播放`}><button type="button" disabled={busy || !ready} aria-label={`${session.loopSelection ? '关闭' : '开启'}选取循环播放`} aria-pressed={Boolean(session.loopSelection)} onClick={toggleLoop}><RetweetOutlined /></button></Tooltip>
      </>}
      <Tooltip title={busy ? `处理中 ${progress}%` : '确认（Enter）'}><button type="button" className="studio-video-transform-dock__submit" disabled={busy || !ready} aria-label={busy ? `处理中 ${progress}%` : '确认编辑'} onClick={() => void confirm()}>{busy ? <LoadingOutlined /> : <CheckOutlined />}</button></Tooltip>
      {busy ? <div className="studio-video-transform-dock__progress" role="progressbar" aria-label="视频处理进度" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${progress}%` }} /><output>{progress}%</output></div> : null}
    </div>
  </>
})
