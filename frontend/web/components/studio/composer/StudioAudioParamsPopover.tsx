'use client'

import { useState } from 'react'
import { ControlOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import type { AudioClientContract } from '@/types/audio-model'
import { audioConstraints } from '@/utils/audioModelContract'
import { projectAudioSettingGroups, studioAudioSettingChipLabel } from '@/utils/studio/studioAudioSettingGroups'
import { StudioCanvasPopover } from '../canvas/StudioCanvasPopover'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioAudioCapabilityFields } from './StudioAudioCapabilityPanel'

export function StudioAudioParamsPopover({ contract, value, errors, onChange, onUpload, disabled }: {
  contract: AudioClientContract; value: Record<string, unknown>; errors: Record<string, string>
  onChange: (next: Record<string, unknown>) => void; onUpload: (path: string, file: File) => Promise<void>; disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const label = studioAudioSettingChipLabel(contract, projectAudioSettingGroups(contract, value, audioConstraints(contract, value)))
  return <StudioCanvasPopover trigger="click" nodePlacement arrow={false} open={!disabled && open} onOpenChange={value => { if (!disabled) setOpen(value) }}
    getPopupContainer={getStudioComposerPopupContainer} classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
    content={<div className="studio-audio-settings studio-params-panel studio-params-panel--audio">
      <StudioAudioCapabilityFields {...{ contract, value, errors, onChange, onUpload, disabled }} />
    </div>}>
    <StudioComposerChip className="studio-composer-chip--audio" icon={<StudioChromeIcon name="skillSettings" fallback={<ControlOutlined />} />} label={label} title={label} aria-label="音频设置" disabled={disabled} />
  </StudioCanvasPopover>
}
