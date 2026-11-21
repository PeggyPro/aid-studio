'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Slider, Switch } from 'antd'
import { CloseOutlined, RedoOutlined } from '@ant-design/icons'
import { useBillingQuote } from '@/hooks/useBillingQuote'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { message } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import type { BillingQuoteRequest } from '@/types/business-api'
import { billingQuoteCreditsForDisplay } from '@/utils/billingQuoteDisplay'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { estimateStudioCredits } from '@/utils/studio/studioCreditEstimate'
import { fetchMediaBlob } from '@/utils/mediaFetch'
import { StudioCreditSendButton } from './StudioCreditSendButton'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioMultiAngleCubeScene } from './multi-angle/StudioMultiAngleCubeScene'
import {
  DEFAULT_MULTI_ANGLE,
  MULTI_ANGLE_PRESET_TABS,
  applyMultiAnglePreset,
  buildStudioMultiAnglePrompt,
  focalToLabel,
  presetPromptHint,
  type MultiAnglePresetId,
  type StudioMultiAngleValues
} from './multi-angle/multiAngleSceneMath'
import './StudioImageMultiAnglePanel.css'
import './StudioImageLightingPanel.css'

export type { MultiAnglePresetId, StudioMultiAngleValues }

export function StudioImageMultiAnglePanel({
  node,
  submitDisabledReason,
  onClose,
  onSubmit,
  storyboardId,
  multiViewModelCode,
  aspectRatio = '1:1'
}: {
  node: StudioCanvasNode
  submitDisabledReason: string | null
  onClose: () => void
  onSubmit: (prompt: string) => Promise<unknown>
  storyboardId?: number | null
  multiViewModelCode?: string | null
  aspectRatio?: string
}) {
  const [values, setValues] = useState<StudioMultiAngleValues>(DEFAULT_MULTI_ANGLE)
  const [submitting, setSubmitting] = useState(false)
  const [sceneReady, setSceneReady] = useState(false)
  const [resolvedImageUrl, setResolvedImageUrl] = useState<string | null>(node.data.mediaUrl ? null : '')
  const canvasEdit = useCanvasImageEdit(node)
  const ensureSourceResource = canvasEdit.ensureSourceResource
  const runtime = useCanvasProjectRuntime()
  const [canvasModelCode, setCanvasModelCode] = useState('')
  const [canvasModelOpen, setCanvasModelOpen] = useState(false)
  const [sourceResource, setSourceResource] = useState<{ url: string; id: string; version: number } | null>(null)
  const sourceResourceId = sourceResource && sourceResource.url === node.data.mediaUrl ? sourceResource.id : ''
  const sourceVersion = sourceResource && sourceResource.url === node.data.mediaUrl
    ? sourceResource.version : undefined
  const canvasSelection = canvasEdit.select('canvas.image.multi_view', 'image_edit', canvasModelCode)
  const canvasModel = canvasSelection.model
  const camera = useMemo(() => ({
    yaw: ((Math.round(values.horizontal) % 360) + 360) % 360,
    pitch: Math.max(-90, Math.min(90, Math.round(values.vertical))),
    framing: (values.focal <= 3 ? 'WIDE' : values.focal >= 7 ? 'CLOSE' : 'MEDIUM') as 'WIDE' | 'MEDIUM' | 'CLOSE'
  }), [values.horizontal, values.vertical, values.focal])
  useEffect(() => {
    if (!canvasEdit.active || !node.data.mediaUrl) return
    let active = true
    void ensureSourceResource().then((resource) => {
      const version = runtime?.getNodeVersion(node.id)
      if (active && version) setSourceResource({ url: node.data.mediaUrl!, id: String(resource.resourceId), version })
    }).catch(() => { if (active) setSourceResource(null) })
    return () => { active = false }
  }, [canvasEdit.active, ensureSourceResource, node.data.mediaUrl, node.id, runtime])

  const prompt = useMemo(() => buildStudioMultiAnglePrompt(values), [values])
  const hintText = useMemo(() => presetPromptHint(values.preset), [values.preset])

  const quoteRequest = useMemo<BillingQuoteRequest | null>(() => {
    if (submitDisabledReason || !node.data.mediaUrl || !storyboardId || !multiViewModelCode || !prompt) return null
    return {
      quoteType: 'STORYBOARD_MULTI_VIEW_IMAGE',
      payload: {
        storyboardId,
        imageUrl: node.data.mediaUrl,
        angles: [prompt],
        modelCode: multiViewModelCode,
        aspectRatio
      }
    }
  }, [aspectRatio, multiViewModelCode, node.data.mediaUrl, prompt, storyboardId, submitDisabledReason])

  const quote = useBillingQuote(canvasEdit.active ? null : quoteRequest)
  const canvasQuoteRequest = canvasEdit.active && canvasEdit.project && canvasModel && sourceResourceId && sourceVersion ? {
    modelName: canvasModel.modelCode, funcCode: 'canvas.image.multi_view', capabilityCode: 'image_edit',
    projectId: String(canvasEdit.project.projectId), workspaceId: String(canvasEdit.project.workspace.workspaceId),
    clientRequestId: 'multi-view-quote', prompt, expectedImageCount: 1,
    imageEdit: { operation: 'MULTI_VIEW' as const, sourceResourceId, sourceObjectId: node.id,
      sourceExpectedVersion: sourceVersion, camera }
  } : null
  const canvasQuote = useCanvasImageQuote(canvasQuoteRequest)
  const activeQuote = canvasEdit.active ? canvasQuote : quote
  const quotedCredits = canvasEdit.active ? studioCanvasQuoteCredits(activeQuote.quote) : billingQuoteCreditsForDisplay(activeQuote.quote)
  const quoteCredits = canvasEdit.active ? quotedCredits ?? 0
    : quotedCredits ?? estimateStudioCredits({ mediaKind: 'image' })
  const quoteIsFree = canvasEdit.active ? canvasQuote.quote?.isFree === true : quotedCredits === 0
  const activeReason = canvasEdit.active
    ? canvasEdit.disabledReason || (!canvasModel ? canvasEdit.error || '暂无可用的多机位模型' : !sourceResourceId || !sourceVersion ? '正在保存原图素材' : canvasQuote.error || (canvasQuote.quote?.matched === false ? '当前参数没有可用计费规则' : !canvasQuote.quote ? '正在获取报价' : ''))
    : submitDisabledReason

  useEffect(() => {
    setSceneReady(false)
    const source = node.data.mediaUrl
    if (!source) {
      setResolvedImageUrl('')
      return
    }
    let objectUrl: string | null = null
    let cancelled = false
    setResolvedImageUrl(null)
    void (async () => {
      try {
        const blob = await fetchMediaBlob(source)
        if (cancelled) return
        if (blob) {
          objectUrl = URL.createObjectURL(blob)
          setResolvedImageUrl(objectUrl)
          return
        }
      } catch { /* fall through */ }
      if (!cancelled) setResolvedImageUrl(source)
    })()
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [node.data.mediaUrl])

  const applyPreset = (preset: MultiAnglePresetId) => {
    setValues(applyMultiAnglePreset(preset))
  }

  const patchAngles = useCallback((patch: Partial<Pick<StudioMultiAngleValues, 'horizontal' | 'vertical' | 'focal'>>) => {
    setValues((current) => ({
      ...current,
      preset: 'custom',
      ...patch
    }))
  }, [])

  const handleCameraMove = useCallback((horizontal: number, vertical: number) => {
    patchAngles({ horizontal, vertical })
  }, [patchAngles])

  const handleSceneReady = useCallback(() => setSceneReady(true), [])
  const resetValues = () => setValues(DEFAULT_MULTI_ANGLE)
  const sceneUrlReady = resolvedImageUrl !== null

  const handleSubmit = () => {
    if (activeReason || submitting) return
    if (canvasEdit.active && runtime?.getNodeVersion(node.id) !== sourceVersion) {
      message.warning('原图节点版本已变化，请重新获取报价')
      setSourceResource(null)
      void ensureSourceResource().then((resource) => {
        const version = runtime?.getNodeVersion(node.id)
        if (version) setSourceResource({ url: node.data.mediaUrl!, id: String(resource.resourceId), version })
      }).catch(() => undefined)
      return
    }
    setSubmitting(true)
    void (canvasEdit.active && canvasModel
      ? canvasEdit.submit({ modelCode: canvasModel.modelCode, funcCode: 'canvas.image.multi_view', capabilityCode: 'image_edit',
        imageEdit: { operation: 'MULTI_VIEW', sourceObjectId: node.id, sourceExpectedVersion: sourceVersion, camera },
        expectedImageCount: 1, label: '多角度', purpose: node.data.imagePurpose ?? 'generic', prompt })
      : onSubmit(prompt)).then((ok) => {
      if (ok) onClose()
    }).catch((error: unknown) => message.error(error instanceof Error ? error.message : '多角度提交失败'))
      .finally(() => setSubmitting(false))
  }

  return (
    <div className="studio-image-multi-angle-shell studio-image-multi-angle-shell--dock">
      <header className="studio-image-multi-angle-header">
        <strong>多角度编辑器</strong>
        <button type="button" aria-label="关闭" onClick={onClose}>
          <CloseOutlined />
        </button>
      </header>

      <div className="studio-image-multi-angle-tabs" role="tablist" aria-label="机位预设">
        {MULTI_ANGLE_PRESET_TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={values.preset === tab.id}
            className={values.preset === tab.id ? 'is-active' : ''}
            onClick={() => applyPreset(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="studio-image-multi-angle-layout">
        <section className="studio-image-multi-angle-viewport">
          <div className={`studio-image-multi-angle-viewport__stage${sceneReady ? ' is-ready' : ''}`}>
            <div className="studio-image-multi-angle-viewport__canvas">
              {sceneUrlReady ? (
                <StudioMultiAngleCubeScene
                  imageUrl={resolvedImageUrl ?? ''}
                  horizontal={values.horizontal}
                  vertical={values.vertical}
                  focal={values.focal}
                  onCameraMove={handleCameraMove}
                  onReady={handleSceneReady}
                />
              ) : null}
            </div>
            {!sceneUrlReady || !sceneReady ? (
              <div className="studio-image-multi-angle-viewport__loading" aria-hidden />
            ) : null}
          </div>
          <div className="studio-image-multi-angle-viewport__footer">
            <button type="button" className="studio-image-multi-angle-viewport__reset" onClick={resetValues}>
              <RedoOutlined aria-hidden />
              重置参数
            </button>
          </div>
        </section>

        <aside className="studio-image-multi-angle-controls">
          <MultiAngleSliderRow
            label="水平环绕"
            value={values.horizontal}
            min={0}
            max={360}
            displayValue={Math.round(values.horizontal)}
            unit="°"
            onChange={(v) => patchAngles({ horizontal: v })}
          />
          <MultiAngleSliderRow
            label="垂直俯仰"
            value={values.vertical}
            min={-75}
            max={75}
            displayValue={Math.round(values.vertical)}
            unit="°"
            onChange={(v) => patchAngles({ vertical: v })}
          />
          <MultiAngleSliderRow
            label="景别缩放"
            value={values.focal}
            min={0}
            max={10}
            step={1}
            displayValue={focalToLabel(values.focal)}
            unit=""
            onChange={(v) => patchAngles({ focal: v })}
          />

          <div className="studio-image-multi-angle-hint-row">
            <span>提示词</span>
            <Switch
              size="small"
              checked={values.promptHint}
              onChange={(checked) => setValues((c) => ({ ...c, promptHint: checked }))}
            />
          </div>

          {values.promptHint && hintText ? (
            <p className="studio-image-multi-angle-hint-text">{hintText}</p>
          ) : null}
        </aside>
      </div>

      <footer className="studio-image-multi-angle-footer">
        {canvasEdit.active && <StudioCanvasDropdownPopover
          open={canvasModelOpen}
          onOpenChange={setCanvasModelOpen}
          placement="top"
          ariaLabel={'\u591a\u89d2\u5ea6\u6a21\u578b'}
          activeKey={canvasModel?.modelCode || ''}
          items={canvasSelection.models.map((item) => ({ key: item.modelCode, label: item.modelName }))}
          onSelect={setCanvasModelCode}
          trigger={<StudioComposerChip label={canvasModel?.modelName || '\u9009\u62e9\u6a21\u578b'} active={canvasModelOpen} />}
        />}
        <StudioCreditSendButton
              credits={quoteCredits}
              isFree={quoteIsFree}
              hasContent={Boolean(prompt.trim())}
              mode="agent"
              loading={submitting}
              className="studio-image-multi-angle-footer__send"
              authoritative={quotedCredits != null}
              pendingQuote={Boolean(canvasEdit.active && quotedCredits == null)}
              estimateLoading={Boolean(activeQuote.loading)}
              estimateError={activeQuote.error}
              quoteStatus={canvasEdit.active ? canvasQuote.status : undefined}

              disabled={Boolean(activeReason)}
              disabledReason={activeReason || ''}
              onSend={handleSubmit}
        />
      </footer>
    </div>
  )
}

function MultiAngleSliderRow({
  label,
  value,
  min,
  max,
  step = 1,
  displayValue,
  unit,
  onChange
}: {
  label: string
  value: number
  min: number
  max: number
  step?: number
  displayValue: string | number
  unit: string
  onChange: (value: number) => void
}) {
  return (
    <label className="studio-image-lighting-slider">
      <span className="studio-image-lighting-slider__label">{label}</span>
      <div className="studio-image-lighting-slider__row">
        <Slider
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={onChange}
          tooltip={{ open: false }}
        />
        <span className="studio-image-lighting-slider__value">
          <b>{displayValue}</b>
          {unit ? <em>{unit}</em> : null}
        </span>
      </div>
    </label>
  )
}
