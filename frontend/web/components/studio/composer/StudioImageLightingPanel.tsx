'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { message, Slider, Switch, Tooltip } from 'antd'
import {
  CloseOutlined,
  InfoCircleOutlined,
  RedoOutlined,
  SunOutlined
} from '@ant-design/icons'
import { useBillingQuote } from '@/hooks/useBillingQuote'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import type { StudioCanvasNode } from '@/types/studio'
import type { BillingQuoteRequest } from '@/types/business-api'
import { billingQuoteCreditsForDisplay } from '@/utils/billingQuoteDisplay'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { StudioCreditSendButton } from './StudioCreditSendButton'
import { StudioModelPicker } from './StudioModelPicker'
import { StudioLightingR3FScene, type LightingViewMode } from './lighting/StudioLightingR3FScene'
import {
  DEFAULT_LIGHT_POSE,
  DEFAULT_RIM_LIGHT_POSE,
  LIGHT_DIRECTION_ANGLES,
  type StudioLightingDirection
} from './lighting/lightingSceneMath'
import './studio-image-ai-tools.css'
import './StudioImageLightingPanel.css'

export type { StudioLightingDirection }

export interface StudioLightingValues {
  brightness: number
  temperature: number
  direction: StudioLightingDirection
  azimuth: number
  elevation: number
  rimLight: boolean
  rimAzimuth: number
  rimElevation: number
}

export const DEFAULT_LIGHTING: StudioLightingValues = {
  brightness: 50,
  temperature: 5600,
  direction: DEFAULT_LIGHT_POSE.direction,
  azimuth: DEFAULT_LIGHT_POSE.azimuth,
  elevation: DEFAULT_LIGHT_POSE.elevation,
  rimLight: false,
  rimAzimuth: DEFAULT_RIM_LIGHT_POSE.azimuth,
  rimElevation: DEFAULT_RIM_LIGHT_POSE.elevation
}

const DIRECTION_PRESETS: Array<{ value: StudioLightingDirection; label: string }> = [
  { value: 'left', label: '左侧' },
  { value: 'top', label: '顶部' },
  { value: 'right', label: '右侧' },
  { value: 'front', label: '前方' },
  { value: 'bottom', label: '底部' },
  { value: 'back', label: '后方' }
]

