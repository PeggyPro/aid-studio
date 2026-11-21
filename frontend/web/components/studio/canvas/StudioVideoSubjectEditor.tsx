'use client'

/* eslint-disable @next/next/no-img-element -- Runtime media thumbnails from subject detection. */
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import { Modal, message } from 'antd'
import { AimOutlined, BorderOutlined, HighlightOutlined, ClearOutlined, ReloadOutlined, CloseOutlined, CheckOutlined, ArrowUpOutlined, PauseOutlined, CaretRightOutlined } from '@ant-design/icons'
import type { StudioCanvasNode, StudioComposerRef, StudioVideoClipRange } from '@/types/studio'
import type { StudioVideoSubjectEdit, StudioVideoSubjectMark, StudioVideoSubjectMode, StudioVideoSubjectTool, SubjectCandidate, SubjectPoint, SubjectSelection, SubjectStroke } from '@/types/studio-video-subject'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { resolveStudioVideoFilmstripSource } from '@/utils/mediaFetch'
import { studioId } from '@/utils/studio/studioGraph'
import { normalizeRect, clientPointToNormalized, clampRectToImage, pointToDefaultRect } from '@/utils/studio/touchEditGeometry'
import { createStudioVideoClipRange } from '@/utils/studio/studioVideoClip'
import { SUBJECT_LIMIT, SUBJECT_MODES, selectedSubject, subjectEditProblem } from '@/utils/studio/studioVideoSubject'
import { detectCanvasVideoSubject, detectStudioVideoSubject } from '@/hooks/studio/studioVideoSubjectApi'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { StudioVideoClipTimeline } from '../composer/StudioVideoClipTimeline'
import { StudioAssetLibraryModal } from '../composer/StudioAssetLibraryModal'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import './studio-video-subject.css'

const boxStyle = (r: { x: number; y: number; w: number; h: number }) => ({ left: `${r.x * 100}%`, top: `${r.y * 100}%`, width: `${r.w * 100}%`, height: `${r.h * 100}%` })

function selectionBounds(selection: SubjectSelection) {
  if (selection.tool !== 'brush') return selection.rect
  let left = 1, top = 1, right = 0, bottom = 0, found = false
  for (const stroke of selection.strokes) {
    if (stroke.tool !== 'brush') continue
    const radius = stroke.size / 2
    for (const point of stroke.points) {
      found = true
      left = Math.min(left, point.x - radius)
      top = Math.min(top, point.y - radius)
      right = Math.max(right, point.x + radius)
      bottom = Math.max(bottom, point.y + radius)
    }
  }
  if (!found) throw new Error('请先画出主体区域')
  left = Math.max(0, left)
  top = Math.max(0, top)
  right = Math.min(1, right)
  bottom = Math.min(1, bottom)
  return { x: left, y: top, w: right - left, h: bottom - top }
}

function SubjectButton({ label, icon, onClick, activeTool = false, disabled = false }: { label: string; icon: ReactNode; onClick: () => void; activeTool?: boolean; disabled?: boolean }) {
  return <StudioCanvasTooltip title={label}><button type="button" aria-label={label} aria-pressed={activeTool} className={activeTool ? 'is-active' : ''} disabled={disabled} onClick={onClick}>{icon}</button></StudioCanvasTooltip>
}

async function captureSubjectFrame(video: HTMLVideoElement): Promise<Blob> {
  const scale = Math.min(1, 1920 / video.videoWidth, 1080 / video.videoHeight)
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(video.videoWidth * scale))
  canvas.height = Math.max(1, Math.round(video.videoHeight * scale))
  const context = canvas.getContext('2d')
  if (!context) throw new Error('浏览器无法截取视频帧')
  try { context.drawImage(video, 0, 0, canvas.width, canvas.height) }
  catch { throw new Error('视频资源未允许跨域截帧，请检查媒体 CORS 配置') }
  return new Promise<Blob>((resolve, reject) => {
    try { canvas.toBlob((value) => value ? resolve(value) : reject(new Error('视频截帧失败')), 'image/jpeg', 0.85) }
    catch { reject(new Error('视频资源未允许跨域截帧，请检查媒体 CORS 配置')) }
  })
}

