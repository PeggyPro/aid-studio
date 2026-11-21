import { STUDIO_NODE_META } from '@/config/studioMeta'
import type { StudioNodeKind } from '@/types/studio'
import { StudioNodeIcon } from './StudioNodeIcon'

export function StudioAddNodeMenuLabel({ kind }: { kind: StudioNodeKind }) {
  const { label, description } = STUDIO_NODE_META[kind]
  return (
    <span className="studio-add-menu__item">
      <i aria-hidden="true"><StudioNodeIcon kind={kind} /></i>
      <span><strong>{label}</strong><small>{description}</small></span>
    </span>
  )
}
