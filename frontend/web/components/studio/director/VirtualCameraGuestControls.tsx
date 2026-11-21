'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowDownOutlined, ArrowUpOutlined } from '@ant-design/icons'
import { virtualCameraStickInput } from '@/utils/studio/virtualCameraPose'

const directions = {
  ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1]
} as const
const center = { x: 0, y: 0 }

export function VirtualCameraStick({ label, rotated, onChange }: {
  label: string; rotated: boolean; onChange: (x: number, y: number) => void
}) {
  const [point, setPoint] = useState(center)
  const pointer = useRef<{ id: number; x: number; y: number } | null>(null)
  const keys = useRef(new Set<keyof typeof directions>())
  const output = useRef(onChange)
  useEffect(() => { output.current = onChange }, [onChange])

  function update(x: number, y: number) {
    const radius = Math.max(1, Math.hypot(x, y))
    setPoint({ x: x / radius, y: y / radius })
    const input = virtualCameraStickInput(x, y)
    output.current(input.x, input.y)
  }

  const reset = useCallback(() => {
    pointer.current = null
    keys.current.clear()
    setPoint(center)
    output.current(0, 0)
  }, [])

  useEffect(() => {
    window.addEventListener('blur', reset)
    document.addEventListener('visibilitychange', reset)
    return () => {
      window.removeEventListener('blur', reset)
      document.removeEventListener('visibilitychange', reset)
      if (pointer.current || keys.current.size) output.current(0, 0)
    }
  }, [reset])

  function updateKeys() {
    let x = 0, y = 0
    for (const key of keys.current) {
      x += directions[key][0]
      y += directions[key][1]
    }
    update(x, y)
  }

  return <div className="virtual-camera-stick-group">
    <div className="virtual-camera-stick" role="application" aria-label={`${label}摇杆，方向键操控`} tabIndex={0}
      onPointerDown={(event) => {
        if (event.button !== 0 || pointer.current) return
        event.preventDefault()
        keys.current.clear()
        update(0, 0)
        pointer.current = { id: event.pointerId, x: event.clientX, y: event.clientY }
        event.currentTarget.setPointerCapture(event.pointerId)
      }}
      onPointerMove={(event) => {
        const start = pointer.current
        if (!start || start.id !== event.pointerId) return
        const dx = event.clientX - start.x, dy = event.clientY - start.y
        const travel = Math.max(1, event.currentTarget.clientWidth * 0.36)
        update((rotated ? dy : dx) / travel, (rotated ? dx : -dy) / travel)
      }}
      onPointerUp={event => { if (pointer.current?.id === event.pointerId) reset() }}
      onPointerCancel={event => { if (pointer.current?.id === event.pointerId) reset() }}
      onLostPointerCapture={event => { if (pointer.current?.id === event.pointerId) reset() }}
      onBlur={reset}
      onKeyDown={event => {
        const key = event.key as keyof typeof directions
        if (!Object.hasOwn(directions, key) || pointer.current) return
        event.preventDefault()
        if (!keys.current.has(key)) { keys.current.add(key); updateKeys() }
      }}
      onKeyUp={event => { if (keys.current.delete(event.key as keyof typeof directions)) updateKeys() }}>
      <span className="virtual-camera-stick__thumb" style={{ left: `${50 + point.x * 29}%`, top: `${50 - point.y * 29}%` }} />
    </div>
    <span className="virtual-camera-control-caption">{label}</span>
  </div>
}

export function VirtualCameraLift({ direction, onChange }: {
  direction: -1 | 1; onChange: (direction: -1 | 1, active: boolean) => void
}) {
  const pointer = useRef<number | null>(null)
  const key = useRef<string | null>(null)
  const output = useRef(onChange)
  useEffect(() => { output.current = onChange }, [onChange])
  const reset = useCallback(() => {
    if (pointer.current === null && key.current === null) return
    pointer.current = null
    key.current = null
    output.current(direction, false)
  }, [direction])
  useEffect(() => {
    window.addEventListener('blur', reset)
    document.addEventListener('visibilitychange', reset)
    return () => {
      window.removeEventListener('blur', reset)
      document.removeEventListener('visibilitychange', reset)
      reset()
    }
  }, [reset])
  const label = direction === 1 ? '升高' : '降低'
  return <button className="virtual-camera-lift" type="button" aria-label={`机位${label}`}
    onPointerDown={event => {
      if (event.button !== 0 || pointer.current !== null) return
      event.currentTarget.setPointerCapture(event.pointerId)
      pointer.current = event.pointerId
      onChange(direction, true)
    }}
    onPointerUp={event => { if (pointer.current === event.pointerId) reset() }}
    onPointerCancel={event => { if (pointer.current === event.pointerId) reset() }}
    onLostPointerCapture={event => { if (pointer.current === event.pointerId) reset() }}
    onKeyDown={event => {
      if (event.key !== ' ' && event.key !== 'Enter') return
      event.preventDefault()
      if (pointer.current === null && key.current === null) { key.current = event.key; onChange(direction, true) }
    }}
    onKeyUp={event => { if (key.current === event.key) reset() }} onBlur={reset}>
    {direction === 1 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}<span>{label}</span>
  </button>
}
