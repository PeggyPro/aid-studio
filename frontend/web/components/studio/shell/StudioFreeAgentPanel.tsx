'use client'

import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type HTMLAttributes,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode
} from 'react'
import { ExpandAltOutlined, PushpinOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import {
  STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH,
  clampStudioAgentDockedWidth,
  clampStudioAgentFloatingRect,
  defaultStudioAgentFloatingRect,
  moveStudioAgentFloatingRect,
  readStudioAgentPanelLayout,
  resizeStudioAgentFloatingRect,
  writeStudioAgentPanelLayout,
  type StudioAgentPanelEdge,
  type StudioAgentPanelLayout,
  type StudioAgentPanelMode,
  type StudioAgentPanelRect,
  type StudioAgentPanelViewport
} from '@/utils/studio/studioAgentPanelLayout'

type HeaderProps = Omit<HTMLAttributes<HTMLElement>, 'children' | 'className'>

export interface StudioFreeAgentPanelRenderProps {
  mode: StudioAgentPanelMode
  headerProps: HeaderProps | undefined
  headerActions: ReactNode
}

interface StudioFreeAgentPanelProps {
  exiting: boolean
  children: (props: StudioFreeAgentPanelRenderProps) => ReactNode
}

type PointerInteraction = {
  kind: 'move' | 'resize' | 'dock-resize'
  pointerId: number
  startClientX: number
  startClientY: number
  startRect: StudioAgentPanelRect
  startDockedWidth: number
  edge?: StudioAgentPanelEdge
}

const RESIZE_EDGES: StudioAgentPanelEdge[] = [
  'left',
  'right',
  'top',
  'bottom',
  'top-left',
  'top-right',
  'bottom-left',
  'bottom-right'
]
const PANEL_MODE_TRANSITION_MS = 260

function initialStudioAgentPanelLayout(): StudioAgentPanelLayout {
  const fallback: StudioAgentPanelLayout = {
    mode: 'docked',
    dockedWidth: STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH,
    floatingRect: {
      x: 24,
      y: 24,
      width: STUDIO_AGENT_PANEL_DEFAULT_DOCKED_WIDTH,
      height: 720
    }
  }
  if (typeof document === 'undefined') return fallback
  const body = document.querySelector('.studio-workspace__body')
  const viewport = body instanceof HTMLElement && body.clientWidth > 0 && body.clientHeight > 0
    ? { width: body.clientWidth, height: body.clientHeight }
    : {
        width: window.innerWidth,
        height: Math.max(1, window.innerHeight - 48)
      }
  return readStudioAgentPanelLayout(viewport)
}

function sameFloatingRect(current: StudioAgentPanelRect, next: StudioAgentPanelRect) {
  return current.x === next.x && current.y === next.y && current.width === next.width && current.height === next.height
}

function pointerTargetIsControl(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest('button, a, input, textarea, select, [role="button"]'))
}

