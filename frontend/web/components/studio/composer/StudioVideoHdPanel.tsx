'use client'

import { useMemo, useState } from 'react'
import { message } from 'antd'
import { StudioCreditSendButton } from '@/components/studio/composer/StudioCreditSendButton'
import { useStudioVideoOperation } from '@/hooks/studio/useStudioVideoOperation'
import { useCanvasVideoToolQuote } from '@/hooks/studio/useCanvasVideoToolQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioCanvasModelsByFunc } from '@/hooks/studio/useStudioCatalog'
import { findModelCapability, modelSupportsCapability, resolveCapabilityRecord } from '@/utils/modelCapability'
import { resolveStudioVideoHdCapabilities } from '@/utils/studio/studioVideoHdCapabilities'
import { useStudioUiStore } from '@/stores/studioUi'
import type {
  StudioCanvasNode,
  StudioVideoFrameRate,
  StudioVideoInterpolationMode,
  StudioVideoResolution,
  StudioVideoSlowMotionFactor
} from '@/types/studio'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioModelPicker } from './StudioModelPicker'
import { StudioPanelSelectTrigger } from './StudioPanelSelectTrigger'
import './studio-image-ai-tools.css'
import './studio-video-tools.css'

export const STUDIO_VIDEO_FRAME_RATES: StudioVideoFrameRate[] = [23.976, 24, 25, 29.97, 30, 48, 50, 59.94, 60, 120]
export const STUDIO_VIDEO_RESOLUTIONS: StudioVideoResolution[] = ['1080', '2K', '4K']
export const STUDIO_VIDEO_SLOW_MOTION_FACTORS: StudioVideoSlowMotionFactor[] = [1, 2, 3, 4, 5]

