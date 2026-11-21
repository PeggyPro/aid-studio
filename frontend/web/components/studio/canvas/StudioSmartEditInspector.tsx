'use client'

import { useState } from 'react'
import { InputNumber } from 'antd'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import type { SmartEditCommand, SmartEditRect, SmartEditTextAnim, SmartEditTransitionType } from '@/types/studio'
import type { SmartEditItem } from '@/utils/studio/smartEditCommands'
import { SMART_EDIT_OVERLAY_RECT } from '@/utils/studio/studioSmartEdit'

export function StudioSmartEditInspector({ item, disabled, command, videoIndex, videoCount }: { item?: SmartEditItem; disabled: boolean; command: (command: SmartEditCommand) => void; videoIndex: number | null; videoCount: number }) {
  if (!item) return <aside className="studio-smart-edit-inspector"><strong>片段属性</strong><p>选择片段以调整裁切、音量与效果。拖动片段边缘可裁切，也可在此精确输入。</p></aside>
  const rect = 'rect' in item ? item.rect : 'overlayRect' in item ? item.overlayRect ?? SMART_EDIT_OVERLAY_RECT : undefined
  const setRect = (key: keyof SmartEditRect, value: number) => {
    if (!rect) return
    const next = { ...rect, [key]: value }
    next.x = Math.min(next.x, 1 - next.w); next.y = Math.min(next.y, 1 - next.h)
    command('text' in item ? { type: 'updateText', clipId: item.id, patch: { rect: next } } : { type: 'setOverlayRect', clipId: item.id, rect: next })
  }
  const number = (label: string, value: number, onChange: (value: number) => void, min = 0, max = 86400, step = 0.1, precision = 3) => <label>{label}<InputNumber aria-label={label} size="small" value={value} min={min} max={max} step={step} precision={precision} disabled={disabled} onChange={(next) => { if (next != null && Number.isFinite(next)) onChange(next) }} /></label>
  return <aside className="studio-smart-edit-inspector"><strong>片段属性</strong>
    {'invalid' in item && item.invalid ? <p role="alert">来源失效，请删除此片段并重新添加素材。</p> : null}
    {videoIndex != null
      ? number('顺序', videoIndex + 1, (index) => command({ type: 'reorderVideoClip', clipId: item.id, toIndex: Math.round(index) - 1 }), 1, videoCount, 1, 0)
      : number('时间轴开始（秒）', item.timelineStartSeconds, (time) => command({ type: 'moveClip', clipId: item.id, timelineStartSeconds: time }))}
    {'range' in item ? <>
      {number('源入点（秒）', item.range.startSeconds, (start) => command({ type: 'trimClip', clipId: item.id, range: { ...item.range, startSeconds: start } }), 0, item.range.endSeconds - 0.001)}
      {number('源出点（秒）', item.range.endSeconds, (end) => command({ type: 'trimClip', clipId: item.id, range: { ...item.range, endSeconds: end } }), item.range.startSeconds + 0.001, item.sourceDurationSeconds)}
      {number('音量', item.volume, (volume) => command({ type: 'setVolume', clipId: item.id, volume }), 0, 1, 0.05)}
    </> : <>
      {number('持续时间（秒）', item.durationSeconds, (durationSeconds) => command({ type: 'updateText', clipId: item.id, patch: { durationSeconds } }), 0.04)}
      <label>文字<textarea aria-label="片段文字" disabled={disabled} value={item.text} maxLength={2000} onChange={(event) => command({ type: 'updateText', clipId: item.id, patch: { text: event.target.value } })} /></label>
    </>}
    {'track' in item && item.track === 'video' ? <>
      <SmartEditSelect ariaLabel={'\u8f6c\u573a'} value={item.transitionOut?.type || 'cut'} disabled={disabled}
        options={[[ 'cut', '\u786c\u5207'], ['fade', '\u6de1\u5165\u6de1\u51fa'], ['dissolve', '\u53e0\u5316'], ['wipe', '\u64e6\u9664']]}
        onChange={(value) => command({ type: 'setTransition', clipId: item.id, transitionOut: { type: value as SmartEditTransitionType, durationSeconds: value === 'cut' ? 0 : 0.5 } })} />
      {item.transitionOut?.type !== 'cut' && item.transitionOut ? number('转场时长（秒）', item.transitionOut.durationSeconds, (durationSeconds) => command({ type: 'setTransition', clipId: item.id, transitionOut: { type: item.transitionOut!.type, durationSeconds } }), 0, item.range.endSeconds - item.range.startSeconds) : null}
    </> : null}
    {rect ? <div className="studio-smart-edit-inspector__rect">{(['x', 'y', 'w', 'h'] as const).map((key) => <div key={key}>{number(`画面 ${key}`, rect[key], (value) => setRect(key, value), key === 'w' || key === 'h' ? 0.02 : 0, 1, 0.02)}</div>)}</div> : null}
    {'fontSize' in item ? <>
      {number('字号', item.fontSize, (fontSize) => command({ type: 'updateText', clipId: item.id, patch: { fontSize } }), 8, 200, 1)}
      <label>文字颜色<input type="color" aria-label="文字颜色" value={item.color} disabled={disabled} onChange={(event) => command({ type: 'updateText', clipId: item.id, patch: { color: event.target.value } })} /></label>
      <SmartEditSelect ariaLabel={'\u6587\u5b57\u52a8\u753b'} value={item.animation} disabled={disabled}
        options={[[ 'none', '\u65e0'], ['fade', '\u6de1\u5165\u6de1\u51fa'], ['slide', '\u4f4d\u79fb']]}
        onChange={(value) => command({ type: 'updateText', clipId: item.id, patch: { animation: value as SmartEditTextAnim } })} />
    </> : null}
    <button type="button" disabled={disabled} className="studio-smart-edit-inspector__delete" onClick={() => command({ type: 'removeClip', clipId: item.id })}>删除片段</button>
  </aside>
}


function SmartEditSelect({ ariaLabel, value, options, disabled, onChange }: {
  ariaLabel: string
  value: string
  options: string[][]
  disabled: boolean
  onChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const selected = options.find(([key]) => key === value)
  return <label className="studio-smart-edit-inspector__select-label">{ariaLabel}
    <StudioCanvasDropdownPopover open={open} onOpenChange={setOpen} placement="top" ariaLabel={ariaLabel}
      activeKey={value} items={options.map(([key, label]) => ({ key, label }))} onSelect={onChange}
      trigger={<button type="button" className="studio-smart-edit-inspector__select" disabled={disabled}>{selected?.[1] || value}<span aria-hidden>{'\u2304'}</span></button>} />
  </label>
}
