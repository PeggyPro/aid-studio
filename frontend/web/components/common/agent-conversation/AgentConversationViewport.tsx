'use client'

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  type ReactNode,
  type UIEvent
} from 'react'
import { conversationRevealDuration, conversationRevealOffset } from './agentConversationReveal'
import './agent-conversation-viewport.css'

const STICK_TO_LATEST_DISTANCE_PX = 72
const LOAD_OLDER_THRESHOLD_PX = 48

export interface AgentConversationViewportHandle {
  scrollToLatest: (behavior?: ScrollBehavior) => void
}

interface AgentConversationViewportProps {
  open?: boolean
  className?: string
  children: ReactNode
  updateToken: string | number
  scopeKey?: string | number
  initialLoading?: boolean
  hasOlder?: boolean
  loadingOlder?: boolean
  onLoadOlder?: () => Promise<unknown> | void
  ariaLive?: 'off' | 'polite' | 'assertive'
  /** Opt in for dialogue cards whose asynchronous content changes without a message event. */
  followContentResize?: boolean
  revealHistory?: boolean
}

/** 对话区统一滚动行为：默认跟随最新消息，用户上翻后停止抢夺滚动位置。 */
export const AgentConversationViewport = forwardRef<
  AgentConversationViewportHandle,
  AgentConversationViewportProps
