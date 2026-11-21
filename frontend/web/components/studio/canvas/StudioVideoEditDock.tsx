'use client'

import {
  forwardRef,
  memo,
  useCallback,
  useImperativeHandle,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent
} from 'react'
import {
  ArrowUpOutlined,
  BorderOutlined,
  CloseOutlined,
  LoadingOutlined,
  QuestionCircleOutlined,
  RedoOutlined,
  SyncOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { useStudioVideoOperation } from '@/hooks/studio/useStudioVideoOperation'
import { useStudioCanvasModelsByFunc } from '@/hooks/studio/useStudioCatalog'
import { useCanvasVideoToolQuote } from '@/hooks/studio/useCanvasVideoToolQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { modelSupportsCapability } from '@/utils/modelCapability'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioLayerSplitRect, StudioVideoEditSession } from '@/types/studio'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioId } from '@/utils/studio/studioGraph'
import {
  addStudioVideoSubtitleRegion,
  isStudioVideoSubtitleRectMeaningful,
  normalizeStudioVideoSubtitleRect,
  redoStudioVideoSubtitleSelection,
  resetStudioVideoSubtitleSelection,
  undoStudioVideoSubtitleSelection
} from '@/utils/studio/studioVideoSubtitleSelection'
import { StudioCreditMark } from '@/components/studio/composer/StudioCreditSendButton'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import '@/components/studio/composer/studio-video-tools.css'

export interface StudioVideoEditDockHandle {
  reposition: (
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect,
    previewRect?: StudioAgentDockRect | null
  ) => void
}

export const StudioVideoEditDock = memo(forwardRef<StudioVideoEditDockHandle>(
  function StudioVideoEditDock(_, ref) {
    const toolbarRef = useRef<HTMLDivElement>(null)
    const surfaceRef = useRef<HTMLDivElement>(null)
    const session = useStudioUiStore((state) => state.videoEditSession)
    const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === session?.nodeId))

    const reposition = useCallback((
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect,
      previewRect: StudioAgentDockRect | null = null
    ) => {
      const toolbar = toolbarRef.current
      if (toolbar) {
        const measured = toolbar.getBoundingClientRect()
        const position = placeStudioImageEditToolbar(selectionRect, viewportRect, {
          width: measured.width || 280,
          height: measured.height || 44
        })
        toolbar.style.left = `${position.left}px`
        toolbar.style.top = `${position.top}px`
      }
      const surface = surfaceRef.current
      if (surface && session?.mode === 'region' && previewRect) {
        surface.style.left = `${previewRect.left}px`
        surface.style.top = `${previewRect.top}px`
        surface.style.width = `${previewRect.width}px`
        surface.style.height = `${previewRect.height}px`
        surface.style.visibility = 'visible'
      } else if (surface) {
        surface.style.visibility = 'hidden'
      }
    }, [session?.mode])

    useImperativeHandle(ref, () => ({ reposition }), [reposition])

    if (!session || !node) return null
    return (
      <>
        {session.mode === 'region' ? (
          <div
            ref={surfaceRef}
            className="studio-video-subtitle-surface nodrag nopan nowheel"
            style={{ visibility: 'hidden' }}
          >
            <StudioVideoSubtitleSelectionOverlay session={session} />
          </div>
        ) : null}
        <div
          ref={toolbarRef}
          className="studio-video-edit-dock nodrag nowheel"
          role="toolbar"
          aria-label={session.mode === 'auto' ? '智能去字幕操作栏' : '框选去字幕操作栏'}
          style={{ left: -9999, top: 0 }}
          onPointerDown={(event) => event.stopPropagation()}
          onWheel={(event) => event.stopPropagation()}
        >
          <StudioVideoSubtitleToolbar session={session} />
        </div>
      </>
    )
  }
))

