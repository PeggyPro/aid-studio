'use client'

import { useId, type ReactNode, type RefObject } from 'react'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import type { UserAssetStyleCategoryItem } from '~/types/business-api'
import GlobalSettingStyleDialog from './GlobalSettingStyleDialog'

export interface StyleLibraryMorePanelProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  categories: UserAssetStyleCategoryItem[]
  categoriesLoading: boolean
  categoriesError: boolean
  activeCategoryCode: string
  onSelectCategory: (code: string) => void
  categoryDisabled?: boolean
  scrollRootRef: RefObject<HTMLElement | null>
  onScroll: () => void
  loadError: boolean
  loaded: boolean
  loadingMore: boolean
  hasMore: boolean
  hasItems: boolean
  appending?: boolean
  children: ReactNode
}

/**
 * 「更多风格」弹出面板：创建项目 GlobalSetting 与首页创作框共用同一套交互与结构。
 */
export function StyleLibraryMorePanel({
  open,
  onOpenChange,
  categories,
  categoriesLoading,
  categoriesError,
  activeCategoryCode,
  onSelectCategory,
  categoryDisabled = false,
  scrollRootRef,
  onScroll,
  loadError,
  loaded,
  loadingMore,
  hasItems,
  appending = false,
  children
}: StyleLibraryMorePanelProps) {
  const panelId = useId()
  const activeCategory = categories.find(category => category.code === activeCategoryCode)
  return (
    <GlobalSettingStyleDialog open={open} onOpenChange={onOpenChange}>
      <div className="style-browser-shell">
        <div className="style-browser-popover__header">
          <div
            className="style-category-filter"
            role="tablist"
            aria-label="精选风格分类"
            aria-orientation="horizontal"
            onKeyDown={(event) => {
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
              const tabs = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button[role="tab"]:not(:disabled)')]
              if (!tabs.length) return
              const current = Math.max(0, tabs.indexOf(document.activeElement as HTMLButtonElement))
              const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
                : (current + (event.key === 'ArrowLeft' ? -1 : 1) + tabs.length) % tabs.length
              event.preventDefault()
              tabs[next].focus()
              tabs[next].scrollIntoView({ block: 'nearest', inline: 'nearest' })
              onSelectCategory(tabs[next].dataset.categoryCode!)
            }}
          >
            {categoriesLoading ? (
              <span className="style-category-filter__hint">分类加载中…</span>
            ) : categoriesError ? (
              <span className="style-category-filter__hint">分类加载失败，请重新打开</span>
            ) : (
              categories.map((category) => (
                <button
                  key={category.code}
                  type="button"
                  role="tab"
                  id={`${panelId}-${category.code}`} data-category-code={category.code} aria-controls={panelId}
                  tabIndex={(activeCategory?.code ?? categories[0]?.code) === category.code ? 0 : -1}
                  className={`style-category-filter__item${activeCategoryCode === category.code ? ' is-active' : ''}`}
                  aria-selected={activeCategoryCode === category.code}
                  disabled={categoryDisabled}
                  onClick={() => onSelectCategory(category.code)}
                >
                  {category.label}
                </button>
              ))
            )}
          </div>
        </div>
        <div
          ref={(element) => {
            scrollRootRef.current = element
          }}
          className="style-browser-popover__body"
          id={panelId} role="tabpanel" aria-labelledby={activeCategory ? `${panelId}-${activeCategory.code}` : undefined}
          onScroll={onScroll}
        >
          {loadError ? (
            <p className="dict-placeholder">加载失败，请稍后重试</p>
          ) : !loaded ? (
            <p className="dict-placeholder">加载中…</p>
          ) : !hasItems ? (
            <p className="dict-placeholder">暂无数据</p>
          ) : null}
          {loaded && !loadError ? (
            <div className={`styles-grid${appending ? ' styles-grid--appending' : ''}`}>{children}</div>
          ) : null}
          {loadingMore ? <InfiniteScrollLoadFooter loading /> : null}
        </div>
      </div>
    </GlobalSettingStyleDialog>
  )
}

export default StyleLibraryMorePanel
