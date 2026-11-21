'use client'

import { useEffect, useState } from 'react'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  resolveStudioPreferredModelCode,
  shouldResolveStudioPreferredModelFromProject
} from '@/utils/studio/studioComposerDefaultModel'

/** 按生成配置 / 智能体默认解析 Composer 优先模型编码。 */
export function useStudioPreferredModelCode(sceneCode?: string | null): string {
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const [preferredModelCode, setPreferredModelCode] = useState('')

  useEffect(() => {
    let active = true
    if (!shouldResolveStudioPreferredModelFromProject({ studioMode, projectId, sceneCode })) {
      setPreferredModelCode('')
      return () => { active = false }
    }
    void resolveStudioPreferredModelCode({
      projectId,
      episodeId,
      sceneCode
    }).then((next) => {
      if (active) setPreferredModelCode(next)
    }).catch(() => {
      if (active) setPreferredModelCode('')
    })
    return () => { active = false }
  }, [episodeId, projectId, sceneCode, studioMode])

  return preferredModelCode
}