export function StudioFreeAgentPanel({ exiting, children }: StudioFreeAgentPanelProps) {
  const panelRef = useRef<HTMLElement>(null)
  const interactionRef = useRef<PointerInteraction | null>(null)
  const modeTransitionOriginRef = useRef<DOMRect | null>(null)
  const modeTransitionAnimationRef = useRef<Animation | null>(null)
  const [restoredLayout] = useState(initialStudioAgentPanelLayout)
  const [layoutReady, setLayoutReady] = useState(true)
  const [mode, setMode] = useState<StudioAgentPanelMode>(restoredLayout.mode)
  const [dockedWidth, setDockedWidth] = useState(restoredLayout.dockedWidth)
  const [floatingRect, setFloatingRect] = useState<StudioAgentPanelRect>(restoredLayout.floatingRect)

  const readViewport = (): StudioAgentPanelViewport => {
    const body = panelRef.current?.closest('.studio-workspace__body')
    if (body instanceof HTMLElement) return { width: body.clientWidth, height: body.clientHeight }
    return {
      width: typeof window === 'undefined' ? 1440 : window.innerWidth,
      height: typeof window === 'undefined' ? 852 : Math.max(1, window.innerHeight - 48)
    }
  }

  useLayoutEffect(() => {
    const restored = readStudioAgentPanelLayout(readViewport())
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 首帧提交前按真实工作区再夹紧一次，数值不变则不会触发第二次布局。
    setMode((current) => current === restored.mode ? current : restored.mode)
    setDockedWidth((current) => current === restored.dockedWidth ? current : restored.dockedWidth)
    setFloatingRect((current) => sameFloatingRect(current, restored.floatingRect) ? current : restored.floatingRect)
    setLayoutReady(true)
  }, [])

  useEffect(() => {
    if (!layoutReady) return
    writeStudioAgentPanelLayout({ mode, dockedWidth, floatingRect })
  }, [dockedWidth, floatingRect, layoutReady, mode])

  useLayoutEffect(() => {
    const origin = modeTransitionOriginRef.current
    modeTransitionOriginRef.current = null
    const panel = panelRef.current
    const surface = panel?.querySelector<HTMLElement>(':scope > .studio-right-panel__content')
    if (!origin || !panel || !surface || typeof surface.animate !== 'function') return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    const target = panel.getBoundingClientRect()
    if (!target.width || !target.height) return
    modeTransitionAnimationRef.current?.cancel()
    const animation = surface.animate([
      {
        borderRadius: mode === 'floating' ? '0px' : '20px',
        opacity: 0.94,
        transform: `translate3d(${origin.left - target.left}px, ${origin.top - target.top}px, 0) scale(${origin.width / target.width}, ${origin.height / target.height})`,
        transformOrigin: 'top left'
      },
      {
        borderRadius: getComputedStyle(surface).borderRadius,
        opacity: 1,
        transform: 'translate3d(0, 0, 0) scale(1)',
        transformOrigin: 'top left'
      }
    ], {
      duration: PANEL_MODE_TRANSITION_MS,
      easing: 'cubic-bezier(0.22, 0.8, 0.32, 1)'
    })
    modeTransitionAnimationRef.current = animation
    animation.finished.catch(() => undefined).finally(() => {
      if (modeTransitionAnimationRef.current === animation) modeTransitionAnimationRef.current = null
    })
    return () => animation.cancel()
  }, [mode])

  useEffect(() => () => modeTransitionAnimationRef.current?.cancel(), [])

  useLayoutEffect(() => {
    const body = panelRef.current?.closest('.studio-workspace__body')
    if (!(body instanceof HTMLElement) || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      const viewport = { width: body.clientWidth, height: body.clientHeight }
      setDockedWidth((current) => clampStudioAgentDockedWidth(current, viewport.width))
      setFloatingRect((current) => clampStudioAgentFloatingRect(current, viewport))
    })
    observer.observe(body)
    return () => observer.disconnect()
  }, [])

  const updateInteraction = (event: ReactPointerEvent<HTMLElement>) => {
    const interaction = interactionRef.current
    if (!interaction || interaction.pointerId !== event.pointerId) return
    event.preventDefault()
    const dx = event.clientX - interaction.startClientX
    const dy = event.clientY - interaction.startClientY
    const viewport = readViewport()
    if (interaction.kind === 'move') {
      setFloatingRect(moveStudioAgentFloatingRect(interaction.startRect, dx, dy, viewport))
    } else if (interaction.kind === 'dock-resize') {
      setDockedWidth(clampStudioAgentDockedWidth(interaction.startDockedWidth - dx, viewport.width))
    } else if (interaction.edge) {
      setFloatingRect(resizeStudioAgentFloatingRect(interaction.startRect, interaction.edge, dx, dy, viewport))
    }
  }

  const finishInteraction = (event: ReactPointerEvent<HTMLElement>) => {
    if (interactionRef.current?.pointerId !== event.pointerId) return
    interactionRef.current = null
    try {
      event.currentTarget.releasePointerCapture(event.pointerId)
    } catch {
      // 指针可能已由浏览器释放。
    }
  }

  const beginInteraction = (
    event: ReactPointerEvent<HTMLElement>,
    kind: PointerInteraction['kind'],
    edge?: StudioAgentPanelEdge
  ) => {
    if (event.button !== 0 || (kind === 'move' && pointerTargetIsControl(event.target))) return
    event.preventDefault()
    event.stopPropagation()
    interactionRef.current = {
      kind,
      edge,
      pointerId: event.pointerId,
      startClientX: event.clientX,
      startClientY: event.clientY,
      startRect: floatingRect,
      startDockedWidth: dockedWidth
    }
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch {
      // 非主指针或浏览器不支持捕获时，保留本次点击行为。
    }
  }

  const moveByKeyboard = (event: KeyboardEvent<HTMLElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
    event.preventDefault()
    const step = event.shiftKey ? 48 : 16
    setFloatingRect((current) => moveStudioAgentFloatingRect(
      current,
      event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0,
      event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0,
      readViewport()
    ))
  }

  const resizeByKeyboard = (event: KeyboardEvent<HTMLElement>, edge: StudioAgentPanelEdge | 'dock') => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
    event.preventDefault()
    const step = event.shiftKey ? 48 : 16
    if (edge === 'dock') {
      const delta = event.key === 'ArrowLeft' ? step : event.key === 'ArrowRight' ? -step : 0
      if (delta) setDockedWidth((current) => clampStudioAgentDockedWidth(current + delta, readViewport().width))
      return
    }
    const dx = event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0
    const dy = event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0
    setFloatingRect((current) => resizeStudioAgentFloatingRect(current, edge, dx, dy, readViewport()))
  }

  const switchMode = () => {
    const viewport = readViewport()
    modeTransitionOriginRef.current = panelRef.current?.getBoundingClientRect() ?? null
    if (mode === 'docked') {
      setFloatingRect((current) => clampStudioAgentFloatingRect(
        current.width > 0 ? current : defaultStudioAgentFloatingRect(viewport),
        viewport
      ))
      setMode('floating')
    } else {
      setDockedWidth((current) => clampStudioAgentDockedWidth(current, viewport.width))
      setMode('docked')
    }
  }

  const headerProps: HeaderProps | undefined = mode === 'floating'
    ? {
        tabIndex: 0,
        role: 'group',
        'aria-label': 'AI 助手浮窗标题栏，可拖动或使用方向键移动',
        onPointerDown: (event) => beginInteraction(event, 'move'),
        onPointerMove: updateInteraction,
        onPointerUp: finishInteraction,
        onPointerCancel: finishInteraction,
        onKeyDown: moveByKeyboard
      }
    : undefined

  const headerActions = (
    <StudioCanvasTooltip title={mode === 'docked' ? '切换为浮窗' : '停靠到右侧'} placement="bottomRight">
      <button
        type="button"
        className="studio-free-agent-panel__mode-toggle"
        aria-label={mode === 'docked' ? '切换为浮窗' : '停靠到右侧'}
        onClick={switchMode}
      >
        {mode === 'docked' ? <ExpandAltOutlined /> : <PushpinOutlined />}
      </button>
    </StudioCanvasTooltip>
  )

  // eslint-disable-next-line react-hooks/refs -- render 参数只传递事件处理器；其中的 ref 仅在后续指针/键盘事件触发时读取。
  const panelContent = children({ mode, headerProps, headerActions })

  return (
    <aside
      ref={panelRef}
      className={`studio-right-panel studio-right-panel--free studio-right-panel--${mode}${exiting ? ' is-exiting' : ''}`}
      style={mode === 'docked'
        ? { width: dockedWidth }
        : {
            width: floatingRect.width,
            height: floatingRect.height,
            transform: `translate3d(${floatingRect.x}px, ${floatingRect.y}px, 0)`
          }}
      data-studio-guide="conversation"
      data-panel-mode={mode}
      aria-hidden={exiting}
      inert={exiting}
    >
      <div className="studio-right-panel__content">{panelContent}</div>
      {mode === 'docked' ? (
        <div
          className="studio-free-agent-panel__resize-handle is-left is-docked"
          role="separator"
          tabIndex={0}
          aria-label="调整 AI 助手宽度"
          aria-orientation="vertical"
          aria-valuenow={Math.round(dockedWidth)}
          onPointerDown={(event) => beginInteraction(event, 'dock-resize')}
          onPointerMove={updateInteraction}
          onPointerUp={finishInteraction}
          onPointerCancel={finishInteraction}
          onKeyDown={(event) => resizeByKeyboard(event, 'dock')}
        />
      ) : RESIZE_EDGES.map((edge) => {
        const corner = edge.includes('-')
        const widthEdge = edge === 'left' || edge === 'right'
        return (
          <div
            key={edge}
            className={`studio-free-agent-panel__resize-handle is-${edge}${corner ? ' is-corner' : ''}`}
            role="separator"
            tabIndex={0}
            aria-label={corner ? '同时调整 AI 助手宽度和高度' : `调整 AI 助手${widthEdge ? '宽度' : '高度'}`}
            aria-orientation={corner ? undefined : widthEdge ? 'vertical' : 'horizontal'}
            aria-valuenow={Math.round(widthEdge ? floatingRect.width : floatingRect.height)}
            aria-valuetext={corner ? `${Math.round(floatingRect.width)} × ${Math.round(floatingRect.height)}` : undefined}
            onPointerDown={(event) => beginInteraction(event, 'resize', edge)}
            onPointerMove={updateInteraction}
            onPointerUp={finishInteraction}
            onPointerCancel={finishInteraction}
            onKeyDown={(event) => resizeByKeyboard(event, edge)}
          />
        )
      })}
    </aside>
  )
}
