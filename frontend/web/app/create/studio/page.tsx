'use client'

import '@xyflow/react/dist/style.css'
import { retiredFlowCanvasHref } from '~/utils/retiredFlowCanvas'
import { AppLoadingIndicator } from '~/components/common/AppLoadingIndicator'
import '@/components/studio/studio-base.css'
import '@/components/studio/studio-panels.css'
import '@/components/studio/studio-canvas.css'
import '@/components/studio/director/director-stage.css'
import '@/components/previz/previz.css'
import '@/components/previz/previz-workspace.css'
import { StudioWorkspace } from '@/components/studio/StudioWorkspace'
import { CanvasSignedOutView } from '@/components/studio/free/CanvasSignedOutView'
import { CANVAS_AUTH_CHANGED_EVENT, hasCanvasGatewaySession } from '@/utils/canvas/canvasApiClient'
import { useUserStore } from '@/stores/user'
import { StudioModeGate } from '@/components/studio/StudioModeGate'
import type { StudioMode } from '@/types/studio'
import { useRouter, useSearchParams } from 'next/navigation'
import { Suspense, useEffect, useMemo, useRef, useState } from 'react'
import {
  buildStudioModeHref,
  parsePositiveStudioId,
  parseStudioEpisodeId,
  parseStudioMode,
  studioWorkspaceScopeKey
} from '@/utils/studio/studioModeEntry'
import {
  isPublicProjectPreviewQuery,
  parsePublicProjectPreviewId,
  PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY
} from '@/utils/publicProjectPreviewNavigation'

export default function StudioPage() {
  return (
    <Suspense fallback={null}>
      <StudioPageContent />
    </Suspense>
  )
}

function StudioPageContent() {
  const query = useSearchParams()
  const router = useRouter()
  const retiredHref = query.has('publicationId') ? null : retiredFlowCanvasHref(new URLSearchParams(query.toString()))
  useEffect(() => {
    if (retiredHref) router.replace(retiredHref)
  }, [retiredHref, router])
  if (retiredHref) return <main className="aid-retired-flow-entry" role="status" aria-live="polite"><AppLoadingIndicator label="正在返回创作流程…" /></main>
  return <StudioWorkspaceEntry />
}