export function StudioImageLightingPanel({
  node,
  submitDisabledReason,
  onClose,
  onSubmit,
  storyboardId,
  editModelCode,
  aspectRatio = '1:1',
  quality = '2K'
}: {
  node: StudioCanvasNode
  submitDisabledReason: string | null
  onClose: () => void
  onSubmit: (prompt: string) => Promise<unknown>
  storyboardId?: number | null
  editModelCode?: string | null
  aspectRatio?: string
  quality?: string
}) {
  const [values, setValues] = useState<StudioLightingValues>(DEFAULT_LIGHTING)
  const [viewMode, setViewMode] = useState<LightingViewMode>('perspective')
  const [submitting, setSubmitting] = useState(false)
  const submittingRef = useRef(false)
  const canvasEdit = useCanvasImageEdit(node)
  const ensureCanvasSourceResource = canvasEdit.ensureSourceResource
  const [canvasModelCode, setCanvasModelCode] = useState('')
  const [sourceResource, setSourceResource] = useState<{ url: string; id: string } | null>(null)
  const sourceResourceId = sourceResource && sourceResource.url === node.data.mediaUrl ? sourceResource.id : null
  const canvasSelection = canvasEdit.select('canvas.image.relight', 'image_edit', canvasModelCode)
  const canvasModel = canvasSelection.model
  const prompt = useMemo(() => buildStudioLightingPrompt(values), [values])
  const lighting = useMemo(() => ({
    brightness: Math.round((values.brightness - 50) * 2),
    colorTemperature: Math.round(values.temperature),
    keyLightAzimuth: ((Math.round(values.azimuth) % 360) + 360) % 360,
    keyLightElevation: Math.round(values.elevation),
    rimLightEnabled: values.rimLight,
    rimLightAzimuth: ((Math.round(values.rimAzimuth) % 360) + 360) % 360,
    rimLightElevation: Math.round(values.rimElevation)
  }), [values])

  useEffect(() => {
    if (!canvasEdit.active || !canvasEdit.project?.workspace.workspaceId) return
    let active = true
    void ensureCanvasSourceResource().then((resource) => {
      if (active) setSourceResource({ url: node.data.mediaUrl!, id: String(resource.resourceId) })
    }).catch((reason: unknown) => {
      if (active) message.error(reason instanceof Error ? reason.message : '无法登记原图素材')
    })
    return () => { active = false }
  }, [canvasEdit.active, canvasEdit.project?.workspace.workspaceId, ensureCanvasSourceResource, node.id, node.data.mediaUrl])

  const quoteRequest = useMemo<BillingQuoteRequest | null>(() => {
    if (submitDisabledReason || !node.data.mediaUrl || !storyboardId || !editModelCode || !prompt) return null
    return {
      quoteType: 'STORYBOARD_EDIT_IMAGE',
      payload: {
        storyboardId,
        referenceImage: node.data.mediaUrl,
        prompt,
        modelCode: editModelCode,
        aspectRatio,
        size: quality,
        imageCount: 1
      }
    }
  }, [aspectRatio, editModelCode, node.data.mediaUrl, prompt, quality, storyboardId, submitDisabledReason])

  const quote = useBillingQuote(canvasEdit.active ? null : quoteRequest)
  const canvasQuote = useCanvasImageQuote(canvasEdit.active && canvasEdit.project && canvasModel && sourceResourceId ? {
    modelName: canvasModel.modelCode, funcCode: 'canvas.image.relight', capabilityCode: 'image_edit',
    projectId: String(canvasEdit.project.projectId), workspaceId: String(canvasEdit.project.workspace.workspaceId),
    clientRequestId: 'image-relight-quote', prompt, expectedImageCount: 1,
    imageEdit: { operation: 'RELIGHT', sourceResourceId, lighting }
  } : null)
  const activeQuote = canvasEdit.active ? canvasQuote : quote
  const quotedCredits = canvasEdit.active ? studioCanvasQuoteCredits(activeQuote.quote) : billingQuoteCreditsForDisplay(activeQuote.quote)
  const quoteCredits = quotedCredits ?? 0
  const quoteIsFree = canvasEdit.active ? canvasQuote.quote?.isFree === true : quotedCredits === 0
  const activeDisabledReason = canvasEdit.active
    ? canvasEdit.disabledReason || (!canvasModel ? (canvasEdit.error || '暂无可用的打光模型') : !sourceResourceId ? '正在登记原图素材' : canvasQuote.error || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : !canvasQuote.quote ? '正在获取报价' : ''))
    : submitDisabledReason

  const update = <K extends keyof StudioLightingValues>(key: K, value: StudioLightingValues[K]) => {
    setValues((current) => ({ ...current, [key]: value }))
  }

  const applyPreset = (direction: StudioLightingDirection) => {
    const angles = LIGHT_DIRECTION_ANGLES[direction]
    setValues((current) => ({
      ...current,
      direction,
      azimuth: angles.azimuth,
      elevation: angles.elevation
    }))
  }

  const handleLightMove = useCallback((azimuth: number, elevation: number, direction: StudioLightingDirection) => {
    setValues((current) => {
      if (
        current.azimuth === azimuth
        && current.elevation === elevation
        && current.direction === direction
      ) {
        return current
      }
      return { ...current, azimuth, elevation, direction }
    })
  }, [])

  const handleRimMove = useCallback((rimAzimuth: number, rimElevation: number) => {
    setValues((current) => {
      if (current.rimAzimuth === rimAzimuth && current.rimElevation === rimElevation) return current
      return { ...current, rimAzimuth, rimElevation }
    })
  }, [])

  const resetValues = () => setValues(DEFAULT_LIGHTING)

  const handleSubmit = () => {
    if (submittingRef.current || activeDisabledReason) return
    submittingRef.current = true
    setSubmitting(true)
    void (canvasEdit.active && canvasModel
      ? canvasEdit.submit({
          modelCode: canvasModel.modelCode, funcCode: 'canvas.image.relight', capabilityCode: 'image_edit',
          imageEdit: { operation: 'RELIGHT', lighting }, expectedImageCount: 1,
          label: '打光', purpose: node.data.imagePurpose ?? 'generic', prompt
        })
      : onSubmit(prompt)).then((ok) => {
      if (ok) onClose()
    }).catch((reason: unknown) => message.error(reason instanceof Error ? reason.message : '打光提交失败'))
      .finally(() => { submittingRef.current = false; setSubmitting(false) })
  }

  return (
    <div className="studio-image-lighting-shell studio-image-lighting-shell--dock">
      <div className="studio-image-lighting-layout">
        <section className="studio-image-lighting-viewport">
          <div className="studio-image-lighting-viewport__tabs" role="tablist" aria-label="打光视图">
            {(['perspective', 'front'] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                role="tab"
                aria-selected={viewMode === mode}
                className={viewMode === mode ? 'is-active' : ''}
                onClick={() => setViewMode(mode)}
              >
                {mode === 'perspective' ? '透视' : '正面'}
              </button>
            ))}
          </div>
          <div className="studio-image-lighting-viewport__stage">
            <div className="studio-image-lighting-viewport__canvas">
              <StudioLightingR3FScene
                imageUrl={node.data.mediaUrl || ''}
                azimuth={values.azimuth}
                elevation={values.elevation}
                brightness={values.brightness}
                temperature={values.temperature}
                rimLight={values.rimLight}
                rimAzimuth={values.rimAzimuth}
                rimElevation={values.rimElevation}
                viewMode={viewMode}
                onLightMove={handleLightMove}
                onRimMove={handleRimMove}
              />
            </div>
          </div>
          <div className="studio-image-lighting-viewport__footer">
            <span className="studio-image-lighting-viewport__badge">主光源</span>
            <button type="button" className="studio-image-lighting-viewport__reset" onClick={resetValues}>
              <RedoOutlined aria-hidden />
              重置
            </button>
          </div>
        </section>

        <aside className="studio-image-lighting-global">
          <header className="studio-image-lighting-global__header">
            <strong>全局</strong>
            <button type="button" aria-label="关闭" onClick={onClose}>
              <CloseOutlined />
            </button>
          </header>

          <div className="studio-image-lighting-global__body">
            {canvasEdit.active ? (
              <div className="studio-image-lighting-model">
                <span className="studio-image-lighting-slider__label">打光模型</span>
                <StudioModelPicker
                  value={canvasModelCode || canvasModel?.modelCode}
                  options={canvasSelection.models}
                  variant="panel"
                  size="compact"
                  ariaLabel="打光模型"
                  onChange={(modelCode) => setCanvasModelCode(modelCode)}
                />
              </div>
            ) : null}
            <LightingSliderRow
              label="亮度"
              value={values.brightness}
              min={0}
              max={100}
              suffix="%"
              icon={<SunOutlined aria-hidden />}
              onChange={(value) => update('brightness', value)}
            />
            <LightingSliderRow
              label="色温"
              value={values.temperature}
              min={2000}
              max={10000}
              step={100}
              suffix="K"
              tone
              onChange={(value) => update('temperature', value)}
            />

            <div className="studio-image-lighting-presets">
              <span className="studio-image-lighting-presets__label">主光源</span>
              <div className="studio-image-lighting-presets__grid">
                {DIRECTION_PRESETS.map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    className={values.direction === item.value ? 'is-active' : ''}
                    onClick={() => applyPreset(item.value)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="studio-image-lighting-rim">
              <span className="studio-image-lighting-rim__label">
                轮廓光
                <Tooltip title="开启后在主体边缘增加分离光，增强层次与立体感">
                  <InfoCircleOutlined aria-hidden />
                </Tooltip>
              </span>
              <Switch
                size="small"
                checked={values.rimLight}
                onChange={(checked) => update('rimLight', checked)}
              />
            </div>
          </div>
        </aside>
      </div>

      <footer className="studio-image-lighting-footer">
        <Tooltip title={activeDisabledReason || ''}>
          <span>
            <StudioCreditSendButton
              credits={quoteCredits}
              isFree={quoteIsFree}
              hasContent={Boolean(prompt.trim())}
              mode="agent"
              loading={submitting}
              className="studio-image-lighting-footer__send"
              authoritative={quotedCredits != null}
              pendingQuote={Boolean(canvasEdit.active && quotedCredits == null)}
              estimateLoading={activeQuote.loading}
              estimateError={activeQuote.error}
              quoteStatus={canvasEdit.active ? canvasQuote.status : undefined}

              disabled={Boolean(activeDisabledReason)}
              disabledReason={activeDisabledReason || ''}
              onSend={handleSubmit}
            />
          </span>
        </Tooltip>
      </footer>
    </div>
  )
}