function StudioVideoSubtitleSelectionOverlay({ session }: { session: StudioVideoEditSession }) {
  const patchSession = useStudioUiStore((state) => state.patchVideoEditSession)
  const [draft, setDraft] = useState<{
    pointerId: number
    start: { x: number; y: number }
    end: { x: number; y: number }
  } | null>(null)

  const pointFromEvent = (event: ReactPointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    return {
      x: Math.max(0, Math.min(1, (event.clientX - rect.left) / Math.max(1, rect.width))),
      y: Math.max(0, Math.min(1, (event.clientY - rect.top) / Math.max(1, rect.height)))
    }
  }

  const finish = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!draft || draft.pointerId !== event.pointerId) return
    const nextRect = normalizeStudioVideoSubtitleRect(draft.start, pointFromEvent(event))
    setDraft(null)
    if (!isStudioVideoSubtitleRectMeaningful(nextRect)) return
    const latest = useStudioUiStore.getState().videoEditSession
    if (!latest || latest.nodeId !== session.nodeId || latest.mode !== 'region') return
    const next = addStudioVideoSubtitleRegion(latest, { id: studioId('subtitle-region'), rect: nextRect })
    patchSession(next)
  }

  const draftRect = draft ? normalizeStudioVideoSubtitleRect(draft.start, draft.end) : null
  return (
    <div
      className="studio-video-subtitle-overlay"
      aria-label="在视频中拖拽框选字幕区域"
      onPointerDown={(event) => {
        if (event.button !== 0) return
        event.preventDefault()
        event.stopPropagation()
        event.currentTarget.setPointerCapture(event.pointerId)
        const point = pointFromEvent(event)
        setDraft({ pointerId: event.pointerId, start: point, end: point })
      }}
      onPointerMove={(event) => {
        if (!draft || draft.pointerId !== event.pointerId) return
        setDraft({ ...draft, end: pointFromEvent(event) })
      }}
      onPointerUp={finish}
      onPointerCancel={() => setDraft(null)}
    >
      {session.regions.map((region, index) => (
        <SubtitleRegionBox key={region.id} rect={region.rect} label={index + 1} />
      ))}
      {draftRect ? <SubtitleRegionBox rect={draftRect} draft /> : null}
      {!session.regions.length && !draft ? (
        <span className="studio-video-subtitle-overlay__hint">拖拽框选字幕区域</span>
      ) : null}
    </div>
  )
}

function SubtitleRegionBox({
  rect,
  label,
  draft = false
}: {
  rect: StudioLayerSplitRect
  label?: number
  draft?: boolean
}) {
  return (
    <span
      className={`studio-video-subtitle-overlay__region${draft ? ' is-draft' : ''}`}
      style={{
        left: `${rect.x * 100}%`,
        top: `${rect.y * 100}%`,
        width: `${rect.w * 100}%`,
        height: `${rect.h * 100}%`
      }}
    >
      {label ? <b>{label}</b> : null}
    </span>
  )
}

