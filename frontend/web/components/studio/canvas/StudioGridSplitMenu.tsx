'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useMemo, useState } from 'react'
import { AppstoreOutlined, RightOutlined } from '@ant-design/icons'
import { STUDIO_GRID_MAX_EDGE, STUDIO_GRID_PRESETS } from '@/utils/studio/studioImageGridSession'
import '@/components/studio/composer/studio-canvas-dropdown.css'
import '@/components/studio/composer/studio-image-ai-tools.css'

const POPOVER_CLASS =
  'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover'

export function StudioGridSplitMenu({
  onSelect,
  onSelectRegion
}: {
  onSelect: (rows: number, columns: number) => void
  onSelectRegion?: () => void
}) {
  const [customOpen, setCustomOpen] = useState(false)
  const [hover, setHover] = useState<{ rows: number; cols: number }>({ rows: 1, cols: 1 })

  const presets = useMemo(() => STUDIO_GRID_PRESETS, [])

  return (
    <div className="studio-canvas-dropdown-menu studio-canvas-popover-scroll studio-grid-split-menu" role="menu" aria-label="宫格切分">
      {presets.map((item) => (
        <button
          key={item.key}
          type="button"
          role="menuitem"
          className="studio-canvas-dropdown-menu__item"
          onClick={() => onSelect(item.rows, item.columns)}
        >
          <i aria-hidden><AppstoreOutlined /></i>
          <span>{item.label}</span>
        </button>
      ))}
      <span className="studio-grid-split-menu__divider" aria-hidden />
      {onSelectRegion ? <button type="button" role="menuitem" className="studio-canvas-dropdown-menu__item" onClick={onSelectRegion}>
        <i aria-hidden><AppstoreOutlined /></i><span>自定义区域</span>
      </button> : null}
      <StudioCanvasPopover
        open={customOpen}
        onOpenChange={setCustomOpen}
        trigger="hover"
        placement="rightTop"
        arrow={false}
        mouseEnterDelay={0.08}
        classNames={{ root: POPOVER_CLASS }}
        content={(
          <div className="studio-grid-custom-panel" aria-label="自定义宫格">
            <strong>自定义宫格</strong>
            <div
              className="studio-grid-custom-panel__board"
              style={{ gridTemplateColumns: `repeat(${STUDIO_GRID_MAX_EDGE}, 1fr)` }}
              onMouseLeave={() => setHover({ rows: 1, cols: 1 })}
            >
              {Array.from({ length: STUDIO_GRID_MAX_EDGE * STUDIO_GRID_MAX_EDGE }, (_, index) => {
                const row = Math.floor(index / STUDIO_GRID_MAX_EDGE) + 1
                const col = (index % STUDIO_GRID_MAX_EDGE) + 1
                const active = row <= hover.rows && col <= hover.cols
                return (
                  <button
                    key={`${row}-${col}`}
                    type="button"
                    className={`studio-grid-custom-panel__cell${active ? ' is-active' : ''}`}
                    aria-label={`${row}×${col}`}
                    disabled={row * col < 2}
                    onMouseEnter={() => setHover({ rows: row, cols: col })}
                    onClick={() => {
                      onSelect(row, col)
                      setCustomOpen(false)
                    }}
                  />
                )
              })}
            </div>
            <span className="studio-grid-custom-panel__hint">{hover.rows} × {hover.cols}</span>
          </div>
        )}
      >
        <button
          type="button"
          role="menuitem"
          className="studio-canvas-dropdown-menu__item studio-grid-split-menu__custom"
        >
          <i aria-hidden><AppstoreOutlined /></i>
          <span>自定义</span>
          <RightOutlined className="studio-grid-split-menu__chevron" />
        </button>
      </StudioCanvasPopover>
    </div>
  )
}
