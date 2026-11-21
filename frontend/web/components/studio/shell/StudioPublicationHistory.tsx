'use client'

import type { canvasPublicationVersions } from '@/utils/canvas/canvasPublicationApi'
import { withAppBasePath } from '@/utils/appBasePath'
import { PublicationHistory } from '@/components/common/publication/PublicationHistory'

type Version = Awaited<ReturnType<typeof canvasPublicationVersions>>[number]
export function StudioPublicationHistory(props: {
  versions: Version[] | null; loading: boolean; error: string; onRetry: () => void
}) {
  return <PublicationHistory {...props} versionHref={id => withAppBasePath(`/canvas/work?publicationId=${encodeURIComponent(id)}&mode=history`)} />
}
