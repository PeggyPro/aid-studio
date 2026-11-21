'use client'

/* eslint-disable @next/next/no-img-element -- 画布节点需预览运行时 OSS URL。 */
import { DownloadOutlined, ExpandAltOutlined } from '@ant-design/icons'
import { useRef, useState } from 'react'
import { useStudioMediaInView } from '@/hooks/studio/useStudioMediaInView'
import { StudioNodeMediaPlaceholder } from './StudioNodeMediaPlaceholder'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

export function StudioImageNodePreview({
  mediaUrl,
  title,
  deferOffscreen = false,
  onIntrinsicSize,
  onPreview,
  onDownload
}: {
  mediaUrl: string
  title: string
  deferOffscreen?: boolean
  onIntrinsicSize?: (width: number, height: number) => void
  onPreview: () => void
  onDownload: () => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const image = useRef<HTMLImageElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [load, setLoad] = useState<{ key: string; failed: boolean } | null>(null)
  const loadKey = mediaUrl + ':' + attempt
  const settled = load?.key === loadKey
  const failed = settled && load?.failed === true
  const requested = useStudioMediaInView(host, deferOffscreen)
  return (
    <div ref={host} className="studio-image-node-preview">
      <img
        key={loadKey}
        ref={image}
        data-studio-source-image="true"
        src={requested ? mediaUrl : undefined}
        decoding={deferOffscreen ? 'async' : 'auto'}
        alt={`${title}预览`}
        draggable={false}
        style={deferOffscreen && (!settled || failed) ? { visibility: 'hidden' } : undefined}
        onError={event => { if (deferOffscreen && event.currentTarget === image.current) setLoad({ key: loadKey, failed: true }) }}
        onLoad={(event) => {
          if (event.currentTarget !== image.current) return
          if (deferOffscreen) setLoad({ key: loadKey, failed: false })
          onIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)
        }}
      />
      {deferOffscreen && (!settled || failed)
        ? <StudioNodeMediaPlaceholder kind="image" failed={failed} onRetry={() => setAttempt(value => value + 1)} />
        : null}
      <div className="studio-image-node-preview__actions nodrag">
        <StudioCanvasTooltip title="放大预览">
          <button
            type="button"
            className="studio-image-node-preview__action"
            aria-label="放大预览"
            onClick={(event) => {
              event.stopPropagation()
              onPreview()
            }}
          >
            <ExpandAltOutlined />
          </button>
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title="下载">
          <button
            type="button"
            className="studio-image-node-preview__action"
            aria-label="下载图片"
            onClick={(event) => {
              event.stopPropagation()
              onDownload()
            }}
          >
            <DownloadOutlined />
          </button>
        </StudioCanvasTooltip>
      </div>
    </div>
  )
}
