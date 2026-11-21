'use client'

import {
  AppstoreOutlined,
  AudioOutlined,
  CommentOutlined,
  FastForwardOutlined,
  FileTextOutlined,
  PictureOutlined,
  PlaySquareOutlined,
  RightOutlined,
  UserOutlined,
  VideoCameraOutlined
} from '@ant-design/icons'
import { type MouseEvent as ReactMouseEvent, useEffect, useRef } from 'react'
import type { StudioMode } from '@/types/studio'
import {
  STUDIO_CANVAS_FLOW_SHORTCUTS,
  STUDIO_CANVAS_FREE_SHORTCUTS,
  type StudioCanvasEmptyShortcut,
  type StudioCanvasEmptyShortcutAction
} from '@/config/studioCanvasEmptyGuide'
import { emptyClickHintTimeline, playEmptyClickHint } from '@/utils/studio/studioEmptyClickHint'

export type StudioCanvasEmptyGuideMenuMode = 'full' | 'add-only'
export type StudioCanvasEmptyGuideMenuHandler = (
  client: { x: number; y: number },
  mode: StudioCanvasEmptyGuideMenuMode
) => void

const ICONS = {
  'text-to-video': VideoCameraOutlined,
  'image-background': PictureOutlined,
  'first-frame-video': PlaySquareOutlined,
  'audio-to-video': AudioOutlined,
  template: AppstoreOutlined,
  'story-script': FileTextOutlined,
  'character-sheet': UserOutlined,
  'reference-video': VideoCameraOutlined,
  'end-frame-video': FastForwardOutlined
} as const

export interface StudioCanvasEmptyGuideProps {
  mode: StudioMode
  onShortcut: (action: StudioCanvasEmptyShortcutAction) => void
  onOpenChat: () => void
  onCanvasMenu?: StudioCanvasEmptyGuideMenuHandler
}

function forwardCanvasMenu(
  event: ReactMouseEvent,
  onCanvasMenu: StudioCanvasEmptyGuideMenuHandler | undefined,
  mode: StudioCanvasEmptyGuideMenuMode
) {
  event.preventDefault()
  event.stopPropagation()
  onCanvasMenu?.({ x: event.clientX, y: event.clientY }, mode)
}

function EmptyGuideClickHint() {
  const rootRef = useRef<HTMLSpanElement>(null)
  const timeline = emptyClickHintTimeline()

  useEffect(() => {
    const node = rootRef.current
    if (!node) return
    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    let stop = () => {}
    const sync = () => {
      stop()
      stop = motion?.matches ? () => {} : playEmptyClickHint(node)
    }
    sync()
    motion?.addEventListener('change', sync)
    return () => {
      motion?.removeEventListener('change', sync)
      stop()
    }
  }, [])

  return (
    <div className="studio-canvas-empty-guide__hint is-click">
      <span className="studio-canvas-empty-guide__hint-copy">双击画布</span>
      <span
        ref={rootRef}
        className="studio-canvas-empty-guide__click"
        aria-hidden="true"
        data-studio-click-cycle={timeline.cycle}
      >
        {(['inner', 'outer'] as const).map((ring) => (
          <span
            key={ring}
            className={`studio-canvas-empty-guide__ripple is-${ring}`}
            data-studio-click-ripple=""
            data-studio-click-ring={ring}
          />
        ))}
        <svg
          className="studio-canvas-empty-guide__pointer"
          data-studio-click-pointer=""
          viewBox="0 0 32 40"
          width="28"
          height="35"
          fill="none"
        >
          <defs>
            <linearGradient id="studio-empty-pointer-fill" x1="18%" y1="6%" x2="88%" y2="94%">
              <stop offset="0%" stopColor="#8af7ff" />
              <stop offset="38%" stopColor="#3d8dff" />
              <stop offset="72%" stopColor="#6a5cff" />
              <stop offset="100%" stopColor="#ffd36a" />
            </linearGradient>
            <linearGradient id="studio-empty-pointer-shine" x1="20%" y1="0%" x2="55%" y2="70%">
              <stop offset="0%" stopColor="#fff" stopOpacity="0.72" />
              <stop offset="100%" stopColor="#fff" stopOpacity="0" />
            </linearGradient>
          </defs>
          <path
            d="M4.2 2.4 4.2 29.8 11.1 23.4 16.8 37.2 21.8 35 16.3 21.6 26.6 21.2Z"
            fill="url(#studio-empty-pointer-fill)"
            stroke="rgba(255,255,255,.9)"
            strokeLinejoin="round"
            strokeWidth="1.35"
          />
          <path
            d="M6.4 6.2 6.4 24.6 11.2 20.4 14.6 28.6"
            stroke="url(#studio-empty-pointer-shine)"
            strokeLinecap="round"
            strokeWidth="1.2"
          />
        </svg>
      </span>
      <span className="studio-canvas-empty-guide__hint-copy">创建节点</span>
    </div>
  )
}

