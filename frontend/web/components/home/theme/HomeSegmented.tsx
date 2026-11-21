'use client'

import { useCallback, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

export interface HomeSegmentOption<T extends string> {
  value: T
  label: ReactNode
  adornment?: ReactNode
  busy?: boolean
}

export function HomeSegmented<T extends string>({
  value,
  options,
  onChange,
  ariaLabel,
  className = '',
  disabled = false
}: {
  value: T
  options: readonly HomeSegmentOption<T>[]
  onChange: (value: T) => void
  ariaLabel: string
  className?: string
  disabled?: boolean
}) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const [box, setBox] = useState({ x: 0, w: 0 })

  const measure = useCallback(() => {
    const root = rootRef.current
    if (!root) return
    const current = root.querySelector<HTMLButtonElement>(`[data-seg="${CSS.escape(value)}"]`)
    if (!current) return
    const x = current.offsetLeft
    const w = current.offsetWidth
    setBox((prev) => (prev.x === x && prev.w === w ? prev : { x, w }))
  }, [value])

  useLayoutEffect(() => {
    measure()
    const root = rootRef.current
    if (!root || typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(measure)
    observer.observe(root)
    return () => observer.disconnect()
  }, [measure])

  return (
    <div ref={rootRef} className={`home-seg ${className}`.trim()} role="group" aria-label={ariaLabel}>
      <span className="home-seg__thumb" style={{ transform: `translateX(${box.x}px)`, width: box.w }} />
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          data-seg={option.value}
          aria-pressed={value === option.value}
          aria-busy={option.busy || undefined}
          disabled={disabled}
          className={value === option.value ? 'is-on' : undefined}
          onClick={() => onChange(option.value)}
        >
          {option.label}
          {option.adornment}
        </button>
      ))}
    </div>
  )
}
