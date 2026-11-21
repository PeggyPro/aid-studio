'use client'

import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react'
import { AudioOutlined, CloseOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import './studio-composer-voice.css'

export function StudioComposerVoiceInput({
  visible, active, state, level, disabledReason, onStart, onStop, leading, trailing
}: {
  visible: boolean
  active: boolean
  state: 'idle' | 'requesting' | 'connecting' | 'listening'
  level: number
  disabledReason: string
  onStart: () => void
  onStop: () => void
  /** 语音按钮左侧、同属右侧操作区的控件。聆听时与发送按钮一起退出。 */
  leading?: ReactNode
  /** 语音按钮右侧的发送区。聆听时隐藏。 */
  trailing?: ReactNode
}) {
  const trigger = useRef<HTMLButtonElement>(null)
  const close = useRef<HTMLButtonElement>(null)
  const wasActive = useRef(false)
  useEffect(() => {
    const restore = wasActive.current
    const frame = requestAnimationFrame(() => {
      if (active) close.current?.focus({ preventScroll: true })
      else if (restore) trigger.current?.focus({ preventScroll: true })
    })
    wasActive.current = active
    return () => cancelAnimationFrame(frame)
  }, [active])
  if (!visible) return <>{leading}{trailing}</>
  const status = state === 'requesting' ? '请允许麦克风权限' : state === 'connecting' ? '正在连接语音识别' : '聆听中…'
  return (
    <div className={`studio-composer-voice${active ? ' is-active' : ''}`} onKeyDown={(event) => {
      if (active && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); onStop() }
    }}>
      <div className="studio-composer-voice__normal" inert={active} aria-hidden={active}>
        {leading}
        <StudioCanvasTooltip title={disabledReason || '语音输入'}>
          <button ref={trigger} type="button" className="studio-composer-voice__trigger" aria-label="语音输入"
            disabled={Boolean(disabledReason)} onMouseDown={(event) => event.preventDefault()} onClick={onStart}>
            <AudioOutlined />
          </button>
        </StudioCanvasTooltip>
        {trailing}
      </div>
      <div className="studio-composer-voice__listening" inert={!active} aria-hidden={!active}>
        <StudioCanvasTooltip title={status}>
          <span className="studio-composer-voice__mic"><AudioOutlined /></span>
        </StudioCanvasTooltip>
        <div className="studio-composer-voice__wave" aria-hidden="true">
          {Array.from({ length: 14 }, (_, index) => (
            <i key={index} style={{ '--voice-bar-scale': 0.12 + level * (0.45 + 0.55 * Math.sin((index + 1) * 1.7) ** 2) } as CSSProperties} />
          ))}
        </div>
        <span className="studio-composer-voice__sr" role="status">{active ? status : ''}</span>
        <StudioCanvasTooltip title="结束语音输入，保留文字">
          <button ref={close} type="button" className="studio-composer-voice__close" aria-label="结束语音输入" onClick={onStop}>
            <CloseOutlined />
          </button>
        </StudioCanvasTooltip>
      </div>
    </div>
  )
}
