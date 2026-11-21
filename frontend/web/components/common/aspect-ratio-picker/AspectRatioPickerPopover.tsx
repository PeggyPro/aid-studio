'use client'

import {
  cloneElement,
  isValidElement,
  type ReactElement,
  type ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState
} from 'react'
import { Popover, type TooltipProps } from 'antd'
import type { AspectRatioOption } from '~/utils/aspectRatioPicker'
import { fitAspectRatioIconSize } from '~/utils/aspectRatioPicker'
import './aspect-ratio-picker.css'

export type { AspectRatioOption }

export function AspectRatioShape({ value, size = 18 }: { value: string; size?: number }) {
  const box = fitAspectRatioIconSize(value, size)
  return (
    <span
      className="aspect-ratio-picker__shape"
      style={{ width: box.width, height: box.height }}
      aria-hidden="true"
    />
  )
}

export interface AspectRatioPickerPopoverProps<T extends string = string> {
  value: T
  options: AspectRatioOption<T>[]
  onChange: (value: T) => void
  disabled?: boolean
  placement?: TooltipProps['placement']
  ariaLabel?: string
  children?: ReactNode
}

export function AspectRatioPickerPopover<T extends string>({
  value,
  options,
  onChange,
  disabled = false,
  placement = 'bottomLeft',
  ariaLabel = '选择比例',
  children
}: AspectRatioPickerPopoverProps<T>) {
  const [open, setOpen] = useState(false)
  const selected = options.find((item) => item.value === value)
  const triggerLabel = selected?.label || value || '比例'

  useEffect(() => {
    if (!open) return undefined
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      setOpen(false)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [open])

  function selectRatio(next: T) {
    if (disabled) return
    onChange(next)
    setOpen(false)
  }

  return (
    <Popover
      trigger="click"
      placement={placement}
      arrow={false}
      open={disabled ? false : open}
      onOpenChange={(next) => {
        if (!disabled) setOpen(next)
      }}
      destroyOnHidden
      getPopupContainer={() => document.body}
      classNames={{ root: 'aspect-ratio-picker-popover' }}
      styles={{
        container: { padding: 0, background: 'transparent' },
        content: { padding: 0 }
      }}
      content={
        <AspectRatioPickerPanel
          value={value}
          options={options}
          disabled={disabled}
          onSelect={selectRatio}
        />
      }
    >
      {renderRatioTrigger(children, open, disabled, ariaLabel, value, triggerLabel)}
    </Popover>
  )
}

function AspectRatioPickerPanel<T extends string>({
  value,
  options,
  disabled,
  onSelect
}: {
  value: T
  options: AspectRatioOption<T>[]
  disabled: boolean
  onSelect: (value: T) => void
}) {
  const rowRef = useRef<HTMLDivElement | null>(null)
  const itemRefs = useRef(new Map<string, HTMLButtonElement>())
  const [hovered, setHovered] = useState<T | null>(null)
  const [highlight, setHighlight] = useState({ left: 0, width: 0, ready: false })
  const activeKey = hovered ?? value

  const measure = useCallback(() => {
    const row = rowRef.current
    const item = itemRefs.current.get(activeKey)
    if (!row || !item) {
      setHighlight((prev) => ({ ...prev, ready: false }))
      return
    }
    setHighlight({
      left: item.offsetLeft,
      width: item.offsetWidth,
      ready: true
    })
  }, [activeKey])

  useLayoutEffect(() => {
    measure()
  }, [measure, options.length])

  useEffect(() => {
    const row = rowRef.current
    if (!row || typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(() => measure())
    observer.observe(row)
    return () => observer.disconnect()
  }, [measure])

  return (
    <div className="aspect-ratio-picker__panel">
      <header className="aspect-ratio-picker__head">比例</header>
      <div
        ref={rowRef}
        className="aspect-ratio-picker__row"
        role="listbox"
        aria-label="选择比例"
        onMouseLeave={() => setHovered(null)}
      >
        <span
          className={`aspect-ratio-picker__highlight${highlight.ready ? ' is-ready' : ''}`}
          style={{ left: highlight.left, width: highlight.width }}
        />
        {options.map((item) => {
          const active = item.value === value
          return (
            <button
              key={item.value}
              type="button"
              role="option"
              aria-selected={active}
              disabled={disabled}
              className={`aspect-ratio-picker__item${active ? ' is-active' : ''}`}
              ref={(node) => {
                if (node) itemRefs.current.set(item.value, node)
                else itemRefs.current.delete(item.value)
              }}
              onMouseEnter={() => setHovered(item.value)}
              onFocus={() => setHovered(item.value)}
              onBlur={() => setHovered(null)}
              onClick={() => onSelect(item.value)}
            >
              <span className="aspect-ratio-picker__icon">
                <AspectRatioShape value={item.value} />
              </span>
              <span className="aspect-ratio-picker__label">{item.label}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

function renderRatioTrigger(
  children: ReactNode,
  open: boolean,
  disabled: boolean,
  ariaLabel: string,
  value: string,
  triggerLabel: string
) {
  if (isValidElement(children)) {
    const trigger = children as ReactElement<{
      className?: string
      disabled?: boolean
      'aria-expanded'?: boolean
    }>
    return cloneElement(trigger, {
      'aria-expanded': open,
      disabled: trigger.props.disabled ?? disabled,
      className: [trigger.props.className, open ? 'is-open' : ''].filter(Boolean).join(' ')
    })
  }
  return (
    <button
      type="button"
      className={`aspect-ratio-picker__trigger${open ? ' is-open' : ''}`}
      aria-label={ariaLabel}
      aria-haspopup="listbox"
      aria-expanded={open}
      disabled={disabled}
    >
      <AspectRatioShape value={value} size={14} />
      <span>{triggerLabel}</span>
    </button>
  )
}

export default AspectRatioPickerPopover
