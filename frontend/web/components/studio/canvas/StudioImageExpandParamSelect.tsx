'use client'

import { useState } from 'react'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'

/** Image edit settings use the shared canvas popover instead of a browser-native select. */
export function StudioImageExpandParamSelect({ value, options, onChange, ariaLabel }: {
  value: string
  options: Array<{ value: string; label: string }>
  onChange: (value: string) => void
  ariaLabel: string
}) {
  const [open, setOpen] = useState(false)
  const label = options.find((item) => item.value === value)?.label || value
  return <StudioCanvasDropdownPopover
    open={open}
    onOpenChange={setOpen}
    placement="top"
    ariaLabel={ariaLabel}
    activeKey={value}
    items={options.map((item) => ({ key: item.value, label: item.label }))}
    onSelect={onChange}
    trigger={<StudioComposerChip label={label} active={open} />}
  />
}
