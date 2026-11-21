'use client'

import { useEffect, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { LiquidOrb } from '@/components/common/liquid-orb/LiquidOrb'

let slot: HTMLElement | null = null
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function assignSlot(node: HTMLDivElement | null) {
  slot = node
  listeners.forEach((listener) => listener())
}

/** 空状态占位。球体本身挂在工作区上，关闭面板时不卸载 WebGL。 */
export function StudioCanvasEmptyOrbAnchor() {
  return <div ref={assignSlot} className="canvas-agent-empty__orb-anchor" />
}

export function StudioCanvasEmptyOrb() {
  const [keeper, setKeeper] = useState<HTMLDivElement | null>(null)
  const activeSlot = useSyncExternalStore(subscribe, () => slot, () => null)
  const [armed, setArmed] = useState(false)

  useEffect(() => {
    if (activeSlot) setArmed(true)
  }, [activeSlot])

  const target = activeSlot ?? keeper
  return (
    <>
      <div ref={setKeeper} hidden className="studio-canvas-empty-orb-keeper" />
      {target ? createPortal(
        <LiquidOrb
          className="canvas-agent-empty__orb"
          size={48}
          theme="dark"
          mode="listening"
          interactive={false}
          webgl={armed}
          ariaHidden
        />,
        target
      ) : null}
    </>
  )
}
