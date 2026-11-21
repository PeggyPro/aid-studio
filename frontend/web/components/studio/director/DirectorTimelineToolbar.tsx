'use client'

import { useId, useState, type ReactNode } from 'react'
import { InputNumber, Slider } from 'antd'
import { AppstoreAddOutlined, CaretRightOutlined, DownOutlined, DownloadOutlined, KeyOutlined, LoadingOutlined, PauseOutlined, SlidersOutlined, UpOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS, StudioCanvasDropdownPopover, type StudioCanvasDropdownItem } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import './director-timeline-toolbar.css'

export type TimelineToolbarAction = StudioCanvasDropdownItem & { run: () => void }

export function DirectorTimelineToolbar({ playing, playhead, playbackEnd, duration, zoom, onPlay, onDuration, onZoom, onClose, shots, lanes, keyframes, actorActions, output, exporting, captureBusy }: {
  playing: boolean; playhead: number; playbackEnd: number; duration: number; zoom: number
  onPlay: () => void; onDuration: (value: number | null) => void; onZoom: (value: number) => void; onClose: () => void
  shots: TimelineToolbarAction[]; lanes: TimelineToolbarAction[]; keyframes: TimelineToolbarAction[]
  actorActions?: TimelineToolbarAction[]; output: TimelineToolbarAction[]; exporting: boolean; captureBusy: boolean
}) {
  const [open, setOpen] = useState<string | null>(null)
  const durationId = useId()
  const menu = (id: string, label: string, icon: ReactNode, items: TimelineToolbarAction[], disabledHint?: string) => (
    <StudioCanvasDropdownPopover key={id} open={open === id} onOpenChange={value => setOpen(value ? id : null)} placement={id === 'output' ? 'topRight' : 'topLeft'} autoAdjustOverflow
      ariaLabel={`时间轴${label}`} items={items} onSelect={key => items.find(item => item.key === key)?.run()}
      trigger={<StudioCanvasTooltip title={disabledHint || label}>
        <button type="button" className={`director-timeline-tool${id === 'output' ? ' is-primary' : ''}`} aria-label={label} aria-haspopup="menu" aria-expanded={open === id} disabled={Boolean(disabledHint)}>
          <span aria-hidden>{icon}</span><span>{label}</span><DownOutlined className="director-timeline-tool__chevron" aria-hidden />
        </button>
      </StudioCanvasTooltip>} />
  )
  return <header className="director-timeline-toolbar" aria-label="时间轴操作">
    <div className="director-timeline-toolbar__playback" role="group" aria-label="播放控制">
      <StudioCanvasTooltip title={playing ? '暂停预演' : '播放预演'}><button type="button" className="director-timeline-tool is-play" aria-label={playing ? '暂停' : '播放'} data-previz-step="playback" onClick={onPlay}>
        {playing ? <PauseOutlined aria-hidden /> : <CaretRightOutlined aria-hidden />}
      </button></StudioCanvasTooltip>
      <span className="director-timeline-toolbar__time" aria-label={`当前 ${playhead.toFixed(2)} 秒，共 ${playbackEnd.toFixed(2)} 秒`}><strong>{playhead.toFixed(2)}</strong><span>/ {playbackEnd.toFixed(2)} s</span></span>
    </div>
    <div className="director-timeline-toolbar__editing" role="group" aria-label="动画编辑">
      {shots.length ? menu('shots', '分镜', <VideoCameraOutlined />, shots) : null}
      {menu('lanes', '添加轨道', <AppstoreAddOutlined />, lanes, lanes.length ? undefined : '全部可用轨道已添加；可先在场景中添加角色或机位')}
      {menu('keyframes', '关键帧', <KeyOutlined />, keyframes)}
      {actorActions ? menu('actor', '角色动作', <CaretRightOutlined />, actorActions) : null}
    </div>
    <div className="director-timeline-toolbar__output" role="group" aria-label="视图与输出">
      <StudioCanvasPopover open={open === 'view'} onOpenChange={value => setOpen(value ? 'view' : null)} trigger="click" placement="topRight" arrow={false} autoAdjustOverflow
        classNames={{ root: STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS }}
        content={<div className="director-timeline-view" role="dialog" aria-label="时间轴视图设置">
          <div className="director-timeline-view__row"><label htmlFor={durationId}>编辑时长</label><InputNumber id={durationId} aria-label="时间轴编辑范围（秒）" min={0.1} step={0.5} value={duration} size="small" suffix="秒" onChange={onDuration} /></div>
          <div className="director-timeline-view__row"><span>刻度缩放</span><Slider aria-label="时间轴刻度缩放" min={24} max={240} step={4} value={zoom} onChange={onZoom} tooltip={{formatter:value=>`${value} px/s`}} /></div>
          <p>编辑时长控制时间轴范围，播放和导出截止到最后一段动画。</p>
        </div>}>
        <StudioCanvasTooltip title="调整编辑时长和时间轴刻度"><button type="button" className="director-timeline-tool" aria-label="时间轴视图" aria-haspopup="dialog" aria-expanded={open === 'view'} data-previz-step="ruler"><SlidersOutlined aria-hidden /><span className="director-timeline-tool__view-label">视图</span></button></StudioCanvasTooltip>
      </StudioCanvasPopover>
      {menu('output', exporting ? '正在导出' : '导出视频', exporting ? <LoadingOutlined spin /> : <DownloadOutlined />, output, captureBusy ? '正在处理录制或导出，请等待完成' : undefined)}
      <StudioCanvasTooltip title="收起时间轴"><button type="button" className="director-timeline-tool is-icon" aria-label="收起时间轴" onClick={onClose}><UpOutlined aria-hidden /></button></StudioCanvasTooltip>
    </div>
  </header>
}