export function StudioVideoSubjectEditor({ source, mode, onClose, onConfirm }: {
  source: StudioCanvasNode
  mode: StudioVideoSubjectMode
  onClose: () => void
  onConfirm: (edit: StudioVideoSubjectEdit) => Promise<boolean>
}) {
  const canvasRuntime = useCanvasProjectRuntime()
  const videoRef = useRef<HTMLVideoElement>(null)
  const overlayRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const requestVersion = useRef(0)
  const busyRef = useRef(false)
  const alive = useRef(true)
  const confirming = useRef(false)
  const frameRef = useRef(0)
  const frameDetectionRef = useRef<{ key: string; promise: Promise<{ candidates: SubjectCandidate[]; frameUrl: string }> } | null>(null)
  const [tool, setTool] = useState<StudioVideoSubjectTool>('point')
  const [brushSize, setBrushSize] = useState(0.04)
  const [marks, setMarks] = useState<StudioVideoSubjectMark[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [draft, setDraft] = useState<SubjectSelection | null>(null)
  const draftRef = useRef<SubjectSelection | null>(null)
  const gesture = useRef<{ start: SubjectPoint; tool: StudioVideoSubjectTool; stroke?: SubjectStroke } | null>(null)
  const refiningMark = useRef<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [ready, setReady] = useState(false)
  const [opened, setOpened] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [frame, setFrame] = useState(0)
  const [ratio, setRatio] = useState(16 / 9)
  const [range, setRange] = useState<StudioVideoClipRange>(createStudioVideoClipRange(10))
  const [libraryOpen, setLibraryOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [candidateOpen, setCandidateOpen] = useState(false)
  const [replacementOpen, setReplacementOpen] = useState(false)
  const playback = resolveStudioVideoFilmstripSource(source.data.mediaUrl || '')
  const playbackSrc = opened ? playback.src : ''
  const playbackCrossOrigin = opened ? playback.crossOrigin : undefined
  const nodes = useStudioUiStore((state) => state.nodes)
  const assets = useStudioUiStore((state) => state.savedAssets)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const scopeKey = useRef(useStudioUiStore.getState().scopeKey)
  const active = marks.find((mark) => mark.id === activeId)
  const candidate = active && selectedSubject(active)
  const liveSource = nodes.find((node) => node.id === source.id)
  const sourceValid = liveSource?.data.mediaUrl === source.data.mediaUrl
  const edit: StudioVideoSubjectEdit = { mode, sourceNodeId: source.id, sourceVideoUrl: source.data.mediaUrl!, sourceTitle: source.data.title,
    sourceRecordId: Number(source.data.recordId) || undefined, range, marks }
  const problem = !ready ? '视频加载中' : !sourceValid ? '源视频已变化，请重新打开编辑' : busy || uploading ? '请等待当前操作完成' : draft ? '请先确认或取消当前选区' : subjectEditProblem(edit)

  useEffect(() => { alive.current = true; return () => { alive.current = false; requestVersion.current += 1 } }, [])
  useEffect(() => { setReady(false) }, [opened, source.data.mediaUrl])

  function setSelection(value: SubjectSelection | null) { draftRef.current = value; setDraft(value) }
  function patchMark(id: string, patch: Partial<StudioVideoSubjectMark>) {
    setMarks((current) => current.map((mark) => mark.id === id ? { ...mark, ...patch } : mark))
  }
  function seek(time: number) {
    if (!videoRef.current) return
    videoRef.current.pause()
    videoRef.current.currentTime = Math.max(0, Math.min(range.durationSeconds, time))
    frameRef.current = videoRef.current.currentTime
    setFrame(frameRef.current)
    setActiveId(null)
    setSelection(null)
  }
  function changeRange(next: StudioVideoClipRange) { if (busyRef.current) return; setRange(next); seek(next.startSeconds) }
  function cancelDraft() {
    requestVersion.current += 1
    busyRef.current = false
    setBusy(false)
    setSelection(null)
    refiningMark.current = null
  }
  async function analyze(selection: SubjectSelection) {
    const refineId = refiningMark.current
    if (busyRef.current || (!refineId && marks.length >= SUBJECT_LIMIT)) return
    videoRef.current?.pause()
    busyRef.current = true
    setBusy(true)
    setSelection(selection)
    const version = ++requestVersion.current
    const frameSeconds = videoRef.current?.currentTime ?? frameRef.current
    frameRef.current = frameSeconds
    setFrame(frameSeconds)
    try {
      let candidates: SubjectCandidate[]
      if (canvasRuntime) {
        const video = videoRef.current
        const workspaceId = canvasRuntime.project?.workspace?.workspaceId
        if (!video || !workspaceId || !video.videoWidth || !video.videoHeight || video.readyState < 2) {
          throw new Error('当前视频帧尚未就绪')
        }
        const key = JSON.stringify([workspaceId, source.id, source.data.mediaUrl, frameSeconds])
        if (frameDetectionRef.current?.key !== key) {
          const promise = (async () => {
            const blob = await captureSubjectFrame(video)
            const frame = await canvasRuntime.uploadResource(new File([blob], 'subject-frame.jpg', { type: 'image/jpeg' }), 'IMAGE')
            const state = useStudioUiStore.getState()
            if (!alive.current || state.scopeKey !== scopeKey.current
              || state.nodes.find((node) => node.id === source.id)?.data.mediaUrl !== source.data.mediaUrl) {
              throw new Error('源视频或画布已变化，请重新打开主体编辑')
            }
            return { candidates: await detectCanvasVideoSubject(String(workspaceId), String(frame.resourceId)), frameUrl: frame.resourceUrl }
          })()
          frameDetectionRef.current = { key, promise }
          void promise.catch(() => {
            if (frameDetectionRef.current?.promise === promise) frameDetectionRef.current = null
          })
        }
        const detected = await frameDetectionRef.current.promise
        candidates = detected.candidates
        const point = selection.point
        if (point) candidates = candidates.filter((item) => point.x >= item.rect.x && point.y >= item.rect.y
          && point.x <= item.rect.x + item.rect.w && point.y <= item.rect.y + item.rect.h)
        else {
          const selected = selectionBounds(selection)
          candidates = candidates.filter((item) => {
            const box = item.rect
            return box.x < selected.x + selected.w && box.x + box.w > selected.x
              && box.y < selected.y + selected.h && box.y + box.h > selected.y
          })
        }
        if (!candidates.length && selection.tool !== 'point') {
          candidates = [{ id: 'manual-selection', label: '手动选区', rect: selectionBounds(selection),
            thumbnailUrl: detected.frameUrl }]
        }
      } else {
        candidates = await detectStudioVideoSubject({ sourceNodeId: source.id, sourceVideoUrl: source.data.mediaUrl!, frameSeconds, selection,
          ...(Number(projectId) > 0 ? { projectId: Number(projectId) } : {}),
          ...(episodeId != null ? { episodeId: Number(episodeId) } : {}) })
      }
      if (!candidates.length) throw new Error('未识别到所选位置的主体，请改用框选')
      const state = useStudioUiStore.getState()
      if (!alive.current || version !== requestVersion.current || state.scopeKey !== scopeKey.current
        || state.nodes.find((node) => node.id === source.id)?.data.mediaUrl !== source.data.mediaUrl) return
      const previous = marks.find((mark) => mark.id === refineId)
      const mark: StudioVideoSubjectMark = { ...previous, id: refineId || studioId('subject'), frameSeconds, selection, candidates, candidateId: candidates[0].id,
        instruction: previous?.instruction || '', confirmed: false }
      setMarks((current) => refineId ? current.map((item) => item.id === refineId ? mark : item) : [...current, mark])
      refiningMark.current = null
      setActiveId(mark.id)
      setSelection(null)
    } catch (error) {
      if (alive.current && version === requestVersion.current) message.error(
        error instanceof Error ? error.message : '主体分析未完成，请重试')
    } finally {
      if (alive.current && version === requestVersion.current) { busyRef.current = false; setBusy(false) }
    }
  }
  function down(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.button !== 0 || !ready || busyRef.current || uploading || !sourceValid || videoRef.current?.seeking) return
    if ((event.target as HTMLElement).closest('button, input, .studio-subject__region-menu')) return
    videoRef.current?.pause()
    const point = clientPointToNormalized(event.clientX, event.clientY, event.currentTarget.getBoundingClientRect())
    if (tool === 'eraser' && !draftRef.current?.strokes.length) {
      if (active?.selection.tool !== 'brush') { message.info('请先点击画笔主体的编号，再擦除选区'); return }
      refiningMark.current = active.id
      setSelection(structuredClone(active.selection))
    }
    if (tool !== 'eraser') refiningMark.current = null
    if (tool !== 'eraser' && marks.length >= SUBJECT_LIMIT) { message.info('最多选择 4 个主体'); return }
    setActiveId(null)
    event.preventDefault()
    if (tool === 'point') {
      void analyze({ tool: 'point', point, rect: clampRectToImage(pointToDefaultRect(point.x, point.y)), strokes: [] })
      return
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    const stroke = tool === 'brush' || tool === 'eraser' ? { tool, size: brushSize, points: [point] } as SubjectStroke : undefined
    gesture.current = { start: point, tool, stroke }
    if (tool === 'rectangle') setSelection({ tool: 'rectangle', rect: normalizeRect(point, point), strokes: [] })
    else {
      const previous = draftRef.current?.tool === 'brush' ? draftRef.current.strokes : []
      if (previous.length >= 128) { gesture.current = null; message.info('笔迹过多，请确认或重新绘制'); return }
      setSelection({ tool: 'brush', rect: { x: 0, y: 0, w: 1, h: 1 }, strokes: [...previous, stroke!] })
    }
  }
  function move(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = gesture.current
    if (!drag) return
    const point = clientPointToNormalized(event.clientX, event.clientY, event.currentTarget.getBoundingClientRect())
    if (drag.tool === 'rectangle') setSelection({ tool: 'rectangle', rect: normalizeRect(drag.start, point), strokes: [] })
    else if (drag.stroke && draftRef.current) {
      const points = drag.stroke.points
      if (points.length >= 4096) return
      const last = points[points.length - 1]
      if (Math.hypot(point.x - last.x, point.y - last.y) < 0.002) return
      drag.stroke = { ...drag.stroke, points: [...points, point] }
      setSelection({ ...draftRef.current, strokes: [...draftRef.current.strokes.slice(0, -1), drag.stroke] })
    }
  }
  function up(event: ReactPointerEvent<HTMLDivElement>) {
    move(event)
    gesture.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    const selection = draftRef.current
    if (selection?.tool === 'rectangle' && (selection.rect.w < 0.01 || selection.rect.h < 0.01)) setSelection(null)
    else if (selection?.tool === 'brush') void analyze(selection)
  }
  async function chooseReplacement(ref: StudioComposerRef) {
    const id = activeId
    if (!id || ref.mediaKind !== 'image' || !ref.previewUrl) { message.warning('请选择图片'); return }
    if (/^(blob:|data:)/.test(ref.previewUrl)) { message.warning('请通过本地上传将图片上传后使用'); return }
    try {
      const resource = canvasRuntime ? await canvasRuntime.resolveResourceReference(ref) : null
      if (canvasRuntime && resource?.mediaType !== 'IMAGE') throw new Error('替换素材必须为图片')
      patchMark(id, { replacementUrl: resource?.resourceUrl ?? ref.previewUrl,
        replacementNodeId: ref.nodeId, replacementResourceId: resource?.resourceId, confirmed: false })
    } catch (error) {
      message.error(error instanceof Error ? error.message : '替换图片登记失败')
    }
  }
  async function upload(file: File) {
    const id = activeId
    if (!id || uploading || !/^image\/(jpeg|png|webp)$/.test(file.type)) { message.warning('请选择 JPG、PNG 或 WebP 图片'); return }
    setUploading(true)
    try {
      if (canvasRuntime) {
        const resource = await canvasRuntime.uploadResource(file, 'IMAGE')
        if (alive.current) patchMark(id, { replacementUrl: resource.resourceUrl,
          replacementResourceId: resource.resourceId, replacementNodeId: undefined, confirmed: false })
      } else {
        const url = await uploadImageToOssWithToast(file)
        if (url && alive.current) patchMark(id, { replacementUrl: url, replacementNodeId: undefined, confirmed: false })
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '替换图片上传失败')
    } finally { if (alive.current) setUploading(false) }
  }
  return <Modal open centered footer={null} closable={false} keyboard={false} mask={{ closable: false }} width="min(1200px, 96vw)"
    afterOpenChange={setOpened}
    className="create-flow-modal studio-subject-modal" onCancel={onClose}>
    <div className="studio-subject nodrag nopan nowheel" onKeyDown={(event) => {
      event.stopPropagation()
      if ((event.target as HTMLElement).closest('input, textarea, [role=menu]')) return
      if (event.key === 'Escape') { event.preventDefault(); if (draft || busy) cancelDraft(); else onClose() }
    }}>
      <div className="studio-subject__toolbar" role="toolbar" aria-label={SUBJECT_MODES[mode].label}>
        <SubjectButton label="退出主体编辑" icon={<CloseOutlined />} onClick={onClose} />
        <strong>{SUBJECT_MODES[mode].label} <small>({marks.filter((mark) => mark.confirmed).length}/{SUBJECT_LIMIT})</small></strong>
        <i />
        {(['point', 'rectangle', 'brush', 'eraser'] as const).map((value, index) => <SubjectButton key={value}
          label={['点选', '框选', '画笔', '橡皮擦'][index]} icon={[<AimOutlined key="p" />, <BorderOutlined key="r" />, <HighlightOutlined key="b" />, <ClearOutlined key="e" />][index]}
          onClick={() => { setTool(value); if (value !== 'eraser') setActiveId(null); if (value === 'point' || value === 'rectangle') setSelection(null) }} activeTool={tool === value} disabled={busy || uploading} />)}
        {(tool === 'brush' || tool === 'eraser') && <input aria-label="画笔粗细" type="range" min="0.008" max="0.15" step="0.002" value={brushSize} onChange={(event) => setBrushSize(Number(event.target.value))} />}
        <i /><SubjectButton label="重置所有标记" icon={<ReloadOutlined />} onClick={() => { cancelDraft(); setMarks([]); setActiveId(null) }} disabled={uploading} />
      </div>
      <div className="studio-subject__stage" style={{ width: `min(100%, calc(58vh * ${ratio}))`, aspectRatio: ratio }}>
        <video ref={videoRef} crossOrigin={playbackCrossOrigin}
          src={playbackSrc || undefined} playsInline preload="metadata"
          onLoadedMetadata={(event) => { const video = event.currentTarget; if (!Number.isFinite(video.duration) || !video.videoWidth) return
            setRatio(video.videoWidth / video.videoHeight); setRange(createStudioVideoClipRange(video.duration)); setReady(true) }}
          onError={() => {
            if (!opened) return
            setReady(false)
            message.error('视频无法加载，请检查源文件')
          }}
          onTimeUpdate={(event) => { const video = event.currentTarget; frameRef.current = video.currentTime; setFrame(video.currentTime)
            if (!video.paused && video.currentTime >= range.endSeconds) { video.pause(); video.currentTime = range.endSeconds } }}
          onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} />
        <span className="studio-subject__title">▷ {source.data.title}</span>
        <div ref={overlayRef} className="studio-subject__overlay" onPointerDown={down} onPointerMove={move} onPointerUp={up}
          onPointerCancel={() => { gesture.current = null; setSelection(null) }}>
          {marks.filter((mark) => Math.abs(mark.frameSeconds - frame) < 0.12).map((mark) => {
            const item = selectedSubject(mark)
            if (!item) return null
            return <div key={mark.id} className={`studio-subject__box${activeId === mark.id ? ' is-active' : ''}`} style={boxStyle(item.rect)}>
              <button className="studio-subject__badge" type="button" aria-label={`编辑主体 ${marks.indexOf(mark) + 1}`} onClick={() => { setActiveId(mark.id); setCandidateOpen(false) }}>{marks.indexOf(mark) + 1}</button>
              <button className="studio-subject__delete" type="button" aria-label={`删除主体 ${marks.indexOf(mark) + 1}`} onClick={() => { setMarks((current) => current.filter((entry) => entry.id !== mark.id)); setActiveId(null) }}><CloseOutlined /></button>
            </div>
          })}
          {draft?.tool === 'rectangle' || draft?.tool === 'point' ? <div className="studio-subject__draft" style={boxStyle(draft.rect)} /> : null}
          {draft?.strokes.length ? <svg className="studio-subject__paint" viewBox={`0 0 ${ratio * 1000} 1000`} preserveAspectRatio="none">
            <defs><mask id={`subject-mask-${source.id}`}><rect width="100%" height="100%" fill="black" />{draft.strokes.map((stroke, index) => stroke.points.length === 1 ? <circle key={index}
              cx={stroke.points[0].x * ratio * 1000} cy={stroke.points[0].y * 1000} r={stroke.size * Math.min(ratio, 1) * 500}
              fill={stroke.tool === 'eraser' ? 'black' : 'white'} /> : <polyline key={index}
              points={stroke.points.map((point) => `${point.x * ratio * 1000},${point.y * 1000}`).join(' ')} fill="none" stroke={stroke.tool === 'eraser' ? 'black' : 'white'}
              strokeWidth={stroke.size * Math.min(ratio, 1) * 1000} strokeLinecap="round" strokeLinejoin="round" />)}</mask></defs>
            <rect width="100%" height="100%" fill="#5285ff" opacity="0.65" mask={`url(#subject-mask-${source.id})`} />
          </svg> : null}
          {busy && <div className="studio-subject__analysis" role="status"><span className="studio-node-card__loader" />分析中…</div>}
          {draft?.tool === 'rectangle' && !busy && <div className="studio-subject__draft-actions" style={{
            left: `clamp(48px, ${(draft.rect.x + draft.rect.w / 2) * 100}%, calc(100% - 48px))`,
            top: `clamp(4px, calc(${(draft.rect.y + draft.rect.h) * 100}% + 8px), calc(100% - 48px))`
          }}>
            <SubjectButton label="取消选区，重新选择" icon={<CloseOutlined />} onClick={() => setSelection(null)} />
            <SubjectButton label="确认选区并分析主体" icon={<CheckOutlined />} onClick={() => void analyze(draft)} />
          </div>}
          {active && candidate && Math.abs(active.frameSeconds - frame) < 0.12 && <div className="studio-subject__region-menu"
            style={{ left: `min(${candidate.rect.x * 100}%, max(0px, calc(100% - 430px)))`, top: `min(${candidate.rect.y * 100}%, max(0px, calc(100% - 100px)))` }}>
            <StudioCanvasDropdownPopover open={candidateOpen} onOpenChange={setCandidateOpen} placement="bottomLeft" ariaLabel="识别出的主体" activeKey={active.candidateId}
              items={active.candidates.map((item) => ({ key: item.id, label: item.label }))}
              onSelect={(id) => patchMark(active.id, { candidateId: id, confirmed: false })}
              trigger={<button type="button">♙ {candidate.label}⌄</button>} getPopupContainer={(trigger) => trigger.closest('.studio-subject') as HTMLElement} />
            {mode === 'modify' && <input aria-label="主体修改内容" placeholder="描述想要如何更改画面" maxLength={1000} value={active.instruction}
              onChange={(event) => patchMark(active.id, { instruction: event.target.value, confirmed: false })} />}
            {mode === 'replace' && <StudioCanvasDropdownPopover open={replacementOpen} onOpenChange={setReplacementOpen} placement="bottom" ariaLabel="选择替换图片"
              items={[{ key: 'upload', label: '本地上传' }, { key: 'asset', label: '资产库' }]}
              onSelect={(key) => key === 'upload' ? fileRef.current?.click() : setLibraryOpen(true)}
              trigger={<button type="button" aria-label="选择替换图片" disabled={uploading}>{active.replacementUrl ? <img src={active.replacementUrl} alt="替换图片" /> : '＋ 替换图片'}</button>}
              getPopupContainer={(trigger) => trigger.closest('.studio-subject') as HTMLElement} />}
            <SubjectButton label="确认主体标记" icon={<CheckOutlined />} onClick={() => { patchMark(active.id, { confirmed: true }); setActiveId(null) }}
              disabled={uploading || (mode === 'modify' && !active.instruction.trim()) || (mode === 'replace' && !active.replacementUrl)} />
          </div>}
        </div>
      </div>
      <div className="studio-subject__playback">
        <SubjectButton label={playing ? '暂停' : '播放所选片段'} icon={playing ? <PauseOutlined /> : <CaretRightOutlined />} onClick={() => {
          const video = videoRef.current; if (!video) return
          if (playing) video.pause(); else { setActiveId(null); if (frame < range.startSeconds || frame >= range.endSeconds) video.currentTime = range.startSeconds
            void video.play().catch(() => message.warning('暂时无法播放视频')) }
        }} disabled={!ready || busy || Boolean(draft)} />
        <input type="range" aria-label="选择视频当前帧" min="0" max={range.durationSeconds} step="0.01" value={frame} disabled={!ready || busy} onChange={(event) => seek(Number(event.target.value))} />
        <span>{frame.toFixed(2)} / {range.durationSeconds.toFixed(2)} 秒</span>
      </div>
      <div className="studio-subject__bottom" aria-busy={busy}>
        <div className="studio-subject__timeline">
          {opened && ready ? <StudioVideoClipTimeline videoUrl={source.data.mediaUrl!} value={range} showCount={false} playheadSeconds={frame} onChange={changeRange} /> : <div className="studio-subject__timeline-loading" role="status">正在加载视频…</div>}
          <div className="studio-subject__markers">{marks.map((mark, index) => <button key={mark.id} type="button" aria-label={`定位主体 ${index + 1} 标记帧`} style={{ left: `${mark.frameSeconds / range.durationSeconds * 100}%` }}
            onClick={() => { if (busy) return; seek(mark.frameSeconds); setActiveId(mark.id) }}>{index + 1}</button>)}</div>
        </div>
        <StudioCanvasTooltip title={problem || (mode === 'modify' || mode === 'replace' ? '确认并创建视频节点' : '生成处理后的视频')}>
          <button className="studio-subject__submit" type="button" aria-label={mode === 'modify' || mode === 'replace' ? '确认并创建视频节点' : '生成处理后的视频'} disabled={Boolean(problem)} onClick={() => {
            if (problem || confirming.current || useStudioUiStore.getState().scopeKey !== scopeKey.current) return
            confirming.current = true
            void onConfirm(structuredClone(edit)).finally(() => { confirming.current = false })
          }}>
            {mode === 'modify' || mode === 'replace' ? '确定' : <ArrowUpOutlined />}
          </button>
        </StudioCanvasTooltip>
      </div>
      <p className="studio-subject__hint">{problem || '已确认主体，可创建处理视频'} · 点击序号可查看和编辑标记</p>
      <input ref={fileRef} hidden type="file" accept="image/jpeg,image/png,image/webp" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void upload(file) }} />
      {libraryOpen && <StudioAssetLibraryModal open onOpenChange={setLibraryOpen} nodes={nodes} assets={assets} projectId={Number(projectId) || 0} episodeId={episodeId == null ? null : Number(episodeId)} onSelect={(ref) => void chooseReplacement(ref)} />}
    </div>
  </Modal>
}
