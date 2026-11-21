'use client'

import { useRef } from 'react'
import { LoadingOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioVideoOperation } from '@/hooks/studio/useStudioVideoOperation'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioCanvasModelsByFunc } from '@/hooks/studio/useStudioCatalog'
import { useCanvasVideoToolQuote } from '@/hooks/studio/useCanvasVideoToolQuote'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { modelSupportsCapability } from '@/utils/modelCapability'
import type { StudioCanvasNode, StudioVideoDepthResolution } from '@/types/studio'
import { resolveStudioDepthSource, STUDIO_DEPTH_CAPTURE_ENABLED } from '@/utils/studio/studioVideoDepth'
import './studio-video-depth.css'

const RESOLUTIONS: StudioVideoDepthResolution[] = ['480', '720']

export function StudioVideoDepthPanel({ node }: { node: StudioCanvasNode }) {
  const nodes = useStudioUiStore((state) => state.nodes)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const operation = useStudioVideoOperation()
  const canvasRuntime = useCanvasProjectRuntime()
  const enabled = Boolean(canvasRuntime) || STUDIO_DEPTH_CAPTURE_ENABLED
  const submitting = useRef(false)
  const source = resolveStudioDepthSource(node, nodes)
  const pool = useStudioCanvasModelsByFunc('video_depth_map', String(canvasRuntime?.project?.projectId || ''), Boolean(canvasRuntime))
  const modelCode = pool.data.find((item) => modelSupportsCapability(item, 'video_depth_map'))?.modelCode
  const quote = useCanvasVideoToolQuote(canvasRuntime && source?.data.mediaUrl && modelCode ? {
    source, targetNodeId: node.id, operation: 'DEPTH_CAPTURE', label: '深度动作捕捉', modelCode
  } : null, canvasRuntime)
  const quotedCredits = studioCanvasQuoteCredits(quote.quote)
  const quoteError = pool.error || quote.error || (quote.quote?.matched === false ? '当前参数没有可用计费规则' : '')
  const resolution = node.data.videoToolOptions?.depthResolution === '720' ? '720' : '480'
  const processing = operation.busy || node.data.status === 'generating' || node.data.status === 'queued'

  const selectResolution = (value: StudioVideoDepthResolution) => {
    if (processing) return
    const current = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
    if (!current) return
    updateNodeData(node.id, { videoToolOptions: { ...current.data.videoToolOptions, depthResolution: value } })
  }
  const submit = async () => {
    if (processing || submitting.current || !enabled || (canvasRuntime && (quotedCredits == null || quote.loading))) return
    const store = useStudioUiStore.getState()
    const current = store.nodes.find((item) => item.id === node.id)
    if (!current || current.data.mediaUrl || ['generating', 'queued'].includes(current.data.status)) return
    const input = resolveStudioDepthSource(current, store.nodes)
    if (!input?.data.mediaUrl) { message.warning('源视频已失效，请重新发起深度动作捕捉'); return }
    submitting.current = true
    try {
      await operation.run({ source: input, targetNodeId: node.id, operation: 'DEPTH_CAPTURE',
        label: '深度动作捕捉', modelCode,
        resolution: current.data.videoToolOptions?.depthResolution === '720' ? '720' : '480' })
    } finally { submitting.current = false }
  }

  return (
    <section className="studio-video-depth-panel nodrag nowheel" aria-label="深度动作捕捉配置" aria-busy={processing}>
      <strong>深度动作捕捉</strong>
      <span>清晰度</span>
      {canvasRuntime ? <small>输出规格由所选深度模型决定</small> : <div className="studio-video-depth-panel__resolutions" role="tablist" aria-label="提取清晰度">
        {RESOLUTIONS.map((value, index) => (
          <button key={value} type="button" role="tab" aria-selected={resolution === value}
            tabIndex={resolution === value ? 0 : -1} disabled={processing}
            onClick={() => selectResolution(value)}
            onKeyDown={(event) => {
              if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
              event.preventDefault()
              const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - index
              selectResolution(RESOLUTIONS[next])
              event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('button')[next]?.focus()
            }}>{value} P</button>
        ))}
      </div>}
      {!enabled ? <small role="status">深度提取服务尚未开通，暂不能提交</small>
        : !source?.data.mediaUrl ? <small role="status">源视频已失效，请重新创建节点</small> : null}
      {canvasRuntime ? <small className="studio-video-depth-panel__price" role="status">
        {quotedCredits != null && !quoteError ? quote.quote?.isFree ? '本次免费' : `本次预计 ${quotedCredits} 积分` : '\u00a0'}
      </small> : null}
      <StudioCanvasTooltip title={quoteError ? '当前功能暂不可用' : !source?.data.mediaUrl ? '请先选择可用的源视频' : undefined}>
      <span className="studio-video-depth-panel__action">
      <button type="button" className="studio-video-depth__confirm" disabled={!enabled || !source?.data.mediaUrl || processing || Boolean(canvasRuntime && quotedCredits == null)}
        onClick={() => { void submit() }}>
        {processing ? <><LoadingOutlined aria-hidden spin /> 提取中…</> : '确认提取'}
      </button>
      </span>
      </StudioCanvasTooltip>
    </section>
  )
}