export function StudioVideoHdPanel({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const sourceId = node.data.videoToolOptions?.sourceNodeId
  const source = useStudioUiStore((state) => state.nodes.find((item) => item.id === sourceId))
  const operation = useStudioVideoOperation()
  const canvasRuntime = useCanvasProjectRuntime()
  const poolKey = String(canvasRuntime?.project?.projectId ?? '')
  const catalog = useStudioCanvasModelsByFunc('video_enhance', poolKey, Boolean(canvasRuntime))
  const pool = { ...catalog, models: catalog.data.filter(item => modelSupportsCapability(item, 'video_enhance')) }
  const options = node.data.videoToolOptions ?? {}
  const selectedModel = pool.models.find(model => model.modelCode === node.data.model)
  const capability = { ...resolveCapabilityRecord(selectedModel), ...findModelCapability(selectedModel, 'video_enhance')?.presentation }
  const [sourceMetadata, setSourceMetadata] = useState<{ url: string; width: number; height: number } | null>(null)
  const sourceHeight = sourceMetadata && sourceMetadata.url === source?.data.mediaUrl ? Math.min(sourceMetadata.width, sourceMetadata.height) : null
  const { resolutions, requiresSourceMetadata, supportsInterpolation, supportsSlowMotion } = resolveStudioVideoHdCapabilities(capability, sourceHeight, STUDIO_VIDEO_RESOLUTIONS)
  const resolution = resolutions.includes(options.resolution!) ? options.resolution! : resolutions[0]
  const interpolationMode = supportsInterpolation ? options.interpolationMode ?? 'none' : 'none'
  const frameRate = options.frameRate ?? 60
  const slowMotionFactor = supportsSlowMotion ? options.slowMotionFactor ?? 1 : 1
  const processing = node.data.status === 'queued' || node.data.status === 'generating'
  const quote = useCanvasVideoToolQuote(source && resolution && canvasRuntime && !pool.loading && pool.models.some(model => model.modelCode === node.data.model) ? {
    source, targetNodeId: node.id, operation: 'ENHANCE', label: '视频高清',
    modelCode: node.data.model, resolution, interpolationMode, slowMotionFactor,
    ...(interpolationMode === 'high_quality' ? { frameRate } : {})
  } : null, canvasRuntime)
  const quotedCredits = studioCanvasQuoteCredits(quote.quote)
  const credits = canvasRuntime
    ? quotedCredits ?? 0
    : node.data.modelIsFree ? 0 : Math.max(0, Number(node.data.modelCostCredits) || 0)
  const patchOptions = (patch: Partial<NonNullable<StudioCanvasNode['data']['videoToolOptions']>>) => {
    const latest = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
    updateNodeData(node.id, {
      videoToolOptions: {
        ...latest?.data.videoToolOptions,
        ...patch,
        sourceNodeId: sourceId
      }
    })
  }

  const interpolationItems = useMemo(() => [
    { key: 'none', label: '不补帧' },
    { key: 'high_quality', label: '高质量补帧' }
  ], [])
  const frameRateItems = useMemo(() => STUDIO_VIDEO_FRAME_RATES.map((value) => ({
    key: String(value),
    label: `${value} FPS`
  })), [])
  const slowMotionItems = useMemo(() => STUDIO_VIDEO_SLOW_MOTION_FACTORS.map((value) => ({
    key: String(value),
    label: value === 1 ? '1×（不慢放）' : `${value}×`
  })), [])

  const submit = async () => {
    if (processing) return
    if (!source?.data.mediaUrl) {
      message.warning('找不到源视频，请从带视频的节点重新发起高清')
      return
    }
    if (!node.data.model || !resolution) {
      message.warning('请先选择视频处理模型')
      return
    }
    await operation.run({
      source,
      targetNodeId: node.id,
      operation: 'ENHANCE',
      label: '视频高清',
      modelCode: node.data.model,
      resolution,
      interpolationMode,
      slowMotionFactor,
      ...(interpolationMode === 'high_quality' ? { frameRate } : {})
    })
  }

  return (
    <div className="studio-image-ai-panel studio-video-hd-panel nodrag nowheel">
      <div className="studio-image-ai-panel__rows">
        <div className="studio-image-ai-panel__row">
          <span>选择模型</span>
          <StudioModelPicker
            value={node.data.model}
            mediaKind="video"
            options={canvasRuntime ? pool.models : undefined}
            optionsLoading={canvasRuntime ? pool.loading : false}
            optionsError={canvasRuntime ? pool.error : ''}
            preferredModelCode={canvasRuntime ? pool.defaultModelCode : undefined}
            variant="panel"
            onChange={(model, item) => updateNodeData(node.id, {
              model,
              modelName: item.modelName,
              modelCostCredits: item.costCredits,
              modelIsFree: item.isFree
            })}
          />
        </div>
        <div className="studio-image-ai-panel__row">
          <span>分辨率</span>
          <div className="studio-image-ai-panel__segment studio-video-hd-panel__resolution" role="tablist" aria-label="输出分辨率">
            {resolutions.map((value) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={resolution === value}
                className={resolution === value ? 'is-active' : ''}
                onClick={() => patchOptions({ resolution: value })}
              >
                {value === '720' ? '720p' : value}
              </button>
            ))}
          </div>
          {!resolutions.length && <small role="status">{requiresSourceMetadata && sourceHeight == null ? '正在读取源视频规格' : '当前源视频没有可用的高清规格'}</small>}
        </div>
        {supportsInterpolation && <div className="studio-image-ai-panel__row">
          <span>补帧模式</span>
          <StudioCanvasDropdownPopover
            placement="topRight"
            ariaLabel="补帧模式"
            activeKey={interpolationMode}
            items={interpolationItems}
            onSelect={(key) => {
              if (key === 'none' && slowMotionFactor > 1) {
                patchOptions({ interpolationMode: 'none', slowMotionFactor: 1 })
                message.info('关闭补帧后，慢放倍数已恢复为 1×')
                return
              }
              patchOptions({ interpolationMode: key as StudioVideoInterpolationMode })
            }}
            trigger={(
              <StudioPanelSelectTrigger
                label={interpolationMode === 'high_quality' ? '高质量补帧' : '不补帧'}
                ariaLabel="选择补帧模式"
              />
            )}
          />
        </div>}
        {interpolationMode === 'high_quality' ? (
          <div className="studio-image-ai-panel__row">
            <span>目标帧率</span>
            <StudioCanvasDropdownPopover
              placement="topRight"
              ariaLabel="目标帧率"
              activeKey={String(frameRate)}
              items={frameRateItems}
              onSelect={(key) => patchOptions({ frameRate: Number(key) as StudioVideoFrameRate })}
              trigger={<StudioPanelSelectTrigger label={`${frameRate} FPS`} ariaLabel="选择目标帧率" />}
            />
          </div>
        ) : null}
        {supportsSlowMotion && <div className="studio-image-ai-panel__row">
          <span>慢放倍数</span>
          <StudioCanvasDropdownPopover
            placement="topRight"
            ariaLabel="慢放倍数"
            activeKey={String(slowMotionFactor)}
            items={slowMotionItems}
            onSelect={(key) => {
              const factor = Number(key) as StudioVideoSlowMotionFactor
              if (factor > 1 && interpolationMode === 'none') {
                patchOptions({ slowMotionFactor: factor, interpolationMode: 'high_quality' })
                message.info('慢放需要补帧，已开启高质量补帧')
                return
              }
              patchOptions({ slowMotionFactor: factor })
            }}
            trigger={(
              <StudioPanelSelectTrigger
                label={slowMotionFactor === 1 ? '1×（不慢放）' : `${slowMotionFactor}×`}
                ariaLabel="选择慢放倍数"
              />
            )}
          />
        </div>}
      </div>
      <footer className="studio-image-ai-panel__footer">
        {source?.data.mediaUrl ? (
          <video
            className="studio-image-ai-panel__thumb"
            src={resolveMediaPlaybackUrl(source.data.mediaUrl)}
            muted
            playsInline
            preload="metadata"
            onLoadedMetadata={(event) => setSourceMetadata({ url: source.data.mediaUrl!, width: event.currentTarget.videoWidth, height: event.currentTarget.videoHeight })}
            onError={() => setSourceMetadata({ url: source.data.mediaUrl!, width: 0, height: 0 })}
          />
        ) : <span />}
        <div className="studio-image-ai-panel__actions">
          <span className="studio-image-ai-panel__eta">
            {resolution} · {interpolationMode === 'high_quality' ? `${frameRate} FPS` : '不补帧'} · {slowMotionFactor}×
          </span>
          <StudioCreditSendButton
            credits={credits}
            isFree={canvasRuntime ? quote.quote?.isFree === true : Boolean(node.data.modelIsFree)}
            authoritative={Boolean(canvasRuntime && quotedCredits != null)}
            pendingQuote={Boolean(canvasRuntime ? quotedCredits == null : pool.loading || !node.data.model || (!node.data.modelIsFree && credits === 0))}
            estimateLoading={Boolean(canvasRuntime && (pool.loading || quote.loading))}
            estimateLoadingLabel={pool.loading ? '读取模型' : undefined}
            estimateError={canvasRuntime && !pool.loading ? pool.error || quote.error || (quote.quote?.matched === false ? '当前参数没有可用计费规则' : '') : ''}
            quoteStatus={Boolean(canvasRuntime) ? pool.loading ? 'LOADING' : quote.status : undefined}

            showCredits
            hasContent={Boolean(source?.data.mediaUrl && node.data.model)}
            mode="agent"
            loading={operation.busy || processing}
            disabled={!source?.data.mediaUrl || !node.data.model || !resolution || processing}
            className="studio-composer__send"
            onSend={() => { void submit() }}
          />
        </div>
      </footer>
    </div>
  )
}

export default StudioVideoHdPanel
