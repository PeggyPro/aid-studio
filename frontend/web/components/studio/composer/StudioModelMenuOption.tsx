'use client'

import type { UserModelListItem } from '@/types/business-api'
import { resolveUserModelProviderLogo } from '@/utils/userModelOption'
import { StudioModelProviderIcon } from './StudioModelProviderIcon'
import './studio-canvas-dropdown.css'

export function StudioModelMenuOption({ item, selected, onSelect }: {
  item: UserModelListItem
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button type="button" role="option" aria-selected={selected} className={selected ? 'is-active' : ''}
      onClick={(event) => { event.stopPropagation(); onSelect() }}>
      <i aria-hidden><StudioModelProviderIcon logo={resolveUserModelProviderLogo(item)} name={item.modelName} /></i>
      <span><strong>{item.modelName}</strong><small>{item.providerName || item.modelCode}</small></span>
    </button>
  )
}
