import { memo } from 'react'
import type { NodeProps } from '@xyflow/react'
import type { StudioGroupNode } from '@/types/studio'
import { StudioGroupResizeHandles } from './StudioGroupResizeHandles'
import { useStudioUiStore } from '@/stores/studioUi'

function StudioGroupCardComponent({ id, data, selected }: NodeProps<StudioGroupNode>) {
  const readOnly = useStudioUiStore((state) => state.readOnly)
  const title = data.title || `分组 ${data.childIds.length} 个节点`
  const color = data.colorTag || null

  return (
    <section
      className={`studio-group-card${selected ? ' is-selected' : ''}${color ? ' has-color' : ''}`}
      style={color ? { background: `${color}33`, borderColor: `${color}99` } : undefined}
    >
      <span>{title}</span>
      {selected && !readOnly ? <StudioGroupResizeHandles groupId={id} /> : null}
    </section>
  )
}

export const StudioGroupCard = memo(StudioGroupCardComponent)
