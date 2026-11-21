'use client'

import { useEffect, useMemo, useState } from 'react'
import { BgColorsOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { message } from 'antd'
import type { GlobalSettingData } from '@/types'
import type { StudioCanvasNode } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { StyleLibraryMorePanel } from '@/components/steps/global-setting/StyleLibraryMorePanel'
import { useGlobalSettingStyleLibrary } from '@/components/steps/global-setting/useGlobalSettingStyleLibrary'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioStyleCard } from './StudioStyleCard'
import '@/components/steps/global-setting/global-setting.css'

type SelectedStyle = NonNullable<GlobalSettingData['selectedStyle']>

export function StudioStylePicker({ node }: { node: StudioCanvasNode }) {
  const [mounted, setMounted] = useState(false)
  const options = node.data.generationOptions ?? {}
  const selectedStyle: SelectedStyle | null = options.styleId ? {
    id: options.styleId,
    name: options.styleName ?? '已选风格',
    thumbnail: options.stylePreviewUrl ?? '',
    promptText: options.stylePrompt
  } : null

  return (
    <span className="studio-style-panel">
      <StudioComposerChip
        icon={<StudioChromeIcon name="nodeStyle" fallback={<BgColorsOutlined />} />}
        label={options.styleName ?? '风格'}
        active={Boolean(options.styleId)}
        onClick={() => setMounted(true)}
      />
      {mounted ? (
        <StudioStyleLibraryDialog
          selectedStyle={selectedStyle}
          onSelect={(style) => {
            useStudioUiStore.getState().updateNodeData(node.id, {
              generationOptions: {
                ...options,
                styleId: style.id,
                styleName: style.name,
                stylePreviewUrl: style.thumbnail,
                stylePrompt: style.promptText?.trim() || undefined
              }
            })
            setMounted(false)
          }}
          onClose={() => setMounted(false)}
        />
      ) : null}
    </span>
  )
}

/** Shared style-library surface used by composer selection and canvas style nodes. */
export function StudioStyleLibraryDialog({
  selectedStyle,
  onSelect,
  onClose
}: {
  selectedStyle: SelectedStyle | null
  onSelect: (style: SelectedStyle) => void
  onClose: () => void
}) {
  const globalSetting = useCreationStore((state) => state.formData.globalSetting)
  const modelValue = useMemo<GlobalSettingData>(() => ({
    ...globalSetting,
    selectedStyle
  }), [globalSetting, selectedStyle])
  const library = useGlobalSettingStyleLibrary({
    modelValue,
    styleLocked: false,
    styleLockActionMessage: '',
    onModelValueChange: (next) => {
      if (!next.styleSelectionTouched || !next.selectedStyle) return
      onSelect(next.selectedStyle)
    }
  })
  const changeStylePanelOpen = library.changeStylePanelOpen
  useEffect(() => changeStylePanelOpen(true), [changeStylePanelOpen])

  return (
    <StyleLibraryMorePanel
      open={library.isStylePanelOpen}
      onOpenChange={(open) => {
        changeStylePanelOpen(open)
        if (!open) onClose()
      }}
      categories={library.styleCategories}
      categoriesLoading={library.styleCategoriesLoading}
      categoriesError={library.styleCategoriesError}
      activeCategoryCode={library.activeStyleCategoryCode}
      onSelectCategory={library.selectStyleCategory}
      scrollRootRef={library.styleScrollRootRef}
      onScroll={library.onFeaturedStylesScroll}
      loadError={library.styleLoadError}
      loaded={library.stylesLoaded}
      loadingMore={library.styleLoadingMore}
      hasMore={library.styleHasMore}
      hasItems={library.mergedStyleList.length > 0}
    >
      {library.mergedStyleList.map((style) => (
        <StudioStyleCard
          key={style.id}
          id={style.id} name={style.name} thumbnail={style.thumbnail} featured={style.featured} selected={library.isCurrentStyle(style)}
          onSelect={() => {
            if (!style.promptText?.trim()) {
              message.warning('该风格尚未配置中文提示词，请在后台补齐后使用')
              return
            }
            if (library.isCurrentStyle(style)) {
              onSelect(style)
              return
            }
            library.selectStyle(style)
          }}
        />
      ))}
    </StyleLibraryMorePanel>
  )
}
