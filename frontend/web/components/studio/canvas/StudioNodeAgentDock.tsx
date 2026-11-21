'use client'

import { forwardRef, memo, useCallback, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  placeStudioNodeAgentDock,
  placeExpandedStudioNodeAgentDock,
  placeVisibleStudioNodeAgentDock,
  placeStudioNodeCreationDock,
  placeCenteredStudioNodeComposer,
  resolveStudioNodeAgentDockViewportLayout,
  resolveStudioNodeAgentDockSize,
  STUDIO_NODE_AGENT_DOCK_RESTING_HEIGHT_PX,
  type StudioAgentDockRect
} from '@/utils/studio/studioNodeAgentDockLayout'
import { realignStudioComposerPopups } from '@/utils/studio/studioComposerPopupAlign'
import { StudioNodeComposer } from '@/components/studio/composer/StudioNodeComposer'
import { StudioComposerPresentationContext } from '@/components/studio/composer/studioComposerPresentationContext'
import { StudioImageHdPanel } from '@/components/studio/composer/StudioImageHdPanel'
import { StudioVideoHdPanel } from '@/components/studio/composer/StudioVideoHdPanel'
import { StudioVideoDepthPanel } from '@/components/studio/composer/StudioVideoDepthPanel'
import { StudioAudioEditPanel } from '@/components/studio/composer/StudioAudioEditPanel'
import { useStudioAudioEditor } from '@/stores/studioAudioEditor'
import { useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { StudioNodeImageEmotionDock } from '@/components/studio/composer/StudioNodeImageEmotionDock'
import { StudioNodeImageLightingDock } from '@/components/studio/composer/StudioNodeImageLightingDock'
import { StudioNodeImageMultiAngleDock } from '@/components/studio/composer/StudioNodeImageMultiAngleDock'
import { shouldHideStudioNodeComposer } from '@/utils/studio/studioComposerAvailability'
import '@/components/home/composer/home-composer-flow.css'

export interface StudioNodeAgentDockHandle {
  reserveAbove: (nodeId: string, rect: StudioAgentDockRect | null) => void
  reposition: (
    nodeId: string,
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect
  ) => void
}

export const StudioNodeAgentDock = memo(forwardRef<StudioNodeAgentDockHandle>(
  function StudioNodeAgentDock(_, ref) {
    const elementRef = useRef<HTMLDivElement>(null)
    const overlaysRef = useRef<HTMLDivElement>(null)
    const wasCentered = useRef(false)
    const timelineSpaceRef = useRef<{ element: HTMLElement; height: number } | null>(null)
    const reservedAboveRef = useRef<{ nodeId: string; rect: StudioAgentDockRect | null } | null>(null)
    const lastRectsRef = useRef<{
      nodeId: string
      selection: StudioAgentDockRect
      viewport: StudioAgentDockRect
    } | null>(null)
    const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === state.activeNodeId))
    const activeNodeIdRef = useRef(node?.id ?? null)
    activeNodeIdRef.current = node?.id ?? null
    const emotionSession = useStudioUiStore((state) => state.emotionSession)
    const showEmotion = Boolean(node && emotionSession?.nodeId === node.id && emotionSession.phase === 'editing')
    const imageLightingNodeId = useStudioUiStore((state) => state.imageLightingNodeId)
    const imageMultiAngleNodeId = useStudioUiStore((state) => state.imageMultiAngleNodeId)
    const imageEditSession = useStudioUiStore((state) => state.imageEditSession)
    const videoEditSession = useStudioUiStore((state) => state.videoEditSession)
    const touchEditSession = useStudioUiStore((state) => state.touchEditSession)
    const layerSplitSession = useStudioUiStore((state) => state.layerSplitSession)
    const studioMode = useStudioUiStore((state) => state.studioMode)
    const scopeKey = useStudioUiStore((state) => state.scopeKey)
    const smartEditOpen = useStudioSmartEdit((state) => state.scopeKey === scopeKey && Boolean(state.nodeId))
    const audioSession = useStudioAudioEditor((state) => state.session)
    const sourceAudioEditor = Boolean(node && audioSession?.sourceNodeId === node.id && audioSession.scopeKey === scopeKey && audioSession.sourceAudioUrl === node.data.mediaUrl)
    const audioEdit = sourceAudioEditor ? audioSession : node?.data.audioEdit
    const showAudio = Boolean(studioMode === 'free' && node?.data.kind === 'voice' && audioEdit && (sourceAudioEditor || !node.data.composerHidden))
    const [flowing, setFlowing] = useState(false)
    const [expandOnly, setExpandOnly] = useState(false)
    const showLighting = Boolean(node && imageLightingNodeId === node.id && node.data.kind === 'image')
    const showMultiAngle = Boolean(node && imageMultiAngleNodeId === node.id && node.data.kind === 'image')
    const showHd = Boolean(node && node.data.kind === 'image' && node.data.imagePurpose === 'hd' && !node.data.mediaUrl)
    const showVideoHd = Boolean(node && node.data.kind === 'video' && node.data.videoPurpose === 'hd' && !node.data.mediaUrl)
    const showVideoDepth = Boolean(node && node.data.kind === 'video' && node.data.videoPurpose === 'depth_capture' && !node.data.mediaUrl)
    const dockMode = showEmotion ? 'emotion' : showAudio ? 'audio' : showVideoDepth ? 'videoDepth' : showMultiAngle
      ? 'multiAngle'
      : showLighting
        ? 'lighting'
        : showHd || showVideoHd
          ? 'hd'
          : 'composer'
    const hideComposer = Boolean(
      smartEditOpen || node?.data.kind === 'style_library'
      || shouldHideStudioNodeComposer(node, dockMode, studioMode)
      || (emotionSession?.nodeId === node?.id && emotionSession?.phase === 'picking')
      || (studioMode === 'free' && node?.data.kind === 'voice' && Boolean(node.data.mediaUrl) && !showAudio)
      || (node?.data.videoPurpose === 'depth_capture' && Boolean(node.data.mediaUrl))
      || (imageEditSession && imageEditSession.nodeId === node?.id)
      || (videoEditSession && videoEditSession.nodeId === node?.id)
      || (touchEditSession && touchEditSession.nodeId === node?.id)
      || (layerSplitSession && layerSplitSession.nodeId === node?.id)
    )
    const presentationKey = JSON.stringify([scopeKey, node?.id, studioMode, dockMode, hideComposer])
    const [presentation, setPresentation] = useState(() => ({ key: presentationKey, centered: false, promptSettling: false }))
    if (presentation.key !== presentationKey) setPresentation({ key: presentationKey, centered: false, promptSettling: false })
    const centerEnabled = studioMode === 'free' && dockMode === 'composer' && !node?.data.flowBinding && !hideComposer
    const centered = centerEnabled && presentation.key === presentationKey && presentation.centered
    const toggleCentered = useCallback(() => setPresentation(current => {
      const nextCentered = current.key === presentationKey ? !current.centered : true
      return { key: presentationKey, centered: nextCentered, promptSettling: current.centered && !nextCentered }
    }), [presentationKey])
    const restore = useCallback(() => setPresentation(current => (
      current.centered ? { ...current, centered: false, promptSettling: true } : current
    )), [])
    const composerPresentation = useMemo(() => centerEnabled ? {
      centered, expandOnly: !centered && expandOnly, toggleCentered, getOverlayContainer: () => overlaysRef.current
    } : null, [centerEnabled, centered, expandOnly, toggleCentered])
    const dockLabel = showEmotion ? '情绪调节面板' : showAudio ? '音频编辑面板' : showVideoDepth ? '深度动作捕捉面板' : showMultiAngle
      ? '节点多角度面板'
      : showLighting
        ? '节点打光面板'
        : showHd || showVideoHd
          ? showVideoHd ? '视频高清面板' : '高清放大面板'
          : '节点 Agent 输入框'

    const applyPosition = useCallback((
      nodeId: string,
      selectionRect: StudioAgentDockRect,
      viewportRect: StudioAgentDockRect
    ) => {
      if (activeNodeIdRef.current !== nodeId) return
      lastRectsRef.current = { nodeId, selection: selectionRect, viewport: viewportRect }
      const element = elementRef.current
      if (!element) return
      const measured = element.getBoundingClientRect()
      const timeline = centerEnabled ? element.querySelector<HTMLElement>('.studio-video-operation-composer > .studio-video-clip') : null
      if (timelineSpaceRef.current?.element !== timeline) timelineSpaceRef.current = null
      const timelineHeight = timeline?.getBoundingClientRect().height ?? 0
      if (timeline && timelineHeight > 0) {
        const gap = timeline.parentElement ? Number.parseFloat(getComputedStyle(timeline.parentElement).rowGap) || 0 : 0
        timelineSpaceRef.current = { element: timeline, height: timelineHeight + gap }
      }
      // 恢复模式隐藏时间轴，仍保留它的正常占用，避免测量结果为零后反复切换布局。
      const companionHeight = timelineSpaceRef.current?.height ?? 0
      const size = resolveStudioNodeAgentDockSize(measured, dockMode)
      const expanded = dockMode === 'composer' && Boolean(element.querySelector('.studio-composer.is-expanded'))
      const reservedAbove = reservedAboveRef.current?.nodeId === nodeId ? reservedAboveRef.current.rect : null
      const position = centered ? placeCenteredStudioNodeComposer(viewportRect) : studioMode === 'free'
        ? dockMode === 'composer' || dockMode === 'hd' || dockMode === 'videoDepth'
          ? placeStudioNodeCreationDock(
              selectionRect,
              viewportRect,
              expanded ? { ...size, height: measured.height } : size,
              { expanded, constrainAvailableHeight: dockMode === 'composer', keepRecoveryEntryVisible: centerEnabled }
            )
          : placeVisibleStudioNodeAgentDock(selectionRect, viewportRect, size, reservedAbove)
        : expanded
          ? placeExpandedStudioNodeAgentDock(selectionRect, viewportRect, measured)
          : placeStudioNodeAgentDock(selectionRect, viewportRect, size)
      element.style.left = `${position.left}px`
      element.style.top = `${position.top}px`
      element.style.width = `${position.width}px`
      if (centered) element.style.setProperty('--studio-node-composer-centered-height', `${position.height}px`)
      else element.style.removeProperty('--studio-node-composer-centered-height')
      const availableHeight = 'availableHeight' in position && typeof position.availableHeight === 'number'
        ? position.availableHeight
        : Math.max(dockMode === 'composer' ? STUDIO_NODE_AGENT_DOCK_RESTING_HEIGHT_PX : 140, viewportRect.bottom - position.top - 16)
      element.style.setProperty('--studio-node-agent-available-height', `${availableHeight}px`)
      const viewportLayout = centerEnabled && !centered && 'availableHeight' in position
        ? resolveStudioNodeAgentDockViewportLayout(availableHeight, position.height, companionHeight)
        : null
      const previousViewportLayout = element.dataset.viewportLayout
      if (viewportLayout) element.dataset.viewportLayout = viewportLayout
      else delete element.dataset.viewportLayout
      setExpandOnly(viewportLayout === 'expand-only')
      if (viewportLayout === 'expand-only' && previousViewportLayout !== viewportLayout) {
        const activeElement = document.activeElement
        if (activeElement instanceof HTMLElement && (element.contains(activeElement) || overlaysRef.current?.contains(activeElement))) {
          element.querySelector<HTMLButtonElement>('[aria-label="放大输入面板"]')?.focus({ preventScroll: true })
        }
      }
      element.style.visibility = 'visible'
      element.style.pointerEvents = 'auto'
      element.dataset.anchorNodeId = nodeId
      element.dataset.placement = position.placement
      realignStudioComposerPopups()
    }, [centerEnabled, centered, dockMode, studioMode])

    useImperativeHandle(ref, () => ({
      reserveAbove(nodeId, rect) {
        reservedAboveRef.current = { nodeId, rect }
        const last = lastRectsRef.current
        if (last?.nodeId === nodeId) applyPosition(nodeId, last.selection, last.viewport)
      },
      reposition(nodeId, selectionRect, viewportRect) {
        applyPosition(nodeId, selectionRect, viewportRect)
      }
    }), [applyPosition])

    useLayoutEffect(() => {
      const element = elementRef.current
      if (!element) return
      if (element.dataset.anchorNodeId === node?.id) return
      lastRectsRef.current = null
      element.style.visibility = 'hidden'
      element.style.pointerEvents = 'none'
      delete element.dataset.anchorNodeId
    }, [node?.id])

    useLayoutEffect(() => {
      const element = elementRef.current
      if (!element || typeof ResizeObserver === 'undefined') return
      const observer = new ResizeObserver(() => {
        const last = lastRectsRef.current
        if (!last) return
        applyPosition(last.nodeId, last.selection, last.viewport)
      })
      observer.observe(element)
      return () => observer.disconnect()
    }, [applyPosition, hideComposer, node?.id])

    useLayoutEffect(() => {
      const last = lastRectsRef.current
      if (!last) return
      applyPosition(last.nodeId, last.selection, last.viewport)
    }, [applyPosition])

    useLayoutEffect(() => {
      if (!presentation.promptSettling) return
      const frame = requestAnimationFrame(() => {
        setPresentation(current => current.promptSettling ? { ...current, promptSettling: false } : current)
      })
      return () => cancelAnimationFrame(frame)
    }, [presentation.promptSettling])

    useLayoutEffect(() => {
      const element = elementRef.current
      if (centered) element?.querySelector<HTMLElement>('[contenteditable="true"], textarea')?.focus({ preventScroll: true })
      else if (wasCentered.current && (element?.contains(document.activeElement) || document.activeElement === document.body)) {
        element?.querySelector<HTMLElement>('[aria-label="放大输入面板"]')?.focus({ preventScroll: true })
      }
      wasCentered.current = centered
    }, [centered])

    useEffect(() => {
      if (!centered || hideComposer) return
      const outside = (event: PointerEvent) => {
        if (!(event.target instanceof Node) || elementRef.current?.contains(event.target) || overlaysRef.current?.contains(event.target)) return
        // 风格库使用共享的 body portal，仍属于当前输入面板的操作。
        if (event.target instanceof Element && (event.target.closest('.style-browser-shell')
          || event.target.closest('.ant-modal-wrap')?.querySelector('.style-browser-shell'))) return
        restore()
      }
      document.addEventListener('pointerdown', outside, true)
      return () => document.removeEventListener('pointerdown', outside, true)
    }, [centered, hideComposer, restore])

    const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
      event.stopPropagation()
      if (!centered || event.defaultPrevented || event.nativeEvent.isComposing) return
      if (event.key === 'Escape') {
        event.preventDefault()
        restore()
        return
      }
      if (event.key !== 'Tab') return
      const selector = 'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), [contenteditable="true"], [tabindex]:not([tabindex="-1"])'
      const controls = [elementRef.current, overlaysRef.current].flatMap(root => root ? Array.from(root.querySelectorAll<HTMLElement>(selector)) : [])
        .filter(element => element.tabIndex >= 0 && element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden')
      const current = document.activeElement
      if (!controls.length) return
      if (event.shiftKey && current === controls[0] || !event.shiftKey && current === controls.at(-1)) {
        event.preventDefault()
        ;(event.shiftKey ? controls.at(-1) : controls[0])?.focus()
      }
    }

    if (!node || hideComposer) return null
    return (
      <StudioComposerPresentationContext.Provider value={composerPresentation}>
      <div ref={overlaysRef} className="studio-node-composer-overlays" data-studio-node-composer-overlays={node.id} onKeyDown={handleKeyDown} />
      {centered ? <div className="studio-node-composer-backdrop" aria-hidden="true" onPointerDown={event => { event.preventDefault(); event.stopPropagation(); restore() }} /> : null}
      <div
        ref={elementRef}
        className={`studio-node-agent-dock studio-node-agent-dock--${studioMode}${centered ? ' is-centered' : ''}${presentation.promptSettling ? ' is-prompt-settling' : ''}${showLighting ? ' studio-node-agent-dock--lighting' : ''}${showMultiAngle ? ' studio-node-agent-dock--multi-angle' : ''}${showHd || showVideoHd ? ' studio-node-agent-dock--hd' : ''} nodrag nowheel`}
        role={centered ? 'dialog' : 'form'}
        aria-modal={centered || undefined}
        aria-label={dockLabel}
        style={{ left: -9999, top: 0, visibility: 'hidden', pointerEvents: 'none' }}
        onPointerDown={(event) => event.stopPropagation()}
        onKeyDownCapture={event => {
          // The rich editor's context-menu trigger consumes Escape even when closed.
          // The centered shell owns Escape from the editor, except while its menu is open.
          if (!centered || event.key !== 'Escape' || event.nativeEvent.isComposing
            || !(event.target instanceof HTMLElement) || !event.target.closest('[contenteditable="true"]')) return
          const popupOpen = Array.from(overlaysRef.current?.querySelectorAll<HTMLElement>('[role="dialog"], [role="menu"], .ant-popover, .ant-dropdown, .studio-mention-panel') ?? [])
            .some(popup => popup.getClientRects().length > 0 && getComputedStyle(popup).visibility !== 'hidden')
          if (popupOpen) return
          event.preventDefault()
          event.stopPropagation()
          restore()
        }}
        onKeyDown={handleKeyDown}
      >
        <div
          className={`home-composer-flow home-composer-flow--dialog studio-node-agent-dock__box${showEmotion ? ' studio-node-agent-dock__box--emotion' : ''}${showAudio ? ' studio-node-agent-dock__box--audio' : ''}${showLighting ? ' studio-node-agent-dock__box--lighting' : ''}${showMultiAngle ? ' studio-node-agent-dock__box--multi-angle' : ''}${showHd || showVideoHd ? ' studio-node-agent-dock__box--hd' : ''}${flowing ? ' is-flowing' : ''}`}
          onMouseEnter={() => setFlowing(true)}
          onMouseLeave={() => setFlowing(false)}
        >
          {showEmotion && emotionSession ? <StudioNodeImageEmotionDock key={emotionSession.id} node={node} session={emotionSession} /> : showAudio && audioEdit ? <StudioAudioEditPanel node={node} edit={audioEdit} sourceEditor={sourceAudioEditor} /> : showVideoDepth ? (
            <StudioVideoDepthPanel key={node.id} node={node} />
          ) : showMultiAngle ? (
            <StudioNodeImageMultiAngleDock key={node.id} node={node} />
          ) : showLighting ? (
            <StudioNodeImageLightingDock key={node.id} node={node} />
          ) : showHd ? (
            <StudioImageHdPanel key={node.id} node={node} />
          ) : showVideoHd ? (
            <StudioVideoHdPanel key={node.id} node={node} />
          ) : (
            <StudioNodeComposer key={node.id} node={node} />
          )}
        </div>
      </div>
      </StudioComposerPresentationContext.Provider>
    )
  }
))