function StudioWorkspaceEntry() {
  const router = useRouter()
  const query = useSearchParams()
  const publicationId = query.get('publicationId')
  const publicationHistory = query.get('mode') === 'history'
  const publication = useMemo(() => publicationId !== null
    ? { publicationId, history: publicationHistory } : undefined, [publicationId, publicationHistory])
  const publicPreviewProjectId = isPublicProjectPreviewQuery(query)
    ? parsePublicProjectPreviewId(query.get(PUBLIC_PROJECT_PREVIEW_ID_QUERY_KEY))
    : null
  const publicProjectReadOnly = publicPreviewProjectId != null
  const studioMode = publication ? 'free' : publicProjectReadOnly ? 'flow' : parseStudioMode(query.get('studioMode'))
  const rawProjectId = publicProjectReadOnly
    ? String(publicPreviewProjectId)
    : query.get('projectId')?.trim() || 'preview'
  const rawEpisodeId = query.get('episodeId')?.trim() || (studioMode === 'flow' ? '0' : 'default')
  const projectId = studioMode === 'flow'
    ? publicPreviewProjectId ?? parsePositiveStudioId(rawProjectId)
    : null
  const canvasProjectId = !publication && studioMode === 'free' && /^\d+$/.test(rawProjectId) && rawProjectId !== '0'
    ? rawProjectId
    : null
  const episodeId = parseStudioEpisodeId(rawEpisodeId)
  const perfNodeCount = query.get('studioPerf') === '3000' ? 3000 : undefined
  const [enteringMode, setEnteringMode] = useState<StudioMode | null>(null)
  const [entryError, setEntryError] = useState('')
  const mainToken = useUserStore((state) => state.token)
  const [canvasAuthenticated, setCanvasAuthenticated] = useState(false)
  const [authReady, setAuthReady] = useState(false)
  const entryTimerRef = useRef<number | null>(null)
  const baseScopeKey = studioWorkspaceScopeKey(rawProjectId, rawEpisodeId, studioMode ?? 'free')
  const scopeKey = publication ? `publication:${publicationHistory ? 'history' : 'public'}:${publicationId}`
    : publicProjectReadOnly ? `public-preview:${baseScopeKey}` : baseScopeKey
  const displayTitle = canvasProjectId
    ? '正在载入自由画布'
    : projectId
      ? `AID Studio · 项目 ${projectId}`
      : 'AID Studio · 开始创作'

  useEffect(() => {
    // Hydrate before choosing between the workspace and signed-out view.
    // AppBootstrap also hydrates, but its effect may run after this page's first paint.
    useUserStore.getState().hydrateFromStorage()
    const sync = () => setCanvasAuthenticated(hasCanvasGatewaySession(useUserStore.getState().token))
    sync()
    const readyFrame = window.requestAnimationFrame(() => setAuthReady(true))
    window.addEventListener(CANVAS_AUTH_CHANGED_EVENT, sync)
    window.addEventListener('storage', sync)
    return () => {
      window.cancelAnimationFrame(readyFrame)
      window.removeEventListener(CANVAS_AUTH_CHANGED_EVENT, sync)
      window.removeEventListener('storage', sync)
    }
  }, [mainToken])

  useEffect(() => {
    if (!studioMode || enteringMode !== studioMode) return
    if (entryTimerRef.current != null) window.clearTimeout(entryTimerRef.current)
    entryTimerRef.current = null
    const settledTimer = window.setTimeout(() => setEnteringMode(null), 0)
    return () => window.clearTimeout(settledTimer)
  }, [enteringMode, studioMode])

  useEffect(() => () => {
    if (entryTimerRef.current != null) window.clearTimeout(entryTimerRef.current)
  }, [])

  // 已废弃「流程画布立项门页」：无作品的 flow 入口统一落到无限画布。
  useEffect(() => {
    if (publicProjectReadOnly) return
    if (studioMode === 'flow' && !projectId) {
      router.replace(buildStudioModeHref(query.toString(), 'free'))
    }
  }, [publicProjectReadOnly, projectId, query, router, studioMode])

  const chooseMode = (mode: StudioMode) => {
    setEntryError('')
    setEnteringMode(mode)
    if (entryTimerRef.current != null) window.clearTimeout(entryTimerRef.current)
    entryTimerRef.current = window.setTimeout(() => {
      entryTimerRef.current = null
      setEnteringMode(null)
      setEntryError('页面未能进入所选画布，请重试。')
    }, 6000)
    try {
      router.replace(buildStudioModeHref(query.toString(), mode))
    } catch (error) {
      if (entryTimerRef.current != null) window.clearTimeout(entryTimerRef.current)
      entryTimerRef.current = null
      setEnteringMode(null)
      setEntryError(error instanceof Error ? error.message : '进入画布失败，请重试。')
    }
  }

  if (!studioMode) {
    return (
      <StudioModeGate
        onChoose={chooseMode}
        enteringMode={enteringMode}
        error={entryError}
      />
    )
  }
  if (studioMode === 'flow' && !projectId) {
    return null
  }
  if (!publication && studioMode === 'free' && !authReady) {
    return (
      <div className="studio-loading-screen" role="status" aria-label="正在恢复画布会话">
        <div className="studio-loading-screen__brand">AI·D <span>STUDIO</span></div>
      </div>
    )
  }
  if (!publication && studioMode === 'free' && (!mainToken || !canvasAuthenticated)) {
    return <CanvasSignedOutView />
  }
  return (
    <StudioWorkspace
      scopeKey={scopeKey}
      displayTitle={displayTitle}
      perfNodeCount={perfNodeCount}
      studioMode={studioMode}
      projectId={projectId}
      canvasProjectId={canvasProjectId}
      episodeId={episodeId}
      readOnly={publicProjectReadOnly || Boolean(publication)}
      publication={publication}
    />
  )
}
