'use client'

import { STUDIO_GROUP_COLOR_PRESETS } from '@/utils/studio/studioGroup'

export function StudioGroupColorPicker({
  value,
  onChange
}: {
  value?: string | null
  onChange: (color: string | null) => void
}) {
  return (
    <div className="studio-group-color-picker" role="listbox" aria-label="分组色标">
      {STUDIO_GROUP_COLOR_PRESETS.map((preset) => {
        const active = (value ?? null) === preset.color
        return (
          <button
            key={preset.id}
            type="button"
            role="option"
            aria-selected={active}
            aria-label={preset.label}
            className={[
              'studio-group-color-picker__swatch',
              preset.color ? '' : 'is-none',
              active ? 'is-active' : ''
            ].filter(Boolean).join(' ')}
            style={preset.color ? { background: preset.color } : undefined}
            onClick={(event) => {
              event.stopPropagation()
              onChange(preset.color)
            }}
          />
        )
      })}
    </div>
  )
}
