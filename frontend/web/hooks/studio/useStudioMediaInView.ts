'use client'

import { useEffect, useState, type RefObject } from 'react'

type MediaObserver = {
  observer: IntersectionObserver
  targets: Map<Element, () => void>
  visible: Set<Element>
  frame: number | null
}

const observers = new WeakMap<Element, MediaObserver>()
const movingViewports = new WeakSet<Element>()
const MEDIA_REQUESTS_PER_FRAME = 2

function scheduleMediaRequests(root: Element, state: MediaObserver) {
  if (movingViewports.has(root) || state.frame !== null || !state.visible.size) return
  state.frame = requestAnimationFrame(() => {
    state.frame = null
    if (movingViewports.has(root)) return
    let started = 0
    for (const element of state.visible) {
      state.visible.delete(element)
      state.targets.get(element)?.()
      if (++started === MEDIA_REQUESTS_PER_FRAME) break
    }
    scheduleMediaRequests(root, state)
  })
}

export function setStudioMediaViewportMoving(root: Element, moving: boolean) {
  if (moving) {
    movingViewports.add(root)
    return
  }
  movingViewports.delete(root)
  const state = observers.get(root)
  if (state) scheduleMediaRequests(root, state)
}

/** Read new media near the settled viewport. Keep existing previews during gestures. */
export function useStudioMediaInView(host: RefObject<HTMLElement | null>, deferOffscreen: boolean) {
  const [requested, setRequested] = useState(false)
  useEffect(() => {
    const element = host.current
    if (!deferOffscreen || requested || !element) return
    const root = element.closest('.studio-canvas')
    if (!root || typeof IntersectionObserver === 'undefined') {
      const frame = requestAnimationFrame(() => setRequested(true))
      return () => cancelAnimationFrame(frame)
    }
    let state = observers.get(root)
    if (!state) {
      const targets = new Map<Element, () => void>()
      const visible = new Set<Element>()
      const observer = new IntersectionObserver(entries => {
        for (const entry of entries) {
          if (!targets.has(entry.target)) continue
          if (entry.isIntersecting) visible.add(entry.target)
          else visible.delete(entry.target)
        }
        scheduleMediaRequests(root, shared)
      }, { root, rootMargin: '160px' })
      const shared: MediaObserver = { observer, targets, visible, frame: null }
      state = shared
      observers.set(root, shared)
    }
    const { observer, targets, visible } = state
    const stop = () => {
      observer.unobserve(element)
      targets.delete(element)
      visible.delete(element)
    }
    targets.set(element, () => { stop(); setRequested(true) })
    observer.observe(element)
    return stop
  }, [host, deferOffscreen, requested])
  return !deferOffscreen || requested
}
