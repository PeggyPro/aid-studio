'use client'

import './studio-segmented-tabs.css'

/** Shared segmented navigation for studio popup surfaces. */
export function StudioSegmentedTabs<T extends string>({ options, value, onChange, ariaLabel, className = '', panelId }: {
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
  ariaLabel: string
  className?: string
  panelId?: string
}) {
  return <div className={`studio-segmented-tabs ${className}`} role="tablist" aria-label={ariaLabel}>
    {options.map((option, index) => <button key={option.value} type="button" role="tab"
      id={panelId ? `${panelId}-tab-${option.value}` : undefined} aria-controls={panelId}
      className={value === option.value ? 'is-active' : ''} aria-selected={value === option.value}
      tabIndex={value === option.value ? 0 : -1} onClick={() => onChange(option.value)}
      onKeyDown={event => {
        const next = event.key === 'ArrowRight' ? (index + 1) % options.length
          : event.key === 'ArrowLeft' ? (index + options.length - 1) % options.length
          : event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : -1
        if (next < 0) return
        event.preventDefault()
        onChange(options[next].value)
        event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus()
      }}>{option.label}</button>)}
  </div>
}
