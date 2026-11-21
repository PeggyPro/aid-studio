'use client'

import { useCallback, useRef } from 'react'
import { useRouter } from 'next/navigation'
import { useCreateFlowPublishExport } from '@/components/create/create-flow-shell/useCreateFlowPublishExport'
import { runFlowSubmit } from '@/hooks/createFlowRouteSteps/stepSubmit'
import { usePreviewPublicationState } from '@/hooks/usePreviewPublicationState'
import { useCreationStore } from '@/stores/creation'

function hasExportedVideoInStore(): boolean {
  const store = useCreationStore.getState()
  return (
    store.currentExportStatus === 2 ||
    Boolean(String(store.currentFinalVideoUrl || '').trim()) ||
    Boolean(String(store.currentPendingVideoUrl || '').trim())
  )
}

/**
 * 流程画布交付链路：复用步骤 preview 的导出/发布编排，发布 submit 以成片导出为准。
 */
export function useStudioFlowPublishExport(pageReady: boolean) {
  const router = useRouter()
  const projectIdRef = useRef<number | null>(null)
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  projectIdRef.current = currentProjectId && currentProjectId > 0 ? currentProjectId : null

  const { isPublished: previewIsPublished } = usePreviewPublicationState({
    pageReady,
    isPreviewStep: true
  })

  const handleSubmit = useCallback(
    async (opts?: {
      alsoPublish?: boolean
      coverUrl?: string
      projectDesc?: string
    }) => runFlowSubmit(
      {
        getCanSubmit: () => hasExportedVideoInStore(),
        navigateToWorks: () => router.push('/works')
      },
      opts
    ),
    [router]
  )

  return useCreateFlowPublishExport({
    previewIsPublished,
    getActiveProjectId: () => projectIdRef.current,
    handleSubmit
  })
}
