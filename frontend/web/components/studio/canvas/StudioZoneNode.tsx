import type { NodeProps } from '@xyflow/react'
import type { StudioZoneNode } from '@/types/studio'

export function StudioZoneNode({ data }: NodeProps<StudioZoneNode>) {
  return (
    <div className="studio-zone-node" style={{ '--studio-zone-color': data.color } as React.CSSProperties}>
      <div className="studio-zone-node__title">{data.title}</div>
      <div className="studio-zone-node__caption">{data.caption}</div>
    </div>
  )
}
