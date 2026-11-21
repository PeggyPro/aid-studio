'use client'

import type { CanvasPublicationState } from '@/types/canvas-api'
import { canvasPublicationStateLabel } from '@/utils/canvas/canvasPublicationState'
import { PublicationSummary as ReviewSummary } from '@/components/common/publication/PublicationStatus'

export { publicationTone, PublicationBadge } from '@/components/common/publication/PublicationStatus'

export function PublicationSummary({ state }: { state: CanvasPublicationState }) {
  return <ReviewSummary state={{ reviewStatus: state.reviewStatus, reviewReason: state.reviewReason ?? undefined,
    visibility: state.visibility, isPublic: state.isPublic }} label={canvasPublicationStateLabel(state)} />
}