function LightingSliderRow({
  label,
  value,
  min,
  max,
  step = 1,
  suffix,
  icon,
  tone = false,
  onChange
}: {
  label: string
  value: number
  min: number
  max: number
  step?: number
  suffix: string
  icon?: React.ReactNode
  tone?: boolean
  onChange: (value: number) => void
}) {
  return (
    <label className={`studio-image-lighting-slider${tone ? ' studio-image-lighting-slider--tone' : ''}`}>
      <span className="studio-image-lighting-slider__label">{label}</span>
      <div className="studio-image-lighting-slider__row">
        <Slider value={value} min={min} max={max} step={step} onChange={onChange} />
        <span className="studio-image-lighting-slider__value">
          {icon}
          <b>{value}</b>
          <em>{suffix}</em>
        </span>
      </div>
    </label>
  )
}

export function buildStudioLightingPrompt(values: StudioLightingValues): string {
  const direction = {
    front: '前方主光',
    left: '左侧主光',
    right: '右侧主光',
    top: '顶部主光',
    bottom: '底部主光',
    back: '后方逆光'
  }[values.direction]
  const temperature = values.temperature > 6000 ? '偏冷色温' : values.temperature < 4500 ? '偏暖色温' : '中性日光色温'
  const brightnessDelta = values.brightness - 50
  const rim = values.rimLight
    ? `开启轮廓光（后方 ${Math.round(values.rimAzimuth)}° / ${Math.round(values.rimElevation)}°），边缘分离清晰`
    : '关闭轮廓光'
  const angleHint = `水平角 ${Math.round(values.azimuth)}°，垂直角 ${Math.round(values.elevation)}°`
  return `保持原图主体、构图和镜头不变，进行电影级打光编辑：${direction}（${angleHint}），亮度 ${brightnessDelta >= 0 ? '+' : ''}${brightnessDelta}%，色温 ${values.temperature}K（${temperature}），${rim}。光影自然，层次清晰，不新增人物或物体。`
}
