'use client'

import { useCallback, useEffect, useMemo, useRef, type KeyboardEvent } from 'react'
import { Button, Dropdown, Slider, Tooltip } from 'antd'
import { DownloadOutlined, LoadingOutlined, PauseOutlined, CaretRightOutlined, RedoOutlined, ScissorOutlined, UndoOutlined } from '@ant-design/icons'
import type { SmartEditCommand, StudioCanvasNode } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { closeSmartEditEditor, dispatchSmartEdit, notifySmartEdit, travelSmartEditHistory, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { canSplitSmartEdit, SMART_EDIT_PIXELS_PER_SECOND, smartEditClock } from '@/utils/studio/smartEditPlayback'
import { findSmartEditItem } from '@/utils/studio/smartEditCommands'
import { readSmartEditMediaDuration } from '@/utils/studio/smartEditJob'
import { smartEditSources } from '@/utils/studio/studioSmartEdit'
import { studioId } from '@/utils/studio/studioGraph'
import { useSmartEditLock } from '@/hooks/studio/useSmartEditLock'
import { useSmartEditComposeWatcher, useSmartEditExport } from '@/hooks/studio/useSmartEditExport'
import { useSmartEditSourceTimeline } from '@/hooks/studio/useSmartEditSourceTimeline'
import { StudioSmartEditShell } from './StudioSmartEditShell'
import { StudioSmartEditPreview } from './StudioSmartEditPreview'
import { StudioSmartEditTracks } from './StudioSmartEditTracks'
import { StudioSmartEditInspector } from './StudioSmartEditInspector'
import './studio-smart-edit-editor.css'

export default function StudioSmartEditEditorHost({ scopeKey, readOnly = false }: { scopeKey: string; readOnly?: boolean }) {
  const nodeId = useStudioSmartEdit((state) => state.nodeId)
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  const selected = useStudioUiStore((state) => state.selectedNodeIds)
  const workspaceScope = useStudioUiStore((state) => state.scopeKey)
  const editorScope = useStudioSmartEdit((state) => state.scopeKey)
  const session = useStudioSmartEdit((state) => state.session)
  const exports = useSmartEditExport(scopeKey)
  useSmartEditComposeWatcher(scopeKey)
  useEffect(() => {
    if (nodeId && (workspaceScope !== scopeKey || !node || !selected.includes(nodeId))) closeSmartEditEditor()
  }, [node, nodeId, scopeKey, selected, workspaceScope])
  useEffect(() => () => closeSmartEditEditor(), [scopeKey])
  if (!node?.data.smartEdit || workspaceScope !== scopeKey || editorScope !== scopeKey) return null
  return <StudioSmartEditEditor key={`${scopeKey}:${node.id}:${session}`} node={node} scopeKey={scopeKey} readOnly={readOnly} exports={exports} />
}

function StudioSmartEditEditor({ node, scopeKey, readOnly, exports }: { node: StudioCanvasNode; scopeKey: string; readOnly: boolean; exports: ReturnType<typeof useSmartEditExport> }) {
  const time = useStudioSmartEdit((state) => state.playheadSeconds)
  const playing = useStudioSmartEdit((state) => state.playing)
  const buffering = useStudioSmartEdit((state) => state.buffering)
  const closing = useStudioSmartEdit((state) => state.closing)
  const session = useStudioSmartEdit((state) => state.session)
  const selected = useStudioSmartEdit((state) => state.selectedClipId)
  const history = useStudioSmartEdit((state) => state.histories[node.id])
  const progress = useStudioSmartEdit((state) => state.exportProgress[`${scopeKey}:${node.id}`])
  const composeProgress = useStudioSmartEdit((state) => state.composeProgress[`${scopeKey}:${node.id}`])
  const lock = useSmartEditLock(node.id, !readOnly)
  const readOnlyContent = readOnly || lock.readOnly || Boolean(node.data.smartEdit!.jobId) || progress !== undefined || closing
  const sourceTimeline = useSmartEditSourceTimeline(node, !readOnlyContent)
  const edit = sourceTimeline.edit
  const disabled = readOnlyContent || sourceTimeline.loading || Boolean(sourceTimeline.error)
  const overlayRead = useRef<AbortController | null>(null)
  useEffect(() => {
    if (disabled) overlayRead.current?.abort()
    return () => overlayRead.current?.abort()
  }, [disabled, node.id, scopeKey, session])
  const titleKey = useStudioUiStore((state) => { const ids = new Set([...edit.timeline.videoClips, ...edit.timeline.overlayClips, ...edit.timeline.audioClips].map((clip) => clip.sourceNodeId)); return JSON.stringify(Object.fromEntries(state.nodes.filter((candidate) => ids.has(candidate.id)).map((candidate) => [candidate.id, candidate.data.title || '未命名节点']))) })
  const titles = useMemo((): Record<string, string> => JSON.parse(titleKey), [titleKey])
  const item = findSmartEditItem(edit.timeline, selected)
  const videoIndex = item && 'track' in item && item.track === 'video' ? edit.timeline.videoClips.findIndex((clip) => clip.id === item.id) : null
  const command = useCallback((value: SmartEditCommand) => { if (!disabled) dispatchSmartEdit(node.id, value) }, [disabled, node.id])
  const togglePlayback = useCallback(() => useStudioSmartEdit.setState((state) => ({ playing: !state.playing, playheadSeconds: state.playheadSeconds >= edit.timeline.durationSeconds ? 0 : state.playheadSeconds })), [edit.timeline.durationSeconds])
  useEffect(() => {
    if (!playing || buffering || sourceTimeline.loading) return
    const started = performance.now(), from = useStudioSmartEdit.getState().playheadSeconds
    let frame = 0
    const tick = () => {
      const next = Math.min(edit.timeline.durationSeconds, from + (performance.now() - started) / 1000)
      useStudioSmartEdit.setState({ playheadSeconds: next, playing: next < edit.timeline.durationSeconds })
      if (next < edit.timeline.durationSeconds) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [buffering, edit.timeline.durationSeconds, playing, sourceTimeline.loading])
  const split = (mode: 'at' | 'keepAfter' | 'keepBefore') => { if (item) command({ type: 'splitClip', clipId: item.id, atSeconds: time, mode }) }
  const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('input, textarea, select, [contenteditable=true]')) return
    if (event.code === 'Space' && !(event.target as HTMLElement).closest('button,[role=slider]')) { event.preventDefault(); if (!sourceTimeline.loading && !sourceTimeline.error) togglePlayback() }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); if (!disabled) travelSmartEditHistory(node.id, event.shiftKey ? 'redo' : 'undo') }
  }
  const add = async (key: string) => {
    if (disabled) return
    if (key === 'subtitle' || key === 'text') {
      const cue = { id: studioId(key), timelineStartSeconds: time, durationSeconds: 3, text: key === 'text' ? '输入文字' : '输入字幕' }
      command(key === 'text' ? { type: 'addText', item: { ...cue, fontSize: 48, color: '#ffffff', rect: { x: 0.1, y: 0.25, w: 0.8, h: 0.25 }, animation: 'none' } } : { type: 'addSubtitle', cue })
      useStudioSmartEdit.setState({ selectedClipId: cue.id }); return
    }
    const source = useStudioUiStore.getState().nodes.find((candidate) => candidate.id === key)
    if (!source || source.data.kind !== 'video' || overlayRead.current) return
    const controller = new AbortController()
    overlayRead.current = controller
    try {
      const duration = await readSmartEditMediaDuration(source, controller.signal)
      const workspace = useStudioUiStore.getState(), editor = useStudioSmartEdit.getState()
      if (controller.signal.aborted || workspace.scopeKey !== scopeKey || editor.nodeId !== node.id || editor.session !== session || workspace.nodes.find((item) => item.id === source.id)?.data.mediaUrl !== source.data.mediaUrl) return
      command({ type: 'addVideoClip', clip: { id: studioId('overlay'), track: 'overlay', sourceNodeId: source.id, sourceUrl: source.data.mediaUrl!, sourceResourceId: source.data.canvasPrimaryResourceId || source.data.canvasResourceIds?.[0], sourceDurationSeconds: duration, timelineStartSeconds: time, range: { startSeconds: 0, endSeconds: duration }, volume: 0 } })
    } catch (error) { if (!controller.signal.aborted) notifySmartEdit(node.id, 'error', error instanceof Error ? error.message : '素材读取失败', scopeKey) }
    finally { if (overlayRead.current === controller) overlayRead.current = null }
  }
  const sources = smartEditSources(node.id, useStudioUiStore.getState().nodes, useStudioUiStore.getState().edges).filter((source) => source.data.kind === 'video')
  const exportItems = [{ key: 'local', label: '导出到本地 · MP4' }, { key: 'canvas', label: '导出到画布 · 视频节点' }, { key: 'fcpxml', label: '导出达芬奇工程 · FCPXML' }, ...(edit.composeDownload ? [{ type: 'divider' as const }, { key: 'last', label: '下载上次服务端成片' }] : [])]
  const exportMenu = <Dropdown menu={{ items: exportItems, onClick: ({ key }) => key === 'last' ? exports.downloadLast(node.id) : void exports.start(node.id, key as 'local' | 'canvas' | 'fcpxml') }} disabled={disabled}><Button size="small" aria-label="导出" icon={<DownloadOutlined />} disabled={disabled}>导出</Button></Dropdown>
  const exportControl = progress !== undefined ? <Button size="small" onClick={() => exports.cancel(node.id)}>取消导出 {progress}%</Button>
    : edit.compose ? <Tooltip title="服务端合成中，可继续编辑；改动后的时间轴不会被这次成片覆盖"><Button size="small" disabled={readOnly || lock.readOnly} onClick={() => exports.cancel(node.id)}>取消导出 {composeProgress ?? 0}%</Button></Tooltip>
      : exportMenu
  return <StudioSmartEditShell title={node.data.title} onKeyDown={handleKeyDown} preview={sourceTimeline.loading ? <div className="studio-smart-edit-editor__loading" role="status"><LoadingOutlined spin /><span>正在载入素材…</span></div> : sourceTimeline.error ? <div className="studio-smart-edit-editor__loading" role="alert"><span>{sourceTimeline.error}</span><Button onClick={sourceTimeline.retry}>重新加载素材</Button></div> : <StudioSmartEditPreview nodeId={node.id} edit={edit} aspectRatio={node.data.generationOptions?.aspectRatio} />}>
    <section className="studio-smart-edit-editor__dock" tabIndex={-1}>
      {sourceTimeline.loading || sourceTimeline.appending || sourceTimeline.error || sourceTimeline.appendError ? <div className="studio-smart-edit-editor__notice" role="status">{sourceTimeline.loading || sourceTimeline.appending ? '正在读取素材时长…' : sourceTimeline.error || sourceTimeline.appendError}{!sourceTimeline.appending && (sourceTimeline.error || sourceTimeline.appendError) ? <Button size="small" onClick={sourceTimeline.retry}>重试</Button> : null}</div> : null}
      {lock.message || edit.jobId ? <div role="status" className="studio-smart-edit-editor__notice">{lock.message || '正在整理素材，完成后可继续精修；关闭精修台不会中断作业。'}</div> : null}
      <div className="studio-smart-edit-editor__toolbar">
        <div className="studio-smart-edit-editor__group"><Tooltip title="撤销"><Button size="small" aria-label="撤销" icon={<UndoOutlined />} disabled={disabled || !history?.past.length} onClick={() => travelSmartEditHistory(node.id, 'undo')} /></Tooltip><Tooltip title="重做"><Button size="small" aria-label="重做" icon={<RedoOutlined />} disabled={disabled || !history?.future.length} onClick={() => travelSmartEditHistory(node.id, 'redo')} /></Tooltip>
          <Button size="small" aria-label="分割" icon={<ScissorOutlined />} disabled={disabled || !canSplitSmartEdit(item, time)} onClick={() => split('at')}>分割</Button><Tooltip title="丢弃播放头之前的部分"><Button size="small" aria-label="前分割" disabled={disabled || !canSplitSmartEdit(item, time)} onClick={() => split('keepAfter')}>前分割</Button></Tooltip><Tooltip title="丢弃播放头之后的部分"><Button size="small" aria-label="后分割" disabled={disabled || !canSplitSmartEdit(item, time)} onClick={() => split('keepBefore')}>后分割</Button></Tooltip>
        </div>
        <div className="studio-smart-edit-editor__transport"><time>{smartEditClock(time)}</time><Button size="small" aria-label={playing ? '暂停' : '播放'} disabled={sourceTimeline.loading || Boolean(sourceTimeline.error)} icon={playing ? <PauseOutlined /> : <CaretRightOutlined />} onClick={togglePlayback} /><time>{smartEditClock(edit.timeline.durationSeconds)}</time></div>
        <div className="studio-smart-edit-editor__group"><Button size="small" aria-label="吸附" type={edit.snapping ? 'primary' : 'default'} aria-pressed={edit.snapping} disabled={disabled} onClick={() => command({ type: 'setSnapping', enabled: !edit.snapping })}>吸附</Button><Button size="small" aria-label="缩小时间轴" disabled={disabled || edit.timelineZoom <= 0.25} onClick={() => command({ type: 'setTimelineZoom', zoom: edit.timelineZoom - 0.25 })}>−</Button><Slider ariaLabelForHandle="时间轴缩放" className="studio-smart-edit-editor__zoom" min={0.25} max={4} step={0.05} value={edit.timelineZoom} disabled={disabled} onChange={(zoom) => command({ type: 'setTimelineZoom', zoom })} /><Button size="small" aria-label="放大时间轴" disabled={disabled || edit.timelineZoom >= 4} onClick={() => command({ type: 'setTimelineZoom', zoom: edit.timelineZoom + 0.25 })}>＋</Button>
          <Button size="small" disabled={disabled} onClick={(event) => { const width = event.currentTarget.closest('section')?.querySelector('.studio-smart-edit-tracks__scroll')?.clientWidth || 600; command({ type: 'setTimelineZoom', zoom: width / (Math.max(10, edit.timeline.durationSeconds + 2) * SMART_EDIT_PIXELS_PER_SECOND) }) }}>适应窗口</Button>
          {exportControl}
        </div>
      </div>
      <div className="studio-smart-edit-editor__content"><div className="studio-smart-edit-editor__timeline"><div className="studio-smart-edit-editor__lane-tools"><span>多轨精修 <small>拖动移动 · 拖动边缘裁切</small></span><Dropdown disabled={disabled} menu={{ items: [{ key: 'subtitle', label: '添加字幕' }, { key: 'text', label: '添加文字 / 特效' }, { key: 'overlay-menu', label: '添加画中画', disabled: !sources.length, children: sources.map((source) => ({ key: source.id, label: source.data.title })) }], onClick: ({ key }) => void add(key) }}><Button size="small" disabled={disabled}>＋ 添加轨道内容</Button></Dropdown></div><StudioSmartEditTracks edit={edit} disabled={disabled} titles={titles} command={command} /></div><StudioSmartEditInspector item={item} disabled={disabled} command={command} videoIndex={videoIndex} videoCount={edit.timeline.videoClips.length} /></div>
  </section>
  </StudioSmartEditShell>
}
