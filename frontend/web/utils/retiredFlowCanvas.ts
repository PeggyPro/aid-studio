import type { CreationStep } from '~/types'
import { CREATE_FLOW_STEP_ORDER } from '~/utils/createFlowRoutes'
import { buildStudioStepsFlowHref } from '~/utils/studio/studioFlowNavigation'
import { buildPublicProjectPreviewFlowHref, isPublicProjectPreviewQuery, parsePublicProjectPreviewId } from '~/utils/publicProjectPreviewNavigation'

/** 退役的流程画布书签只回到七步页面，不挂载 Studio、不读取或更改自由画布项目。 */
export function retiredFlowCanvasHref(query: URLSearchParams): string | null {
  const publicPreview = isPublicProjectPreviewQuery(query)
  if (query.get('studioMode') !== 'flow' && !publicPreview) return null
  const rawStep = query.get('previewStep')
  const step: CreationStep = CREATE_FLOW_STEP_ORDER.includes(rawStep as CreationStep) ? rawStep as CreationStep : 'global-setting'
  const rawEpisode = query.get('episodeId')
  const episode = rawEpisode != null && /^\d+$/.test(rawEpisode) && Number.isSafeInteger(Number(rawEpisode)) ? Number(rawEpisode) : null
  if (publicPreview) {
    const projectId = parsePublicProjectPreviewId(query.get('previewProjectId'))!
    const url = buildPublicProjectPreviewFlowHref(projectId, step)
    return episode == null ? url : `${url}&episodeId=${episode}`
  }
  const rawProject = query.get('projectId') ?? ''
  const projectId = /^\d+$/.test(rawProject) ? Number(rawProject) : 0
  if (!Number.isSafeInteger(projectId) || projectId <= 0) return '/works'
  return buildStudioStepsFlowHref({ projectId, episodeId: episode, step, from:query.get('from') })
}
