'use client'

import {
  AppstoreOutlined,
  CloseOutlined,
  PictureOutlined,
  SkinOutlined,
  UserOutlined,
  VideoCameraOutlined
} from '@ant-design/icons'
import type { ReactNode } from 'react'
import {
  STUDIO_STORYBOARD_COMPOSITION_CATEGORY_LABEL,
  groupStudioStoryboardCompositionsByCategory,
  type StudioStoryboardCompositionCategory,
  type StudioStoryboardCompositionItem
} from '@/utils/studio/studioStoryboardComposition'
import './studio-canvas-dropdown.css'

const CATEGORY_ICONS: Record<StudioStoryboardCompositionCategory, ReactNode> = {
  narrative: <VideoCameraOutlined />,
  texture: <SkinOutlined />,
  space: <AppstoreOutlined />,
  setting: <UserOutlined />
}

function itemIcon(item: StudioStoryboardCompositionItem): ReactNode {
  if (item.category === 'setting' && item.settingSubject === 'scene') return <PictureOutlined />
  return CATEGORY_ICONS[item.category]
}

/** 分镜构图双栏功能面板（对齐规格图 4 分类）。 */
export function StudioStoryboardCompositionPanel({
  activeId,
  onSelect
}: {
  activeId?: string
  onSelect: (id: string) => void
}) {
  const groups = groupStudioStoryboardCompositionsByCategory()
  const left: StudioStoryboardCompositionCategory[] = ['narrative', 'texture']
  const right: StudioStoryboardCompositionCategory[] = ['space', 'setting']

  return (
    <div
      className="studio-storyboard-composition-panel studio-canvas-popover-scroll"
      role="menu"
      aria-label="分镜构图功能"
    >
      <div className="studio-storyboard-composition-panel__col">
        {left.map((category) => (
          <CategoryBlock
            key={category}
            category={category}
            items={groups[category]}
            activeId={activeId}
            onSelect={onSelect}
          />
        ))}
      </div>
      <div className="studio-storyboard-composition-panel__col">
        {right.map((category) => (
          <CategoryBlock
            key={category}
            category={category}
            items={groups[category]}
            activeId={activeId}
            onSelect={onSelect}
          />
        ))}
      </div>
    </div>
  )
}

function CategoryBlock({
  category,
  items,
  activeId,
  onSelect
}: {
  category: StudioStoryboardCompositionCategory
  items: StudioStoryboardCompositionItem[]
  activeId?: string
  onSelect: (id: string) => void
}) {
  if (!items.length) return null
  return (
    <section className="studio-storyboard-composition-panel__section">
      <h4>{STUDIO_STORYBOARD_COMPOSITION_CATEGORY_LABEL[category]}</h4>
      <div className="studio-storyboard-composition-panel__list">
        {items.map((item) => {
          const active = item.id === activeId
          return (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              className={`studio-storyboard-composition-panel__item${active ? ' is-active' : ''}`}
              onClick={() => onSelect(item.id)}
            >
              <i aria-hidden>{itemIcon(item)}</i>
              <span>
                <strong>{item.label}</strong>
                {item.subtitle ? <small>{item.subtitle}</small> : null}
              </span>
            </button>
          )
        })}
      </div>
    </section>
  )
}

export function StudioStoryboardCompositionClearIcon({ onClick }: { onClick: () => void }) {
  return (
    <CloseOutlined
      className="studio-storyboard-composition-tag__close"
      aria-label="关闭分镜构图"
      onClick={(event) => {
        event.stopPropagation()
        onClick()
      }}
    />
  )
}
