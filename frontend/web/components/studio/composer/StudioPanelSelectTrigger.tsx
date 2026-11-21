'use client'

import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { CaretDownOutlined } from '@ant-design/icons'

export type StudioPanelSelectSize = 'default' | 'compact'

/** 画布节点参数面板的统一选择器触发器，必须能承接 Popover 注入的 ref / onClick。 */
export const StudioPanelSelectTrigger = forwardRef<HTMLButtonElement, {
  label: ReactNode
  icon?: ReactNode
  ariaLabel?: string
  disabled?: boolean
  size?: StudioPanelSelectSize
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'>>(function StudioPanelSelectTrigger({
  label,
  icon,
  ariaLabel,
  disabled = false,
  size = 'default',
  className,
  type = 'button',
  ...props
}, ref) {
  return (
    <button
      {...props}
      ref={ref}
      type={type}
      className={[
        'studio-image-ai-panel__select',
        size === 'compact' ? 'studio-image-ai-panel__select--compact' : '',
        className
      ].filter(Boolean).join(' ')}
      aria-label={ariaLabel}
      disabled={disabled}
    >
      {icon ? <i className="studio-image-ai-panel__select-icon">{icon}</i> : null}
      <em>{label}</em>
      <CaretDownOutlined aria-hidden />
    </button>
  )
})

export default StudioPanelSelectTrigger
