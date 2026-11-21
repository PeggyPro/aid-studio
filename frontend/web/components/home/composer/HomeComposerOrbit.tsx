'use client'

import { useEffect, useRef } from 'react'
import { watchHomeMotion } from '../theme/watchHomeMotion'

export default function HomeComposerOrbit({ active = true }: { active?: boolean }) {
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const element = ref.current
    if (!element || !active) return
    const stop = watchHomeMotion(element, (visible) => {
      element.toggleAttribute('data-running', visible)
    })
    return () => {
      stop()
      element.removeAttribute('data-running')
    }
  }, [active])

  return <span ref={ref} className="home-composer-orbit" aria-hidden="true" />
}
