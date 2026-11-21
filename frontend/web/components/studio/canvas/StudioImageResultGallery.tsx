'use client'

/* eslint-disable @next/next/no-img-element -- Generated OSS images are runtime URLs. */
import { DownloadOutlined, FullscreenExitOutlined, PictureOutlined } from '@ant-design/icons'
import type { CSSProperties } from 'react'
import type { CanvasImageGalleryResult } from '@/utils/canvas/canvasImageGallery'

export function StudioImageResultGallery({
  results, mainResultId, title, collapsed, canEdit, selectingResultId,
  onCollapse, onExpand, onSelect, onSelectMain, onDownload, onPreview, onMainIntrinsicSize
}: {
  results: CanvasImageGalleryResult[]
  mainResultId?: string
  title: string
  collapsed: boolean
  canEdit: boolean
  selectingResultId: string | null
  onCollapse: () => void
  onExpand: () => void
  onSelect: () => void
  onSelectMain: (resultId: string) => void
  onDownload: (result: CanvasImageGalleryResult) => void
  onPreview: (result: CanvasImageGalleryResult) => void
  onMainIntrinsicSize?: (width: number, height: number) => void
}) {
  const main = results.find((result) => result.resultId === mainResultId) ?? results[0]
  if (!main) return null
  if (collapsed) {
    return (
      <div className="studio-image-gallery studio-image-gallery--collapsed" onClick={onSelect}>
        <div className="studio-image-gallery__stack" aria-hidden="true" />
        <img data-studio-source-image="true" src={main.resultUrl} alt={`${title}主图`} draggable={false} decoding="async" onLoad={(event) => onMainIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)} onDoubleClick={(event) => { event.stopPropagation(); onPreview(main) }} />
        <button type="button" className="studio-image-gallery__count nodrag" aria-label={`展开${results.length}张生成图片`} onClick={(event) => { event.stopPropagation(); onSelect(); onExpand() }}>
          <PictureOutlined aria-hidden="true" /> {results.length}张
        </button>
      </div>
    )
  }
  return (
    <div className="studio-image-gallery studio-image-gallery--expanded" style={{ '--studio-image-gallery-columns': Math.ceil(Math.sqrt(results.length)) } as CSSProperties} onClick={onSelect}>
      {results.map((result, index) => {
        const primary = result.resultId === main.resultId
        return (
          <div className={`studio-image-gallery__tile${primary ? ' is-primary' : ''}`} key={result.resultId}>
            <img data-studio-source-image={primary ? 'true' : undefined} src={result.resultUrl} alt={`${title}第${index + 1}张${primary ? '，主图' : ''}`} draggable={false} loading={primary ? 'eager' : 'lazy'} decoding="async" onLoad={primary ? (event) => onMainIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight) : undefined} onDoubleClick={(event) => { event.stopPropagation(); onPreview(result) }} />
            {primary ? <span className="studio-image-gallery__primary-label">主图</span> : null}
            <div className="studio-image-gallery__actions nodrag">
              <button type="button" aria-label={`下载第${index + 1}张图片`} onClick={(event) => { event.stopPropagation(); onDownload(result) }}><DownloadOutlined aria-hidden="true" /><span>下载</span></button>
              {primary ? (
                <button type="button" aria-label="收起多图结果" onClick={(event) => { event.stopPropagation(); onSelect(); onCollapse() }}><FullscreenExitOutlined aria-hidden="true" /><span>收起</span></button>
              ) : canEdit ? (
                <button type="button" aria-label={`将第${index + 1}张设为主图`} disabled={selectingResultId !== null} onClick={(event) => { event.stopPropagation(); onSelect(); onSelectMain(result.resultId) }}>
                  {selectingResultId === result.resultId ? '设置中…' : '设为主图'}
                </button>
              ) : null}
            </div>
          </div>
        )
      })}
    </div>
  )
}