function ShortcutButton({
  item,
  onClick,
  onCanvasMenu
}: {
  item: StudioCanvasEmptyShortcut
  onClick: () => void
  onCanvasMenu?: StudioCanvasEmptyGuideMenuHandler
}) {
  const Icon = ICONS[item.id as keyof typeof ICONS] ?? AppstoreOutlined
  return (
    <button
      type="button"
      className={`studio-canvas-empty-guide__shortcut is-${item.tone}`}
      onClick={onClick}
      onContextMenu={(event) => forwardCanvasMenu(event, onCanvasMenu, 'full')}
    >
      <span className="studio-canvas-empty-guide__shortcut-icon" aria-hidden="true">
        <Icon />
      </span>
      <span className="studio-canvas-empty-guide__shortcut-label">{item.label}</span>
      {item.badge ? <span className="studio-canvas-empty-guide__shortcut-badge">{item.badge}</span> : null}
    </button>
  )
}

export function StudioCanvasEmptyGuide({
  mode,
  onShortcut,
  onOpenChat,
  onCanvasMenu
}: StudioCanvasEmptyGuideProps) {
  const shortcuts = mode === 'flow' ? STUDIO_CANVAS_FLOW_SHORTCUTS : STUDIO_CANVAS_FREE_SHORTCUTS

  return (
    <div className={`studio-canvas-empty-guide is-${mode}`} data-studio-guide="canvas-empty">
      {mode === 'flow' ? (
        <>
          <button
            type="button"
            className="studio-canvas-empty-guide__chat-banner"
            data-studio-guide="canvas-empty-chat"
            onClick={onOpenChat}
            onContextMenu={(event) => forwardCanvasMenu(event, onCanvasMenu, 'full')}
          >
            <span className="studio-canvas-empty-guide__chat-icon" aria-hidden="true">
              <CommentOutlined />
            </span>
            <div className="studio-canvas-empty-guide__chat-copy">
              <strong>通过右侧对话面板开始创作</strong>
              <p>与剧本 Agent 对话后，故事脚本、分镜与视频节点会实时出现在画布上。</p>
            </div>
            <span className="studio-canvas-empty-guide__chat-action">
              打开对话面板
              <RightOutlined aria-hidden="true" />
            </span>
          </button>
          <div className="studio-canvas-empty-guide__hint">
            <span className="studio-canvas-empty-guide__hint-badge">
              <CommentOutlined aria-hidden="true" />
              对话驱动
            </span>
            <span className="studio-canvas-empty-guide__hint-text">在右侧输入创作要求，流程节点会自动生成</span>
          </div>
        </>
      ) : (
        <>
          <EmptyGuideClickHint />
        </>
      )}

      <div className="studio-canvas-empty-guide__shortcuts" role="group" aria-label={mode === 'flow' ? '流程快捷入口' : '画布快捷入口'}>
        {shortcuts.map((item) => (
          <ShortcutButton
            key={item.id}
            item={item}
            onClick={() => onShortcut(item.action)}
            onCanvasMenu={onCanvasMenu}
          />
        ))}
      </div>
    </div>
  )
}

export default StudioCanvasEmptyGuide
