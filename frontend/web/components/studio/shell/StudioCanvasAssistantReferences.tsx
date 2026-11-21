'use client'

import { CloseOutlined, FileTextOutlined, LoadingOutlined, PictureOutlined, SoundOutlined, VideoCameraOutlined, WarningOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import type { StudioCanvasNode } from '@/types/studio'

export function StudioCanvasAssistantReferences({ ids, nodes, disabled, onRemove }: {
  ids: string[]; nodes: StudioCanvasNode[]; disabled: boolean; onRemove: (id: string) => void
}) {
  const selected = ids.flatMap(id => { const node = nodes.find(item => item.id === id); return node ? [node] : [] })
  if (!selected.length) return null
  return <ul className="canvas-assistant-references" aria-label="本次引用的素材">{selected.map(node => {
    const id = node.id
    const label = String(node.data.title || node.data.label || '未命名节点')
    const kind = node.data.kind
    const busy = node.data.canvasTransient && node.data.status === 'generating'
    const failed = node.data.status === 'failed'
    const thumbnail = kind === 'image' && typeof node.data.mediaUrl === 'string'
      && /^(https?:\/\/|blob:)/.test(node.data.mediaUrl) ? node.data.mediaUrl : null
    return <li key={id} className={`canvas-assistant-reference${failed ? ' is-error' : ''}`}>
      <StudioCanvasTooltip title={busy ? `${label} · 正在导入` : failed ? `${label} · ${node?.data.errorMessage || '请移除失效引用'}` : label}>
        <span className="canvas-assistant-reference__body">
          {busy ? <LoadingOutlined aria-hidden /> : failed ? <WarningOutlined aria-hidden /> : thumbnail ? (
            // eslint-disable-next-line @next/next/no-img-element -- Same authorized canvas thumbnail; no credential-bearing proxy.
            <img src={thumbnail} alt="" loading="lazy" referrerPolicy="no-referrer" />
          ) : kind === 'image' ? <PictureOutlined aria-hidden /> : kind === 'video' ? <VideoCameraOutlined aria-hidden /> : kind === 'voice' ? <SoundOutlined aria-hidden /> : <FileTextOutlined aria-hidden />}
          <span>{label}</span>
        </span>
      </StudioCanvasTooltip>
      <StudioCanvasTooltip title={`移除引用：${label}`}><button type="button" aria-label={`移除引用：${label}`} disabled={disabled} onClick={() => onRemove(id)}><CloseOutlined /></button></StudioCanvasTooltip>
    </li>
  })}</ul>
}
