'use client'

import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'

function subscribeMotion(listener: () => void) {
  const media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', listener)
  return () => media.removeEventListener('change', listener)
}
const reducedMotionSnapshot = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** One entrance per panel opening, independent of capability and session requests. */
export function useCanvasAssistantComposerMotion() {
  const reducedMotion = useSyncExternalStore(subscribeMotion, reducedMotionSnapshot, () => true)
  const [introDone, setIntroDone] = useState(false)
  useEffect(() => {
    const timer = window.setTimeout(() => setIntroDone(true), 3000)
    return () => window.clearTimeout(timer)
  }, [])
  return { awakening: !introDone && !reducedMotion, introDone: introDone || reducedMotion, reducedMotion }
}

export function StudioCanvasAssistantComposerSurface({ awakening, animateResize = true, children }: { awakening: boolean; animateResize?: boolean; children: ReactNode }) {
  const surface = useRef<HTMLDivElement>(null)
  const body = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const shell = surface.current, content = body.current
    if (!animateResize || !shell || !content || typeof ResizeObserver === 'undefined') return
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    let height: number | null = null
    let phase: string | null = null
    let resizing: Animation | undefined
    let revealing: Animation | undefined
    const update = () => {
      const nextHeight = content.getBoundingClientRect().height
      const nextPhase = (content.querySelector<HTMLElement>('[data-assistant-composer-phase]')?.dataset.assistantComposerPhase || '')
        + ':' + (content.querySelector<HTMLElement>('[data-canvas-question-id]')?.dataset.canvasQuestionId || '')
      const animate = !media.matches && typeof shell.animate === 'function'
      if (height !== nextHeight || !animate) {
        // Read the currently painted size before cancellation, so interrupted changes continue smoothly.
        const from = shell.getBoundingClientRect().height
        resizing?.cancel()
        shell.style.height = `${nextHeight}px`
        shell.removeAttribute('data-resizing')
        if (height !== null && animate && Math.abs(from - nextHeight) > 1) {
          shell.setAttribute('data-resizing', '')
          const animation = shell.animate([{ height: `${from}px` }, { height: `${nextHeight}px` }],
            { duration: 300, easing: 'cubic-bezier(.22,1,.36,1)' })
          resizing = animation
          animation.onfinish = () => { if (resizing === animation) shell.removeAttribute('data-resizing') }
        }
        height = nextHeight
      }
      if (!animate) revealing?.cancel()
      else if (phase !== null && phase !== nextPhase) {
        revealing?.cancel()
        revealing = content.animate([{ opacity: .35, transform: 'translateY(5px)' }, { opacity: 1, transform: 'translateY(0)' }],
          { duration: 240, easing: 'cubic-bezier(.22,1,.36,1)' })
      }
      phase = nextPhase
    }
    update()
    const resizeObserver = new ResizeObserver(update)
    resizeObserver.observe(content)
    const phaseObserver = new MutationObserver(update)
    phaseObserver.observe(content, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['data-assistant-composer-phase', 'data-canvas-question-id'] })
    media.addEventListener('change', update)
    return () => {
      resizeObserver.disconnect(); phaseObserver.disconnect(); media.removeEventListener('change', update)
      resizing?.cancel(); revealing?.cancel()
      shell.style.removeProperty('height'); shell.removeAttribute('data-resizing')
    }
  }, [animateResize])
  return <div ref={surface} className={`canvas-assistant-composer-surface${awakening ? ' is-awakening' : ''}`}>
    {awakening ? <span className="canvas-agent-composer__glow" aria-hidden="true">
      <span className="canvas-agent-composer__glow-halo" />
      <span className="canvas-agent-composer__glow-aura" />
      <span className="canvas-agent-composer__glow-shine" />
      <span className="canvas-agent-composer__glow-ring" />
    </span> : null}
    <div ref={body} className="canvas-assistant-composer-surface__body">{children}</div>
  </div>
}
