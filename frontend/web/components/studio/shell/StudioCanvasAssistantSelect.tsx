'use client'

import { useState } from 'react'
import { DownOutlined } from '@ant-design/icons'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'

export function StudioCanvasAssistantSelect({ label, value, options, disabled, loading, onChange }: {
  label: string; value?: string; options: { value: string; label: string }[]
  disabled?: boolean; loading?: boolean; onChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const display = options.find((item) => item.value === value)?.label || label
  const reason = loading ? '正在读取' : !options.length ? '暂无可选项' : disabled ? '请先完成或核实当前操作' : display
  return <StudioCanvasDropdownPopover items={options.map((item) => ({ key: item.value, label: item.label }))} activeKey={value}
    onSelect={onChange} open={open && !disabled && !loading && !!options.length} onOpenChange={setOpen}
    ariaLabel={label} placement="bottomLeft" getPopupContainer={getStudioComposerPopupContainer}
    trigger={<StudioCanvasTooltip title={reason} open={open ? false : undefined}><button type="button" className="flex min-h-9 max-w-full items-center gap-2 rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] px-3 text-xs"
      disabled={disabled || loading || !options.length} aria-label={label + '，当前为' + display} aria-haspopup="menu" aria-expanded={open}>
      <span className="max-w-44 truncate">{loading ? '读取中…' : display}</span><DownOutlined />
    </button></StudioCanvasTooltip>} />
}
