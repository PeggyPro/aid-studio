'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ScissorOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  useStore,
  type ConnectionLineComponentProps,
  type EdgeProps
} from '@xyflow/react'
import type { StudioFlowEdge } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { edgeColor } from '@/utils/studio/studioGraph'
import {
  STUDIO_EDGE_WASH_LAYERS as WASH_LAYERS,
  STUDIO_EDGE_MOTES as WASH_MOTES,
  STUDIO_EDGE_WASH_SPACING as WASH_SPACING,
  STUDIO_EDGE_WASH_DURATION as WASH_DURATION,
  studioEdgeAnimationDelay
} from '@/utils/studio/studioEdgeMotion'

const CUT_HOVER_DELAY_MS = 1000
const CUT_HIDE_DELAY_MS = 180

function washStyle(from: number, time: number, zoom: number, duration = WASH_DURATION) {
  // React Flow scales an HTML ancestor of the SVG. vector-effect alone does not
  // cancel that CSS transform: convert distances/widths, never the animation clock.
  return {
    '--wash-from': `${from / zoom}px`,
    '--wash-to': `${(from - WASH_SPACING) / zoom}px`,
    '--wash-duration': `${duration}s`,
    '--wash-delay': studioEdgeAnimationDelay(time, duration)
  } as CSSProperties
}

function StudioEnergyFlowOverlays({
  path,
  color,
  target,
  zoom = 1
}: {
  path: string
  color: string
  target?: { x: number; y: number }
  zoom?: number
}) {
  // A culled/remounted edge rejoins the same page clock rather than flashing an entrance animation.
  const [time] = useState(() => typeof performance === 'undefined' ? 0 : performance.now() / 1000)
  return (
    <g className="studio-energy-edge__stream" style={{ color, '--edge-inverse-zoom': 1 / zoom } as CSSProperties}>
      <path className="studio-energy-edge__thread" d={path} fill="none" />
      {WASH_LAYERS.map(([layer, dash]) => (
        <path
          key={layer}
          className={`studio-energy-edge__wash studio-energy-edge__wash--${layer}`}
          d={path}
          fill="none"
          stroke="currentColor"
          strokeDasharray={`${dash / zoom} ${(WASH_SPACING - dash) / zoom}`}
          style={washStyle(dash, time, zoom)}
        />
      ))}
      {WASH_MOTES.map(({ behind, speed, glimmer }) => (
        <path key={behind} className="studio-energy-edge__mote" d={path} fill="none"
          strokeDasharray={`${.01 / zoom} ${(WASH_SPACING - .01) / zoom}`}
          style={{ ...washStyle(behind, time, zoom, WASH_DURATION / speed),
            '--glimmer-duration': `${glimmer}s`, '--glimmer-delay': studioEdgeAnimationDelay(time, glimmer)
          } as CSSProperties} />
      ))}
      {target ? <g transform={`translate(${target.x} ${target.y}) scale(${1 / zoom})`}>
        <circle className="studio-energy-edge__ripple" r={10} fill="none"
          style={washStyle(0, time, 1)} />
      </g> : null}
    </g>
  )
}

function useStudioEdgeCutButton(enabled: boolean) {
  const [visible, setVisible] = useState(false)
  const showTimerRef = useRef<number | null>(null)
  const hideTimerRef = useRef<number | null>(null)

  const clearTimers = () => {
    if (showTimerRef.current != null) {
      window.clearTimeout(showTimerRef.current)
      showTimerRef.current = null
    }
    if (hideTimerRef.current != null) {
      window.clearTimeout(hideTimerRef.current)
      hideTimerRef.current = null
    }
  }

  useEffect(() => () => clearTimers(), [])

  useEffect(() => {
    if (enabled) return
    clearTimers()
    const resetTimer = window.setTimeout(() => setVisible(false), 0)
    return () => window.clearTimeout(resetTimer)
  }, [enabled])

  const onPointerEnter = () => {
    if (!enabled) return
    if (hideTimerRef.current != null) {
      window.clearTimeout(hideTimerRef.current)
      hideTimerRef.current = null
    }
    if (visible || showTimerRef.current != null) return
    showTimerRef.current = window.setTimeout(() => {
      showTimerRef.current = null
      setVisible(true)
    }, CUT_HOVER_DELAY_MS)
  }

  const onPointerLeave = () => {
    if (showTimerRef.current != null) {
      window.clearTimeout(showTimerRef.current)
      showTimerRef.current = null
    }
    if (!visible) return
    hideTimerRef.current = window.setTimeout(() => {
      hideTimerRef.current = null
      setVisible(false)
    }, CUT_HIDE_DELAY_MS)
  }

  return { visible, onPointerEnter, onPointerLeave }
}

