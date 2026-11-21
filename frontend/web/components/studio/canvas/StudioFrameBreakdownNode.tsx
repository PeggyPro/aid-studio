'use client'

import {
  AppstoreOutlined,
  CloudUploadOutlined,
  LinkOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
  SoundOutlined,
  SwapOutlined,
  ThunderboltOutlined,
  VideoCameraAddOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { useMemo, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioNodeData } from '@/types/studio'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { createTrackedObjectUrl, revokeObjectUrl } from '@/utils/objectUrl'
import { uploadVideoToOssWithToast } from '@/utils/ossUpload'
import {
  isStudioFrameBreakdownVideoCandidate,
  normalizeStudioFrameBreakdownDimensions,
  STUDIO_FRAME_BREAKDOWN_DIMENSIONS,
  toggleStudioFrameBreakdownDimension
} from '@/utils/studio/studioFrameBreakdown'
import { StudioCanvasDropdownPopover, type StudioCanvasDropdownItem } from './StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

const DIMENSION_ICONS: Record<string, ReactNode> = {
  storyboard: <AppstoreOutlined />,
  motion: <ThunderboltOutlined />,
  music: <SoundOutlined />
}

export function StudioFrameBreakdownNode({
  nodeId,
  data
}: {
  nodeId: string
  data: StudioNodeData
}) {
  const canvasRuntime = useCanvasProjectRuntime()
  const inputRef = useRef<HTMLInputElement>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<number | null>(null)
  const [pendingPreviewUrl, setPendingPreviewUrl] = useState<string | null>(null)
  const nodeData = useStudioUiStore((state) => state.nodes.find((node) => node.id === nodeId)?.data) ?? data
  const sourceNode = useStudioUiStore((state) => {
    const sourceNodeId = state.nodes.find((node) => node.id === nodeId)?.data.frameBreakdown?.sourceNodeId
    return sourceNodeId ? state.nodes.find((node) => node.id === sourceNodeId) : undefined
  })
  const hasCanvasVideos = useStudioUiStore((state) => state.nodes.some(
    (candidate) => isStudioFrameBreakdownVideoCandidate(nodeId, candidate)
  ))
  const beginReferencePick = useStudioUiStore((state) => state.beginReferencePick)
  const setFrameBreakdownSource = useStudioUiStore((state) => state.setFrameBreakdownSource)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const readOnly = Boolean(canvasRuntime && !canvasRuntime.canEdit)
  const dimensions = normalizeStudioFrameBreakdownDimensions(nodeData.frameBreakdown?.dimensions)
  const mediaUrl = pendingPreviewUrl || sourceNode?.data.mediaUrl || nodeData.mediaUrl || ''
  const sourceTitle = sourceNode?.data.title || nodeData.frameBreakdown?.sourceTitle || '本地视频'
  const uploading = uploadProgress !== null

  const menuItems = useMemo<StudioCanvasDropdownItem[]>(() => [
    {
      key: 'upload',
      label: '上传视频',
      icon: <CloudUploadOutlined />,
      disabled: readOnly || uploading,
      disabledHint: readOnly ? '当前为只读画布' : undefined
    },
    {
      key: 'canvas',
      label: '从画布选择',
      icon: <LinkOutlined />,
      disabled: readOnly || !hasCanvasVideos || uploading,
      disabledHint: readOnly
        ? '当前为只读画布'
        : !hasCanvasVideos
          ? '画布中暂无可用的视频节点'
          : undefined
    }
  ], [hasCanvasVideos, readOnly, uploading])

  const selectSourceAction = (key: string) => {
    if (key === 'upload') {
      inputRef.current?.click()
      return
    }
    if (key === 'canvas') beginReferencePick(nodeId)
  }

  const sourceMenu = (trigger: ReactNode) => (
    <StudioCanvasDropdownPopover
      items={menuItems}
      onSelect={selectSourceAction}
      trigger={trigger}
      placement="bottom"
      open={menuOpen}
      onOpenChange={setMenuOpen}
      ariaLabel="选择逐帧拉片视频来源"
    />
  )

  const uploadVideo = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || uploading || readOnly) return
    if (!file.type.startsWith('video/')) {
      message.error('请选择视频文件')
      return
    }

    const previewUrl = createTrackedObjectUrl(file)
    setPendingPreviewUrl(previewUrl)
    setUploadProgress(0)
    try {
      const resource = canvasRuntime
        ? await canvasRuntime.uploadResource(file, 'VIDEO')
        : null
      const remoteUrl = resource?.resourceUrl || (
        canvasRuntime ? null : await uploadVideoToOssWithToast(file, setUploadProgress)
      )
      if (!remoteUrl) throw new Error('视频上传未返回有效地址')
      const resourceId = resource?.resourceId ? String(resource.resourceId) : undefined
      setFrameBreakdownSource(nodeId, {
        title: file.name,
        mediaUrl: remoteUrl,
        resourceIds: resourceId ? [resourceId] : [],
        primaryResourceId: resourceId
      })
      message.success('视频已添加，可以选择拆解维度')
    } catch (error: unknown) {
      const detail = error as { msg?: string; message?: string }
      message.error(detail.msg || detail.message || '视频上传失败')
    } finally {
      revokeObjectUrl(previewUrl)
      setPendingPreviewUrl(null)
      setUploadProgress(null)
    }
  }

  const toggleDimension = (dimension: (typeof STUDIO_FRAME_BREAKDOWN_DIMENSIONS)[number]['id']) => {
    const nextDimensions = toggleStudioFrameBreakdownDimension(dimensions, dimension)
    if (nextDimensions.length === dimensions.length && nextDimensions.every((item, index) => item === dimensions[index])) return
    updateNodeData(nodeId, {
      frameBreakdown: {
        ...nodeData.frameBreakdown,
        dimensions: nextDimensions
      }
    }, true)
  }

  const playPreview = () => {
    if (!videoRef.current || uploading) return
    void videoRef.current.play().catch(() => undefined)
  }

  const stopPreview = () => {
    const video = videoRef.current
    if (!video) return
    video.pause()
    try {
      video.currentTime = 0
    } catch {
      // 元数据尚未加载时浏览器可能拒绝设置 currentTime。
    }
  }

  return (
    <section className="studio-frame-breakdown nowheel" aria-label="逐帧拉片配置">
      <header className="studio-frame-breakdown__header">
        <span className="studio-frame-breakdown__mark"><VideoCameraAddOutlined /></span>
        <div>
          <strong>逐帧拉片</strong>
          <small>反向解析成片结构</small>
        </div>
        <span className="studio-frame-breakdown__badge">分析工作台</span>
      </header>

      <div className="studio-frame-breakdown__section-label">
        <span>视频素材</span>
        {mediaUrl ? <StudioCanvasTooltip title={sourceTitle}><small>{sourceTitle}</small></StudioCanvasTooltip> : null}
      </div>

      {mediaUrl ? (
        <div
          className={`studio-frame-breakdown__media${uploading ? ' is-uploading' : ''}`}
          onMouseEnter={playPreview}
          onMouseLeave={stopPreview}
        >
          <video
            ref={videoRef}
            src={resolveMediaPlaybackUrl(mediaUrl)}
            muted
            loop
            playsInline
            preload="metadata"
            aria-label={`${sourceTitle}预览`}
          />
          <span className="studio-frame-breakdown__play-hint"><PlayCircleOutlined /> 悬停预览</span>
          {sourceMenu(
            <button
              type="button"
              className="studio-frame-breakdown__replace nodrag"
              aria-label="替换拉片视频"
              disabled={readOnly || uploading}
            >
              {uploading ? <LoadingOutlined spin /> : <SwapOutlined />}
            </button>
          )}
          {uploading ? (
            <span className="studio-frame-breakdown__upload-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={uploadProgress ?? 0}>
              <i style={{ width: `${Math.max(3, uploadProgress ?? 0)}%` }} />
            </span>
          ) : null}
        </div>
      ) : sourceMenu(
        <button type="button" className="studio-frame-breakdown__empty nodrag" disabled={readOnly}>
          <VideoCameraAddOutlined />
          <strong>添加视频素材</strong>
          <span>上传视频或从当前画布选择</span>
        </button>
      )}

      <div className="studio-frame-breakdown__section-label studio-frame-breakdown__section-label--dimensions">
        <span>拆解维度</span>
        <small>至少保留一项</small>
      </div>
      <div className="studio-frame-breakdown__dimensions" role="group" aria-label="拆解维度">
        {STUDIO_FRAME_BREAKDOWN_DIMENSIONS.map((item) => {
          const active = dimensions.includes(item.id)
          return (
            <StudioCanvasTooltip key={item.id} title={item.description}>
            <button
              type="button"
              className={`nodrag${active ? ' is-active' : ''}`}
              aria-pressed={active}
              disabled={readOnly}
              onClick={() => toggleDimension(item.id)}
            >
              {DIMENSION_ICONS[item.id]}
              <span>{item.label}</span>
            </button>
            </StudioCanvasTooltip>
          )
        })}
      </div>

      <button
        type="button"
        className="studio-frame-breakdown__start nodrag"
        disabled={!mediaUrl || uploading || readOnly}
        onClick={() => message.info('拉片配置已保存，分析服务接入后即可开始拆解')}
      >
        <PlayCircleOutlined />
        <span>{mediaUrl ? '开始拉片' : '添加视频后开始'}</span>
      </button>

      <input
        ref={inputRef}
        type="file"
        accept="video/mp4,video/webm,video/quicktime,video/*"
        hidden
        onChange={(event) => { void uploadVideo(event) }}
      />
    </section>
  )
}