>(function AgentConversationViewport(
  {
    open = true,
    className = '',
    children,
    updateToken,
    scopeKey = 'default',
    initialLoading = false,
    hasOlder = false,
    loadingOlder = false,
    onLoadOlder,
    ariaLive = 'off',
    followContentResize = false,
    revealHistory = true
  },
  forwardedRef
) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const contentRef = useRef<HTMLDivElement | null>(null)
  const initializedRef = useRef(false)
  const stickToLatestRef = useRef(true)
  const loadingOlderRef = useRef(false)
  const loadRequestVersionRef = useRef(0)
  const activeScopeKeyRef = useRef<string | number>(scopeKey)
  const revealGenerationRef = useRef(0)
  const revealingRef = useRef(false)

  const cancelReveal = useCallback(() => {
    revealGenerationRef.current += 1
    revealingRef.current = false
  }, [])

  const startReveal = useCallback((root: HTMLDivElement) => {
    revealGenerationRef.current += 1
    const generation = revealGenerationRef.current
    const maxScroll = () => Math.max(0, root.scrollHeight - root.clientHeight)
    const reducedMotion = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const duration = conversationRevealDuration(maxScroll(), reducedMotion)
    if (duration === 0) {
      revealingRef.current = false
      root.scrollTop = root.scrollHeight
      return
    }
    revealingRef.current = true
    root.scrollTop = 0
    const started = performance.now()
    const tick = (now: number) => {
      if (revealGenerationRef.current !== generation || rootRef.current !== root) return
      const offset = conversationRevealOffset(now - started, duration, maxScroll())
      root.scrollTop = offset
      if (now - started < duration) window.requestAnimationFrame(tick)
      else revealingRef.current = false
    }
    window.requestAnimationFrame(tick)
  }, [])

  const scrollToLatest = useCallback((behavior: ScrollBehavior = 'auto') => {
    cancelReveal()
    stickToLatestRef.current = true
    window.requestAnimationFrame(() => {
      const root = rootRef.current
      if (!root) return
      root.scrollTo({ top: root.scrollHeight, behavior })
    })
  }, [cancelReveal])

  useImperativeHandle(forwardedRef, () => ({ scrollToLatest }), [scrollToLatest])

  useEffect(() => {
    if (open) return
    cancelReveal()
    initializedRef.current = false
    stickToLatestRef.current = true
    loadingOlderRef.current = false
    loadRequestVersionRef.current += 1
  }, [cancelReveal, open])

  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    const interrupt = () => { if (revealingRef.current) cancelReveal() }
    root.addEventListener('wheel', interrupt, { passive: true })
    root.addEventListener('touchstart', interrupt, { passive: true })
    root.addEventListener('pointerdown', interrupt)
    return () => {
      root.removeEventListener('wheel', interrupt)
      root.removeEventListener('touchstart', interrupt)
      root.removeEventListener('pointerdown', interrupt)
    }
  }, [cancelReveal])

  useLayoutEffect(() => {
    if (!Object.is(activeScopeKeyRef.current, scopeKey)) {
      activeScopeKeyRef.current = scopeKey
      cancelReveal()
      initializedRef.current = false
      stickToLatestRef.current = true
      loadingOlderRef.current = false
      loadRequestVersionRef.current += 1
    }
    if (!open || initialLoading) return
    const root = rootRef.current
    if (!root) return
    if (!initializedRef.current) {
      initializedRef.current = true
      stickToLatestRef.current = true
      if (revealHistory) startReveal(root)
      else root.scrollTop = root.scrollHeight
      return
    }
    if (revealingRef.current) return
    if (stickToLatestRef.current && !loadingOlderRef.current) {
      root.scrollTop = root.scrollHeight
    }
  }, [cancelReveal, initialLoading, open, revealHistory, scopeKey, startReveal, updateToken])

  useLayoutEffect(() => {
    const root = rootRef.current, content = contentRef.current
    if (!followContentResize || !open || !root || !content) return
    const observer = new ResizeObserver(() => {
      if (!revealingRef.current && !initialLoading && initializedRef.current && stickToLatestRef.current && !loadingOlderRef.current) {
        root.scrollTop = root.scrollHeight
      }
    })
    observer.observe(content)
    observer.observe(root)
    return () => observer.disconnect()
  }, [followContentResize, initialLoading, open, scopeKey])

  const loadOlder = useCallback(async () => {
    const root = rootRef.current
    if (!root || !hasOlder || loadingOlder || loadingOlderRef.current || !onLoadOlder) return
    loadingOlderRef.current = true
    stickToLatestRef.current = false
    const requestVersion = ++loadRequestVersionRef.current
    const requestedScopeKey = scopeKey
    const previousHeight = root.scrollHeight
    const previousTop = root.scrollTop
    try {
      await onLoadOlder()
    } catch {
      // 数据层负责展示错误；滚动容器始终恢复锚点并允许用户再次触发加载。
    } finally {
      window.requestAnimationFrame(() => {
        if (loadRequestVersionRef.current !== requestVersion) return
        const currentRoot = rootRef.current
        if (
          currentRoot === root &&
          Object.is(activeScopeKeyRef.current, requestedScopeKey)
        ) {
          const addedHeight = currentRoot.scrollHeight - previousHeight
          currentRoot.scrollTop = Math.max(0, previousTop + addedHeight)
        }
        loadingOlderRef.current = false
      })
    }
  }, [hasOlder, loadingOlder, onLoadOlder, scopeKey])

  function handleScroll(event: UIEvent<HTMLDivElement>) {
    if (revealingRef.current) return
    const root = event.currentTarget
    const distanceToLatest = root.scrollHeight - root.scrollTop - root.clientHeight
    stickToLatestRef.current = distanceToLatest <= STICK_TO_LATEST_DISTANCE_PX
    if (root.scrollTop <= LOAD_OLDER_THRESHOLD_PX) void loadOlder()
  }

  return (
    <div
      ref={rootRef}
      className={`agent-conversation-viewport${className ? ` ${className}` : ''}`}
      aria-live={ariaLive}
      onScroll={handleScroll}
    >
      {hasOlder || loadingOlder ? (
        <div className="agent-conversation-viewport__history" role={loadingOlder ? 'status' : undefined}>
          {loadingOlder ? '正在读取更早对话…' : '继续上拉查看更早对话'}
        </div>
      ) : null}
      {followContentResize ? <div ref={contentRef} className="agent-conversation-viewport__content">{children}</div> : children}
    </div>
  )
})

export default AgentConversationViewport
