'use client'

import { Suspense } from 'react'
import { useRouter } from 'next/navigation'
import { SeriesScriptImport } from '~/components/create/SeriesScriptImport'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import { CREATE_SERIES_EPISODE_LIST_PATH } from '~/utils/createFlowRoutes'

function SeriesScriptUploadClient() {
  const router = useRouter()
  const route = useRouteLike()
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const routeId = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const projectId = Number.isFinite(routeId) && routeId > 0 ? routeId : currentProjectId

  function openEpisodes(episodeId?: number) {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(route.query)) {
      if (value != null) query.set(key, String(Array.isArray(value) ? value[0] : value))
    }
    if (projectId) {
      query.set('projectId', String(projectId))
      query.set('id', String(projectId))
    }
    if (episodeId) query.set('episodeId', String(episodeId))
    useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
    router.push(`${CREATE_SERIES_EPISODE_LIST_PATH}?${query}`)
  }

  if (!projectId) return <p role="alert">缺少项目信息，请从作品列表重新进入。</p>
  return <SeriesScriptImport key={projectId} projectId={projectId}
    onImported={(result) => openEpisodes(result.episodes?.[0]?.episodeId)}
    onSkip={() => openEpisodes()} />
}

export default function SeriesScriptUploadPage() {
  return <Suspense fallback={null}><SeriesScriptUploadClient /></Suspense>
}