export function StudioEnergyEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
  deletable = true
}: EdgeProps<StudioFlowEdge>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition
  })
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const zoom = useStore(state => state.transform[2])
  const edgeKind = data?.kind ?? 'flow'
  const baseColor = typeof style?.stroke === 'string'
    ? style.stroke
    : studioMode === 'free'
      ? 'var(--studio-edge-base)'
      : edgeColor(edgeKind)
  // 底线统一 edge-base；流光层用主题的 edge-pulse，与底线拉开对比
  const flowColor = studioMode === 'free' ? 'var(--studio-edge-pulse, var(--studio-edge-flow))' : baseColor
  // React Flow puts edge.className on the wrapper <g>, not on Edge props — use data.energy.
  const showEnergy = Boolean(data?.energy)
  const deleteEdges = useStudioUiStore((state) => state.deleteEdges)
  const canCut = studioMode === 'free'
    && deletable !== false
    && !data?.flowManaged
  const { visible, onPointerEnter, onPointerLeave } = useStudioEdgeCutButton(canCut)

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        className="studio-energy-edge__base"
        style={{ ...style, strokeWidth: typeof style?.strokeWidth === 'number' ? style.strokeWidth / zoom : style?.strokeWidth ? `calc(${style.strokeWidth} / ${zoom})` : 1.5 / zoom, strokeDasharray: 'none' }}
      />
      {showEnergy ? (
        <StudioEnergyFlowOverlays path={path} color={flowColor} target={{ x: targetX, y: targetY }} zoom={zoom} />
      ) : null}
      {canCut ? (
        <path
          d={path}
          fill="none"
          stroke="transparent"
          strokeWidth={22 / zoom}
          className="studio-edge-cut-hit nodrag nopan"
          onPointerEnter={onPointerEnter}
          onPointerLeave={onPointerLeave}
        />
      ) : null}
      {canCut && visible ? (
        <EdgeLabelRenderer>
          <div
            className="studio-edge-cut-button-wrap nodrag nopan"
            style={{
              transform: `translate(${labelX}px, ${labelY}px) scale(${1 / zoom}) translate(-50%, -50%)`,
              transformOrigin: '0 0'
            }}
            onPointerEnter={onPointerEnter}
            onPointerLeave={onPointerLeave}
          >
            <StudioCanvasTooltip title="剪断连线">
            <button
              type="button"
              className="studio-edge-cut-button"
              aria-label="剪断连线"
              onClick={(event) => {
                event.stopPropagation()
                deleteEdges([id])
              }}
              onPointerDown={(event) => event.stopPropagation()}
            >
              <ScissorOutlined />
            </button>
            </StudioCanvasTooltip>
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
}

export function StudioEnergyConnectionLine({
  fromX,
  fromY,
  toX,
  toY,
  fromPosition,
  toPosition
}: ConnectionLineComponentProps) {
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const zoom = useStore(state => state.transform[2])
  const baseColor = studioMode === 'free' ? 'var(--studio-edge-base)' : edgeColor('flow')
  const flowColor = studioMode === 'free' ? 'var(--studio-edge-pulse, var(--studio-edge-flow))' : edgeColor('flow')
  const [path] = getBezierPath({
    sourceX: fromX,
    sourceY: fromY,
    targetX: toX,
    targetY: toY,
    sourcePosition: fromPosition,
    targetPosition: toPosition
  })
  return (
    <g className="studio-energy-connection">
      <path
        className="studio-energy-edge__base"
        strokeWidth={1.5 / zoom}
        d={path}
        fill="none"
        stroke={baseColor}
      />
      <StudioEnergyFlowOverlays path={path} color={flowColor} zoom={zoom} />
    </g>
  )
}
