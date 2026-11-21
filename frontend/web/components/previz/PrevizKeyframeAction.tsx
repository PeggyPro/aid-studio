'use client'
import { PlusOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

export function PrevizKeyframeAction({ label, disabled, onClick }: { label: string; disabled?: boolean; onClick: () => void }) {
  return <StudioCanvasTooltip title={disabled ? '请先解锁对象' : `在当前帧记录${label}`}>
    <button type="button" className="pv-add-keyframe" aria-label={`${label}设置关键帧`} disabled={disabled} onClick={onClick}>
      <PlusOutlined aria-hidden />关键帧
    </button>
  </StudioCanvasTooltip>
}