function StudioVideoSubtitleToolbar({ session }: { session: StudioVideoEditSession }) {
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === session.nodeId))
  const closeSession = useStudioUiStore((state) => state.closeVideoEditSession)
  const patchSession = useStudioUiStore((state) => state.patchVideoEditSession)
  const addNode = useStudioUiStore((state) => state.addNode)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const operation = useStudioVideoOperation()
  const canvasRuntime = useCanvasProjectRuntime()
  const pool = useStudioCanvasModelsByFunc('video_subtitle_remove', String(canvasRuntime?.project?.projectId || ''), Boolean(canvasRuntime))
  const modelCode = pool.data.find((item) => modelSupportsCapability(item, 'video_subtitle_remove'))?.modelCode
  const quote = useCanvasVideoToolQuote(canvasRuntime && node && modelCode &&
    (session.mode === 'auto' || session.regions.length > 0) ? {
      source: node, targetNodeId: '',
      operation: session.mode === 'auto' ? 'REMOVE_SUBTITLE_AUTO' : 'REMOVE_SUBTITLE_REGION',
      label: session.mode === 'auto' ? '智能去字幕' : '框选去字幕',
      modelCode, regions: session.mode === 'region' ? session.regions : undefined
    } : null, canvasRuntime)
  const quotedCredits = studioCanvasQuoteCredits(quote.quote)
  const quoteError = pool.error || quote.error || (quote.quote?.matched === false ? '当前参数没有可用计费规则' : '')
  const submittingRef = useRef(false)

  const applySession = useCallback((next: StudioVideoEditSession) => patchSession(next), [patchSession])

  if (!node) return null
  const submit = async () => {
    if (submittingRef.current || (canvasRuntime && quotedCredits == null)) return
    if (session.mode === 'region' && !session.regions.length) {
      message.warning('请先在视频中框选字幕区域')
      return
    }
    submittingRef.current = true
    try {
      const label = session.mode === 'auto' ? '智能去字幕' : '框选去字幕'
      const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
      const position = placeStudioNodeWithoutOverlap(
        { x: node.position.x + 330, y: node.position.y },
        { width: 320, height: 180 },
        occupied
      )
      const targetId = addNode('video', position, {
        title: label,
        subtitle: `由 ${node.data.title} 发起`,
        zoneId: node.data.zoneId,
        status: 'queued',
        mediaKind: 'video',
        videoPurpose: 'subtitle_removal',
        composerHidden: true,
        storyboardId: node.data.storyboardId,
        videoToolOptions: {
          sourceNodeId: node.id,
          subtitleMode: session.mode,
          subtitleRegions: session.regions
        },
        resultSummary: `${label}处理中`
      })
      addConnection({ source: node.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
      selectNodes([node.id])
      const ok = await operation.run({
        source: node,
        targetNodeId: targetId,
        operation: session.mode === 'auto' ? 'REMOVE_SUBTITLE_AUTO' : 'REMOVE_SUBTITLE_REGION',
        label,
        modelCode,
        regions: session.mode === 'region' ? session.regions : undefined
      })
      if (!ok) return
      closeSession()
      selectNodes([targetId])
      window.requestAnimationFrame(() => {
        window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: targetId } }))
      })
    } finally {
      submittingRef.current = false
    }
  }

  const updateFromStore = (fn: (value: StudioVideoEditSession) => StudioVideoEditSession) => {
    const latest = useStudioUiStore.getState().videoEditSession
    if (!latest || latest.nodeId !== session.nodeId) return
    applySession(fn(latest))
  }

  return (
    <div className={`studio-video-subtitle-toolbar studio-video-subtitle-toolbar--${session.mode}`}>
      <StudioCanvasTooltip title="关闭">
        <button type="button" aria-label="关闭字幕消除" onClick={() => closeSession()}><CloseOutlined /></button>
      </StudioCanvasTooltip>
      <span className="studio-video-subtitle-toolbar__divider" aria-hidden />
      <strong>{session.mode === 'auto' ? '智能擦除' : '框选去字幕'}</strong>
      {session.mode === 'region' ? (
        <>
          <StudioCanvasTooltip title="在视频画面中拖拽框选字幕，可添加多个区域">
            <button type="button" aria-label="框选说明"><QuestionCircleOutlined /></button>
          </StudioCanvasTooltip>
          <button type="button" className="is-active" aria-label="框选工具"><BorderOutlined /></button>
          <StudioCanvasTooltip title="撤销（Ctrl/⌘ Z）">
            <button
              type="button"
              aria-label="撤销框选"
              disabled={!session.historyPast.length}
              onClick={() => updateFromStore(undoStudioVideoSubtitleSelection)}
            ><UndoOutlined /></button>
          </StudioCanvasTooltip>
          <StudioCanvasTooltip title="重做（Ctrl/⌘ Shift Z）">
            <button
              type="button"
              aria-label="重做框选"
              disabled={!session.historyFuture.length}
              onClick={() => updateFromStore(redoStudioVideoSubtitleSelection)}
            ><RedoOutlined /></button>
          </StudioCanvasTooltip>
          <StudioCanvasTooltip title="重置（Ctrl/⌘ Backspace）">
            <button
              type="button"
              aria-label="重置框选"
              disabled={!session.regions.length}
              onClick={() => updateFromStore(resetStudioVideoSubtitleSelection)}
            ><SyncOutlined /></button>
          </StudioCanvasTooltip>
        </>
      ) : null}
      <span className="studio-video-subtitle-toolbar__cost">
        {(!canvasRuntime || quotedCredits != null && !quoteError) ? <><StudioCreditMark /> {canvasRuntime ? quote.quote?.isFree ? '免费' : `预计 ${quotedCredits} 积分` : '按模型报价'}</> : '\u00a0'}
      </span>
      <StudioCanvasTooltip title={session.mode === 'region' && !session.regions.length ? '请先框选字幕区域' : quoteError ? '当前功能暂不可用' : '消除视频中的字幕'}>
        <button
          type="button"
          className="studio-video-subtitle-toolbar__submit"
          aria-label="提交字幕消除"
          disabled={operation.busy || (session.mode === 'region' && !session.regions.length) || Boolean(canvasRuntime && quotedCredits == null)}
          onClick={() => { void submit() }}
        >
          {operation.busy ? <LoadingOutlined spin /> : <ArrowUpOutlined />}
        </button>
      </StudioCanvasTooltip>
    </div>
  )
}

export default StudioVideoEditDock
