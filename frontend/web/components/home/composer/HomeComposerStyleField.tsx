'use client'

import { useEffect, useRef, useState } from 'react'
import dialogSelectSelIconMod from '@/assets/img/icon/dialog-select-sel.svg'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { StyleThumbnail } from '~/components/common/StyleThumbnail'
import type { StyleLibraryCard } from '~/composables/usePromptDictionary'
import StyleLibraryMorePanel from '~/components/steps/global-setting/StyleLibraryMorePanel'
import { useGlobalSettingStyleLibrary } from '~/components/steps/global-setting/useGlobalSettingStyleLibrary'
import type { GlobalSettingData } from '~/types'
import { assetUrl } from '~/utils/assetUrl'
import '~/components/steps/global-setting/global-setting.css'

const dialogSelectSelIcon = assetUrl(dialogSelectSelIconMod)

interface HomeComposerStyleFieldProps {
  modelValue: GlobalSettingData
  onModelValueChange: (value: GlobalSettingData) => void
}

function ChevronIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
      <path
        d="M3 4.5L6 7.5L9 4.5"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export function HomeComposerStyleField({
  modelValue,
  onModelValueChange
}: HomeComposerStyleFieldProps) {
  const styleLockActionMessage = '无法切换风格'
  const {
    styleScrollRootRef,
    mergedStyleList,
    styleLoadingMore,
    styleLoadError,
    styleHasMore,
    stylesLoaded,
    stylesLoadRevision,
    isStylePanelOpen,
    styleCategories,
    activeStyleCategoryCode,
    styleCategoriesLoading,
    styleCategoriesError,
    selectStyleCategory,
    selectedStyleShortcut,
    locatedStyleId,
    onFeaturedStylesScroll,
    selectStyle,
    isCurrentStyle,
    changeStylePanelOpen
  } = useGlobalSettingStyleLibrary({
    modelValue,
    onModelValueChange,
    styleLocked: false,
    styleLockActionMessage
  })

  const [appendedStyleIds, setAppendedStyleIds] = useState<ReadonlySet<string>>(new Set())
  const prevStyleIdsRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    if (!isStylePanelOpen || !stylesLoaded) return undefined
    const frame = requestAnimationFrame(onFeaturedStylesScroll)
    return () => cancelAnimationFrame(frame)
  }, [isStylePanelOpen, stylesLoaded, mergedStyleList.length, onFeaturedStylesScroll])

  useEffect(() => {
    const currentIds = new Set(mergedStyleList.map((item) => item.id))
    const fresh = new Set<string>()
    currentIds.forEach((id) => {
      if (!prevStyleIdsRef.current.has(id)) fresh.add(id)
    })
    if (fresh.size && prevStyleIdsRef.current.size) setAppendedStyleIds(fresh)
    prevStyleIdsRef.current = currentIds
  }, [mergedStyleList])

  function renderStyleCard(style: StyleLibraryCard) {
    const isActive = isCurrentStyle(style)
    const cardClass = [
      'style-card',
      isActive ? 'active' : '',
      style.featured ? 'featured' : '',
      locatedStyleId === style.id ? 'style-card--located' : '',
      appendedStyleIds.has(style.id) ? 'style-card-append-enter' : ''
    ]
      .filter(Boolean)
      .join(' ')

    return (
      <div
        key={style.id}
        data-style-card-id={style.id}
        className={cardClass}
        onClick={() => selectStyle(style)}
      >
        {style.featured ? <span className="featured-badge">精选</span> : null}
        <span
          className={`style-selected-mark${isActive ? ' style-selected-mark--visible' : ''}`}
          aria-hidden="true"
        >
          <img src={dialogSelectSelIcon} alt="" className="style-selected-mark__icon" />
        </span>
        <span className="style-active-ring" aria-hidden="true" />
        <div className="style-thumb">
          <StyleThumbnail key={`${stylesLoadRevision}-${style.id}`} url={style.thumbnail || ''} width={160} name={style.name} />
        </div>
        <div className="style-overlay">
          <EllipsisTooltip title={style.name} />
        </div>
      </div>
    )
  }

  const label = selectedStyleShortcut?.name || '选择风格'

  return (
    <>
      <button
        type="button"
        aria-label="选择风格"
        aria-haspopup="dialog"
        aria-expanded={isStylePanelOpen}
        className="home-composer-select-trigger"
        onClick={() => changeStylePanelOpen(!isStylePanelOpen)}
      >
        {selectedStyleShortcut?.thumbnail ? <img className="home-composer-style-thumb" src={selectedStyleShortcut.thumbnail} alt="" width={18} height={14} /> : null}
        <span className="home-composer-select-trigger__label">{label}</span>
        <ChevronIcon />
      </button>
      <StyleLibraryMorePanel
        open={isStylePanelOpen}
        onOpenChange={changeStylePanelOpen}
        categories={styleCategories}
        categoriesLoading={styleCategoriesLoading}
        categoriesError={styleCategoriesError}
        activeCategoryCode={activeStyleCategoryCode}
        onSelectCategory={selectStyleCategory}
        categoryDisabled={!stylesLoaded || styleLoadingMore}
        scrollRootRef={styleScrollRootRef}
        onScroll={onFeaturedStylesScroll}
        loadError={styleLoadError}
        loaded={stylesLoaded}
        loadingMore={styleLoadingMore}
        hasMore={styleHasMore}
        hasItems={mergedStyleList.length > 0}
        appending={styleLoadingMore}
      >
        {mergedStyleList.map(renderStyleCard)}
      </StyleLibraryMorePanel>
    </>
  )
}

export default HomeComposerStyleField
