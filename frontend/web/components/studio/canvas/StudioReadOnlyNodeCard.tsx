'use client'

import { useEffect, useState } from 'react'
import { Handle, Position, useUpdateNodeInternals, type NodeProps } from '@xyflow/react'
import { Modal } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { studioNodeDimensions, rememberStudioMediaAspectRatio } from '@/utils/studio/studioNodeLod'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import { resolveDirectorNodePreviewUrl } from '@/utils/studio/directorScene'
import { StudioNodeExternalTitle, StudioImagePendingPreview } from './StudioNodeExternalTitle'
import { StudioMediaPreview } from './StudioMediaPreview'
import { StudioNodeLabelDecoration } from './StudioNodeLabels'

/** Native node presentation without mounting editor hooks, uploads or generation controls. */
export function StudioReadOnlyNodeCard({ id, data, selected }: NodeProps<StudioCanvasNode>) {
  const [fullText, setFullText] = useState(false)
  const [intrinsic, setIntrinsic] = useState<{ url: string; ratio: number } | null>(null)
  const edges = useStudioUiStore((state) => state.edges)
  const directorPreview = useStudioUiStore((state) => data.kind === 'director'
    ? resolveDirectorNodePreviewUrl(data, state.directorScenes) : undefined)
  const text = resolveStudioScriptNodeText(data)
  const mediaUrl = directorPreview || data.mediaUrl
  const dimensions = studioNodeDimensions({ data }, intrinsic && intrinsic.url === mediaUrl ? intrinsic.ratio : null)
  const updateNodeInternals = useUpdateNodeInternals()
  useEffect(() => {
    const frame = requestAnimationFrame(() => updateNodeInternals(id))
    return () => cancelAnimationFrame(frame)
  }, [dimensions.width, dimensions.height, id, updateNodeInternals])
  const mediaKind = directorPreview ? 'image' : data.mediaKind || (data.kind === 'image' || data.kind === 'video' ? data.kind : undefined)
  const grid = data.gridStoryboard
  const media = Boolean(mediaUrl && mediaKind)
  const ports = (role: 'source' | 'target') => [...new Set([null, ...edges
    .filter((edge) => edge[role] === id).map((edge) => edge[role === 'source' ? 'sourceHandle' : 'targetHandle'] || null)])]

  return <div className={`studio-node-shell studio-node-shell--titled${media ? ' studio-node-shell--image' : ''}${selected ? ' is-selected' : ''}`} style={dimensions}>
    <StudioNodeLabelDecoration data={data} />
    <StudioNodeExternalTitle kind={data.kind} title={data.title} readOnly />
    <article className={`studio-node-card studio-node-card--${data.status} studio-node-card--readonly${media ? ' studio-node-card--image-only' : ''}${selected ? ' is-selected' : ''}`} style={dimensions} aria-label={`只读节点：${data.title}`}>
      {grid ? <div className="studio-readonly-node__grid" style={{ gridTemplateColumns: `repeat(${grid.columns}, minmax(0, 1fr))`, gridTemplateRows: `repeat(${grid.rows}, minmax(0, 1fr))` }}>
        {grid.cells.map((cell) => <div key={cell.id}>{cell.mediaUrl
          ? <StudioMediaPreview kind={cell.mediaKind === 'video' ? 'video' : 'image'} mediaKind={cell.mediaKind || 'image'} mediaUrl={cell.mediaUrl} title={cell.title || data.title} />
          : null}</div>)}
      </div> : media ? <StudioMediaPreview kind={data.kind} mediaKind={mediaKind} mediaUrl={mediaUrl!} title={data.title} onIntrinsicSize={(width, height) => {
        const ratio = rememberStudioMediaAspectRatio(mediaUrl!, width, height)
        if (ratio) setIntrinsic((previous) => previous && previous.url === mediaUrl && previous.ratio === ratio ? previous : { url: mediaUrl!, ratio })
      }} />
        : text ? <div className="studio-node-card__text-preview studio-readonly-node__text nodrag nowheel">
          <p>{text}</p>
          <button type="button" onClick={() => setFullText(true)}>阅读全文</button>
        </div> : <StudioImagePendingPreview kind={data.kind} title="暂无内容" />}
    </article>
    {(['target', 'source'] as const).flatMap((role) => ports(role).map((port) => <Handle key={`${role}:${port}`} id={port || undefined} type={role} position={role === 'source' ? Position.Right : Position.Left} isConnectable={false} className="studio-readonly-node__port" />))}
    <Modal title={data.title} open={fullText} onCancel={() => setFullText(false)} footer={null} destroyOnHidden>
      <div className="studio-readonly-node__full-text">{text}</div>
    </Modal>
  </div>
}
