'use client'

import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { DownOutlined } from '@ant-design/icons'

export interface StudioComposerChipProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon?: ReactNode
  label: ReactNode
  arrow?: boolean
  active?: boolean
}

export const StudioComposerChip = forwardRef<HTMLButtonElement, StudioComposerChipProps>(
  function StudioComposerChip({ icon, label, arrow = true, active = false, className, ...props }, ref) {
    return (
      <button
        ref={ref}
        type="button"
        aria-label={typeof label === 'string' ? label : undefined}
        className={[
          'studio-composer-chip',
          active ? 'is-active' : '',
          className
        ].filter(Boolean).join(' ')}
        {...props}
      >
        {icon ? <i>{icon}</i> : null}
        <span className="studio-composer-chip__label">{label}</span>
        {arrow ? <DownOutlined className="studio-composer-chip__arrow" aria-hidden /> : null}
      </button>
    )
  }
)
