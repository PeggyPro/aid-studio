'use client'

/* eslint-disable @next/next/no-img-element -- 风格图来自运行时素材目录。 */
import { BgColorsOutlined, CheckOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import './studio-style-card.css'

/** A complete foreground image with a soft edge fill; the title never covers the original image. */
export function StudioStyleCard({ id, name, thumbnail, selected = false, featured = false, onSelect }: {
  id: string; name: string; thumbnail?: string; selected?: boolean; featured?: boolean; onSelect: () => void
}) {
  return <StudioCanvasTooltip title={name}>
    <button type="button" className={`studio-style-card${selected ? ' is-selected' : ''}`} data-style-card-id={id}
      aria-label={name} aria-pressed={selected} onClick={onSelect}>
      <span className="studio-style-card__preview">
        {thumbnail ? <>
          <img className="studio-style-card__backdrop" src={thumbnail} alt="" aria-hidden loading="lazy" decoding="async" />
          <img className="studio-style-card__image" src={thumbnail} alt="" loading="lazy" decoding="async" />
        </> : <BgColorsOutlined className="studio-style-card__placeholder" aria-hidden />}
        {featured ? <span className="studio-style-card__badge">精选</span> : null}
        {selected ? <span className="studio-style-card__selected" aria-hidden><CheckOutlined /></span> : null}
      </span>
      <span className="studio-style-card__name">{name}</span>
    </button>
  </StudioCanvasTooltip>
}
