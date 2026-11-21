'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Checkbox, Modal, Progress } from 'antd'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioPanelSelectTrigger } from '@/components/studio/composer/StudioPanelSelectTrigger'
import { canvasApiReadPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from '@/utils/business/shared'
import type { EnumDictGroup } from '@/types/business-api'
import { directorRecordingDimensions } from '@/utils/studio/directorRecordingResolution'
import './director-recording.css'

type RecordingMode = 'active_camera' | 'director'
const MODES = [
  { key: 'active_camera', label: '机位视角（按机位轨迹或正在预览的分镜）' },
  { key: 'director', label: '导演视角（当前观察角度）' }
]
const modalClass = 'create-flow-modal director-recording-modal'

/** 配置、进度及取消确认共用画布弹窗外壳，录制逻辑仍由监视器负责。 */
export function DirectorRecordingDialogs({ open, recording, duration, progress, mode, resolution, burn, disabledReason,
  onClose, onModeChange, onResolutionChange, onBurnChange, onStart, onAbort
}: {
  open: boolean; recording: boolean; duration: number; progress: number; mode: RecordingMode; resolution: string; burn: boolean
  disabledReason: string; onClose: () => void; onModeChange: (mode: RecordingMode) => void
  onResolutionChange: (resolution: string) => void; onBurnChange: (burn: boolean) => void; onStart: () => void; onAbort: () => void
}) {
  const [menu, setMenu] = useState<'mode' | 'resolution' | null>(null)
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [resolutions, setResolutions] = useState<Array<{ key: string; label: string }>>([])
  const [resolutionError, setResolutionError] = useState('')
  const [revision, setRevision] = useState(0)
  const resolutionRef = useRef(resolution)
  useEffect(() => { resolutionRef.current = resolution }, [resolution])
  useEffect(() => {
    if (!open) return
    let active = true
    void canvasApiReadPost<EnumDictGroup[]>('/api/user/dict/enum/list', { enumTypes: ['DirectorRecordingResolutionEnum'] })
      .then(unwrap).then(groups => {
        const options = groups.find(group => group.enumType === 'DirectorRecordingResolutionEnum')?.items.map(item => {
          directorRecordingDimensions(item.value)
          return { key: item.value, label: item.desc }
        }) ?? []
        if (!options.length) throw new Error('录制清晰度尚未配置')
        if (active) { setResolutionError(''); setResolutions(options); if (!options.some(item => item.key === resolutionRef.current)) onResolutionChange(options[0].key) }
      }).catch(error => { if (active) setResolutionError(error instanceof Error ? error.message : '清晰度读取失败') })
    return () => { active = false }
  }, [open, revision, onResolutionChange])
  const blocked = disabledReason || resolutionError || (!resolutions.some(item => item.key === resolution) ? '正在读取录制清晰度' : '')
  const seconds = Math.min(120, Math.max(0, duration))
  return <>
    <Modal open={open} title="录制白模预演" className={modalClass} centered width={480}
      onCancel={() => { setMenu(null); onClose() }}
      footer={<>
        <Button onClick={() => { setMenu(null); onClose() }}>取消</Button>
        <StudioCanvasTooltip title={blocked || '开始录制白模预演'}>
          <Button type="primary" disabled={Boolean(blocked)} onClick={() => { setMenu(null); setConfirmCancel(false); onStart() }}>开始录制</Button>
        </StudioCanvasTooltip>
      </>}>
      <div className="director-recording-form">
        <p className="director-recording-summary">录制不消耗积分<span>30 fps · {seconds} 秒</span></p>
        {duration > 120 ? <p className="director-recording-hint">本次录制截取前 120 秒。</p> : null}
        <div className="director-recording-field"><span>录制视角</span>
          <StudioCanvasDropdownPopover placement="bottomLeft" autoAdjustOverflow open={open && menu === 'mode'}
            onOpenChange={value => setMenu(value ? 'mode' : null)} ariaLabel="录制视角" items={MODES} activeKey={mode}
            onSelect={key => onModeChange(key as RecordingMode)}
            trigger={<StudioPanelSelectTrigger label={MODES.find(item => item.key === mode)?.label} ariaLabel="录制视角" aria-haspopup="menu" aria-expanded={menu === 'mode'} />} />
        </div>
        <div className="director-recording-field"><span>分辨率</span>
          <StudioCanvasDropdownPopover placement="bottomLeft" autoAdjustOverflow open={open && menu === 'resolution'}
            onOpenChange={value => setMenu(value ? 'resolution' : null)} ariaLabel="录制分辨率" items={resolutions} activeKey={resolution}
            onSelect={onResolutionChange}
            trigger={<StudioPanelSelectTrigger label={resolutions.find(item => item.key === resolution)?.label || '读取清晰度…'} ariaLabel="录制分辨率" aria-haspopup="menu" aria-expanded={menu === 'resolution'} />} />
        </div>
        {disabledReason ? <p className="director-recording-hint" role="status">{disabledReason}</p> : null}
        {resolutionError ? <p role="alert">{resolutionError}<Button size="small" onClick={() => setRevision(value => value + 1)}>重新加载</Button></p> : null}
        <Checkbox checked={burn} onChange={event => onBurnChange(event.target.checked)}>录制时烧录名牌</Checkbox>
        <p className="director-recording-hint">完成后上传到当前画布，作为视频节点保存。</p>
      </div>
    </Modal>
    <Modal open={recording} title="正在录制白模预演…" className={modalClass} centered width={480}
      closable={false} mask={{ closable: false }} keyboard={false}
      footer={<Button danger onClick={() => setConfirmCancel(true)}>取消录制</Button>}>
      <p className="director-recording-hint" role="status">正在按时间轴录制，请保持此页面可见。</p>
      <Progress percent={Math.min(100, Math.max(0, Math.round(progress / Math.max(seconds, 1) * 100)))} />
    </Modal>
    <Modal open={recording && confirmCancel} title="取消录制？" className={modalClass} centered width={420}
      onCancel={() => setConfirmCancel(false)}
      footer={<><Button onClick={() => setConfirmCancel(false)}>继续录制</Button>
        <Button danger type="primary" onClick={() => { setConfirmCancel(false); onAbort() }}>取消录制</Button></>}>
      <p className="director-recording-hint">取消后将丢弃本次尚未保存的录制。</p>
    </Modal>
  </>
}
