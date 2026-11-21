'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { useEffect, useRef, useState } from 'react'
import { StudioModelMenuOption } from './StudioModelMenuOption'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioPreferredModelCode } from '@/hooks/studio/useStudioPreferredModelCode'
import type { UserModelListItem } from '@/types/business-api'
import type { StudioMediaKind } from '@/types/studio'
import { resolveUserModelProviderLogo } from '@/utils/userModelOption'
import {
  isStudioComposerModelInOptions,
  pickStudioComposerDefaultModel
} from '@/utils/studio/studioComposerDefaultModel'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioModelProviderIcon } from './StudioModelProviderIcon'
import { StudioPanelSelectTrigger, type StudioPanelSelectSize } from './StudioPanelSelectTrigger'
import './studio-canvas-dropdown.css'

export function StudioModelPicker({
  value,
  mediaKind,
  sceneCode,
  options: optionsProp,
  optionsLoading = false,
  optionsError = '',
  preferredModelCode: preferredModelCodeProp,
  selectedModelMeta,
  preserveInvalidSelection = false,
  syncDefaults = true,
  variant = 'chip',
  popupPlacement,
  disabled = false,
  size = 'default',
  ariaLabel = '选择生成模型',
  onChange
}: {
  value?: string
  mediaKind?: StudioMediaKind
  /** 生成配置 / 出片智能体 sceneCode；有项目时用于默认模型 */
  sceneCode?: string
  /** 外部模型池（流程节点专用池）；不传则用通用 catalog */
  options?: UserModelListItem[]
  optionsLoading?: boolean
  optionsError?: string
  preferredModelCode?: string
  /** 节点已保存的计费字段；目录加载后与模型一起校准。 */
  selectedModelMeta?: { modelName?: string; isFree?: boolean; costCredits?: number | null }
  /** 已选型号被下架时保留无效状态，要求用户重新选择，避免静默替换收费模型。 */
  preserveInvalidSelection?: boolean
  /** 受版本控制的画布仅允许用户选择时写入，读取目录不能修改节点。 */
  syncDefaults?: boolean
  /** 弹窗内菜单不使用画布节点定位，复用统一菜单并自动避让视口。 */
  popupPlacement?: 'topRight'
  disabled?: boolean
  /** 参数面板使用 full-width select 外观，其余场景保持紧凑 chip。 */
  variant?: 'chip' | 'panel'
  size?: StudioPanelSelectSize
  ariaLabel?: string
  onChange: (value: string, model: UserModelListItem) => void
}) {
  const catalog = useStudioModels(mediaKind ?? 'image', { enabled: optionsProp == null })
  const [open, setOpen] = useState(false)
  const options = optionsProp ?? catalog.data
  const loading = optionsProp ? optionsLoading : catalog.loading
  const error = optionsProp ? optionsError : catalog.error
  const preferredFromConfig = useStudioPreferredModelCode(sceneCode)
  const preferredModelCode = String(preferredModelCodeProp || preferredFromConfig || '').trim()
  const hasSavedModelMeta = selectedModelMeta != null
  const savedModelName = selectedModelMeta?.modelName
  const savedIsFree = selectedModelMeta?.isFree
  const savedCostCredits = selectedModelMeta?.costCredits
  const matched = isStudioComposerModelInOptions(options, value)
    ? options.find((item) => String(item.modelCode || '').trim() === String(value || '').trim())
    : undefined
  const fallback = pickStudioComposerDefaultModel(options, [preferredModelCode])
  const invalidSelection = preserveInvalidSelection && Boolean(value) && !matched
  const selected = matched ?? (invalidSelection || !syncDefaults ? undefined : fallback)
  const selectedValue = selected?.modelCode
  const label = selected?.modelName
    ?? (loading ? '加载模型' : error ? '模型加载失败' : invalidSelection ? '所选模型已不可用' : options.length ? '选择模型' : '暂无模型')
  const onChangeRef = useRef(onChange)
  const providerIcon = (
    <StudioModelProviderIcon
      logo={selected ? resolveUserModelProviderLogo(selected) : ''}
      name={selected?.modelName}
    />
  )

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  useEffect(() => {
    if (!syncDefaults || !options.length) return
    const current = options.find((item) => String(item.modelCode || '').trim() === String(value || '').trim())
    if (preserveInvalidSelection && value && !current) return
    const next = current ?? pickStudioComposerDefaultModel(options, [preferredModelCode])
    if (!next) return
    if (current && (!hasSavedModelMeta || (
      savedModelName === next.modelName
      && savedIsFree === next.isFree
      && savedCostCredits === next.costCredits
    ))) return
    onChangeRef.current(next.modelCode, next)
  }, [options, preferredModelCode, savedModelName, savedIsFree, savedCostCredits, hasSavedModelMeta, preserveInvalidSelection, syncDefaults, value])

  if (popupPlacement) return (
    <StudioCanvasDropdownPopover
      open={!disabled && open}
      onOpenChange={next => setOpen(!disabled && next)}
      placement={popupPlacement}
      autoAdjustOverflow
      align={{ overflow: { adjustX: true, adjustY: true, shiftX: true } }}
      getPopupContainer={getStudioComposerPopupContainer}
      ariaLabel={ariaLabel}
      activeKey={selectedValue}
      items={options.map(item => ({
        key: item.modelCode,
        label: item.modelName,
        description: item.providerName || item.modelCode,
        showSelectionCheck: false,
        icon: <StudioModelProviderIcon logo={resolveUserModelProviderLogo(item)} name={item.modelName} />
      }))}
      onSelect={code => {
        const item = options.find(model => model.modelCode === code)
        if (!disabled && item) onChange(code, item)
      }}
      trigger={(
        <StudioCanvasTooltip title={disabled ? '正在优化提示词，暂不可切换模型' : label} open={open ? false : undefined}>
          <StudioComposerChip className="studio-composer-chip--model" icon={providerIcon} label={label}
            aria-label={ariaLabel} aria-haspopup="menu" aria-expanded={!disabled && open}
            disabled={disabled || loading || !options.length} />
        </StudioCanvasTooltip>
      )}
    />
  )

  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement={variant === 'panel' ? 'topRight' : undefined}
      nodePlacement={variant !== 'panel'}
      arrow={false}
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
      content={(
        <div className="studio-model-menu" role="listbox" aria-label={ariaLabel}>
          <div className="studio-model-menu__list studio-canvas-popover-scroll">
            {loading ? <p className="studio-model-menu__state">正在读取可用模型…</p> : null}
            {!loading && error ? <p className="studio-model-menu__state is-error">{error}</p> : null}
            {!loading && !error && !options.length ? <p className="studio-model-menu__state">暂无可用模型</p> : null}
            {options.map((item) => (
              <StudioModelMenuOption key={item.modelCode} item={item} selected={item.modelCode === selectedValue}
                onSelect={() => { onChange(item.modelCode, item); setOpen(false) }} />
            ))}
          </div>
        </div>
      )}
    >
      {variant === 'panel' ? (
        <span className="studio-canvas-dropdown-popover__trigger">
          <StudioPanelSelectTrigger
            label={label}
            icon={providerIcon}
            ariaLabel={ariaLabel}
            size={size}
            disabled={loading || (!options.length && !error)}
          />
        </span>
      ) : (
        <StudioCanvasTooltip title={label} open={open ? false : undefined}>
          <StudioComposerChip
            className="studio-composer-chip--model"
            icon={providerIcon}
            label={label}
            aria-label={ariaLabel}
            aria-haspopup="listbox"
            aria-expanded={open}
          />
        </StudioCanvasTooltip>
      )}
    </StudioCanvasPopover>
  )
}
