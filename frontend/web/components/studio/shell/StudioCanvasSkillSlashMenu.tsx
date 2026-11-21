'use client'

import type { CanvasSkillItem } from '@/utils/studio/canvasSkillCatalog'
import { StudioCanvasSkillGlyph } from './StudioCanvasSkillPicker'
import './studio-canvas-skill-gallery.css'

export function StudioCanvasSkillSlashMenu({
  query,
  items,
  activeIndex,
  onHover,
  onSelect
}: {
  query: string
  items: CanvasSkillItem[]
  activeIndex: number
  onHover: (index: number) => void
  onSelect: (item: CanvasSkillItem) => void
}) {
  return (
    <div className="canvas-skill-slash" role="listbox" aria-label="选择 Skill 命令">
      <strong>{query ? `/${query}` : '常用 Skill'}</strong>
      {items.length ? items.map((item, index) => (
        <button
          key={item.code || 'general'}
          type="button"
          role="option"
          aria-selected={index === activeIndex}
          className={index === activeIndex ? 'is-active' : ''}
          onMouseEnter={() => onHover(index)}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => onSelect(item)}
        >
          <StudioCanvasSkillGlyph />
          <span>{item.name}</span>
          {item.code ? <em>/{item.code}</em> : null}
        </button>
      )) : <p>没有匹配的 Skill</p>}
    </div>
  )
}
