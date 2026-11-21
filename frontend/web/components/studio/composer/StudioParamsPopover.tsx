'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Slider } from 'antd'
import { ControlOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { AspectRatioShape } from '@/components/common/aspect-ratio-picker/AspectRatioPickerPopover'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioPreferredModelCode } from '@/hooks/studio/useStudioPreferredModelCode'
import type { UserModelListItem } from '@/types/business-api'
import type { StudioMediaKind, StudioNodeData } from '@/types/studio'
import {
  coerceGenerationSettings,
  buildAudioSelectOptions,
  coerceVideoGenerationSettings,
  resolveCapabilityRecord
} from '@/utils/modelCapability'
import {
  resolveStudioComposerModelFromNode
} from '@/utils/studio/studioComposerDefaultModel'
import { resolveStudioParamsPanelOptions } from '@/utils/studio/studioGenerationOptions'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import {
  readRecommendedDurationSeconds,
  resolveVideoDurationOption
} from '@/utils/resolveVideoDurationOption'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'

type GenerationOptions = NonNullable<StudioNodeData['generationOptions']>

export function StudioParamsPopover({
  mode,
  value,
  onChange,
  modelCode,
  modelOptions,
  sceneCode,
  mediaKind,
  recommendedDurationSeconds,
  singleResultVideo = false,
  allowAutomaticDuration = false,
  syncDefaults = true,
  audioControls = false
}: {
  mode: 'image' | 'video'
  value: GenerationOptions
  onChange: (next: GenerationOptions) => void
  /** 当前节点选中的模型编码；与 StudioModelPicker 一致 */
  modelCode?: string
  /** 外部模型池（流程节点专用）；不传则读通用 catalog */
  modelOptions?: UserModelListItem[]
  sceneCode?: string
  mediaKind?: StudioMediaKind
  /** 分镜详情返回的推荐时长；按当前模型档位向上归一。 */
  recommendedDurationSeconds?: number | null
  /** 画布视频生成接口每次只返回一个任务，不提供批量视频数。 */
  singleResultVideo?: boolean
  allowAutomaticDuration?: boolean
  /** 受版本控制的画布参数只在用户选择或提交时写入。 */
  syncDefaults?: boolean
  /** 通用画布视频请求支持音频；流程/派生工具保持各自契约。 */
  audioControls?: boolean
}) {
  const [open, setOpen] = useState(false)
  const resolvedMediaKind = mediaKind ?? mode
  const catalog = useStudioModels(resolvedMediaKind, { enabled: modelOptions == null })
  const options = modelOptions ?? catalog.data
  const preferredModelCode = useStudioPreferredModelCode(sceneCode)
  const model = useMemo(
    () => (!syncDefaults ? options.find(item => item.modelCode === modelCode) : resolveStudioComposerModelFromNode(
      options,
      { data: { model: modelCode } },
      [preferredModelCode]
    )) ?? null,
    [modelCode, options, preferredModelCode, syncDefaults]
  )

  const panel = useMemo(() => {
    const resolved = resolveStudioParamsPanelOptions(mode, model, allowAutomaticDuration)
    const supportsSizePreset = model?.supportsSizePreset ?? resolveCapabilityRecord(model).supportsSizePreset
    if (mode !== 'image' || supportsSizePreset !== false) return resolved
    return {
      ...resolved,
      snapshot: { ...resolved.snapshot, sizeOptions: [], defaultSize: '' },
      qualityOptions: []
    }
  }, [allowAutomaticDuration, mode, model])
  const {
    snapshot,
    ratioOptions,
    qualityOptions,
    countOptions,
    durationOptions: durationSelectOptions,
    supportsDuration
  } = panel
  const durationValues = durationSelectOptions
    .map((item) => Number(item.value))
    .filter((n) => Number.isFinite(n) && n > 0)
  const supportsAutomaticDuration = durationSelectOptions.some((item) => item.value === '-1')
  const audioOptions = mode === 'video' && audioControls
    ? buildAudioSelectOptions(snapshot.supportsAudio, snapshot.audioOptions) : []
  const normalizedRecommendedDuration = readRecommendedDurationSeconds({ recommendedDurationSeconds })
  const resolvedRecommendedDuration = normalizedRecommendedDuration == null
    ? null
    : resolveVideoDurationOption({
        recommendedDurationSeconds: normalizedRecommendedDuration,
        durationOptions: snapshot.durationOptions,
        defaultDurationSeconds: snapshot.defaultDurationSeconds
      })

  const onChangeRef = useRef(onChange)
  const valueRef = useRef(value)

  useEffect(() => {
    onChangeRef.current = onChange
    valueRef.current = value
  }, [onChange, value])

  // 与编辑分镜视频弹窗一致：模型 capability 变化后收敛当前选中值
  useEffect(() => {
    // 未取得模型能力时不能用兜底规格覆盖已保存的节点参数。
    if (!syncDefaults || !model) return
    const current = valueRef.current
    if (mode === 'video') {
      const desiredDuration = resolvedRecommendedDuration ?? current.duration ?? snapshot.defaultDurationSeconds
      const coerced = coerceVideoGenerationSettings({
        aspectRatio: current.aspectRatio ?? '',
        count: singleResultVideo ? 1 : current.count ?? 1,
        quality: current.quality ?? '',
        duration: String(desiredDuration),
        audio: 'silent'
      }, snapshot)
      const nextDuration = Number(coerced.duration)
      const next: GenerationOptions = {
        ...current,
        aspectRatio: coerced.aspectRatio,
        count: singleResultVideo ? 1 : coerced.count,
        quality: coerced.quality,
        ...(Number.isFinite(nextDuration) && (nextDuration > 0 || nextDuration === -1 && supportsAutomaticDuration) ? { duration: nextDuration } : {})
      }
      if (
        next.aspectRatio !== current.aspectRatio
        || next.count !== current.count
        || next.quality !== current.quality
        || next.duration !== current.duration
      ) {
        onChangeRef.current(next)
      }
      return
    }

    const coerced = coerceGenerationSettings({
      aspectRatio: current.aspectRatio ?? '',
      count: current.count ?? 1,
      quality: current.quality ?? ''
    }, snapshot)
    if (
      coerced.aspectRatio !== current.aspectRatio
      || coerced.count !== current.count
      || (qualityOptions.length ? coerced.quality : '') !== current.quality
    ) {
      onChangeRef.current({
        ...current,
        aspectRatio: coerced.aspectRatio,
        count: coerced.count,
        quality: qualityOptions.length ? coerced.quality : ''
      })
    }
  }, [mode, model, qualityOptions.length, resolvedRecommendedDuration, singleResultVideo, snapshot, supportsAutomaticDuration, syncDefaults])

  const activeQualityLabel = qualityOptions.find((item) => item.value === value.quality)?.label
    ?? value.quality
    ?? snapshot.defaultSize
  const showCount = countOptions.length > 0 && !(mode === 'video' && singleResultVideo)
  const chipLabel = [
    ratioOptions.length ? value.aspectRatio ?? snapshot.defaultAspectRatio : '',
    activeQualityLabel,
    mode === 'video' && supportsDuration
      ? (value.duration ?? resolvedRecommendedDuration ?? snapshot.defaultDurationSeconds) === -1 ? '自动时长' : `${value.duration ?? resolvedRecommendedDuration ?? snapshot.defaultDurationSeconds}s`
      : '',
    showCount ? `${value.count ?? 1}${mode === 'video' ? '个' : '张'}` : '',
    audioOptions.length ? value.audioEnabled ? '含音频' : '无声' : ''
  ].filter(Boolean).join(' · ')

  return (
    <StudioCanvasPopover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      nodePlacement
      arrow={false}
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
      content={
        <div className="studio-params-panel studio-params-panel--generation" role="dialog" aria-label={mode === 'image' ? '图片生成参数' : '视频生成参数'}>
          {ratioOptions.length ? (
            <ParamGroup title="比例" variant="ratios">
              {ratioOptions.map(({ value: ratio }) => (
                <button
                  key={ratio}
                  type="button"
                  aria-pressed={value.aspectRatio === ratio}
                  className={value.aspectRatio === ratio ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, aspectRatio: ratio })}
                >
                  <AspectRatioShape value={ratio} /><span>{ratio}</span>
                </button>
              ))}
            </ParamGroup>
          ) : null}
          {supportsDuration ? (
            <ParamGroup title={resolvedRecommendedDuration == null ? '秒数' : `秒数 · 推荐 ${resolvedRecommendedDuration}s`}>
              {supportsAutomaticDuration && <button type="button" aria-pressed={value.duration === -1} className={value.duration === -1 ? 'is-active' : ''}
                onClick={() => onChange({ ...value, duration: -1 })}>自动时长</button>}
              {supportsAutomaticDuration && durationValues.length > 0 && <button type="button"
                aria-pressed={value.duration !== -1} className={value.duration !== -1 ? 'is-active' : ''}
                onClick={() => onChange({ ...value, duration: value.duration != null && value.duration > 0 ? value.duration
                  : durationValues.includes(snapshot.defaultDurationSeconds) ? snapshot.defaultDurationSeconds : durationValues[0] })}>指定秒数</button>}
              {durationValues.length > 0 && <Slider
                min={durationValues[0]}
                max={durationValues.at(-1)}
                step={null}
                // Slider excludes empty labels from selectable marks, even when step is null.
                marks={Object.fromEntries(durationValues.map((duration) => [duration, {
                  label: `${duration}s`,
                  ...(durationValues.length <= 8 || duration === durationValues[0] || duration === durationValues.at(-1) || duration === value.duration
                    ? {} : { style: { display: 'none' } })
                }]))}
                value={value.duration === -1 ? durationValues[0] : value.duration ?? snapshot.defaultDurationSeconds}
                disabled={value.duration === -1}
                onChange={(duration) => onChange({ ...value, duration })}
              />}
            </ParamGroup>
          ) : null}
          {audioOptions.length ? <ParamGroup title="音频" variant="segmented">
            {audioOptions.map(option => <button key={option.value} type="button"
              aria-pressed={(value.audioEnabled === true) === (option.value === 'with_audio')}
              className={(value.audioEnabled === true) === (option.value === 'with_audio') ? 'is-active' : ''}
              onClick={() => onChange({ ...value, audioEnabled: option.value === 'with_audio' })}>
              {option.value === 'with_audio' ? '生成音频' : '无声视频'}
            </button>)}
          </ParamGroup> : null}
          {showCount ? (
            <ParamGroup title={mode === 'video' ? '视频数' : '张数'} variant="choices">
              {countOptions.length === 1 ? <p className="studio-params-panel__fixed-count">
                当前模型仅支持 {countOptions[0].label}
              </p> : countOptions.map(({ value: count, label }) => (
                <button
                  key={count}
                  type="button"
                  aria-pressed={(value.count ?? 1) === count}
                  className={(value.count ?? 1) === count ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, count })}
                >
                  {label}
                </button>
              ))}
            </ParamGroup>
          ) : null}
          {qualityOptions.length ? (
            <ParamGroup title="清晰度" variant="quality">
              {qualityOptions.map(({ value: quality, label }) => (
                <button
                  key={quality}
                  type="button"
                  aria-pressed={value.quality === quality}
                  className={value.quality === quality ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, quality })}
                >
                  <span>{formatQualityLabel(label)}</span>
                </button>
              ))}
            </ParamGroup>
          ) : null}
        </div>
      }
    >
      <StudioCanvasTooltip title={chipLabel} open={open ? false : undefined}>
        <span className={`studio-composer-chip-trigger studio-composer-chip-trigger--params-${mode}`}>
          <StudioComposerChip
            className={`studio-composer-chip--params studio-composer-chip--params-${mode}`}
            icon={<StudioChromeIcon name="skillSettings" fallback={<ControlOutlined />} />}
            label={chipLabel}
            aria-label={`生成参数：${chipLabel}`}
            aria-haspopup="dialog"
            aria-expanded={open}
          />
        </span>
      </StudioCanvasTooltip>
    </StudioCanvasPopover>
  )
}

function ParamGroup({ title, children, variant }: { title: string; children: React.ReactNode; variant?: 'ratios' | 'segmented' | 'choices' | 'quality' }) {
  return <section className={`studio-params-panel__group${variant ? ` studio-params-panel__group--${variant}` : ''}`}><h4>{title}</h4><div>{children}</div></section>
}

function formatQualityLabel(label: string): string {
  if (/^auto$/i.test(label)) return '自动'
  return label.replace(/^(\d+)\s*[x×*]\s*(\d+)$/i, '$1 × $2')
}
