'use client'

import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Button, InputNumber, Modal } from 'antd'
import { captureStudioCanvasVideoFrame } from '@/utils/studio/studioCanvasVideoFrameCapture'
import { fetchCanvasPublicationFile } from '@/utils/canvas/canvasPublicationFiles'
import { UploadOutlined } from '@ant-design/icons'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import type { CanvasResource } from '@/types/canvas-api'
import { useStudioUiStore } from '@/stores/studioUi'
import { apiErrorMessage } from '@/utils/apiError'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import {
  canvasPublicationMediaNodes,
  canvasPublicationSourceKey,
  type CanvasPublicationMediaType,
} from '@/utils/canvas/canvasPublicationMedia'
import { checkFileAgainstUploadLimits } from '@/utils/uploadLimitCheck'
import { loadPublicConfig } from '@/hooks/useAuthPublicConfig'
import { canvasQuoteIdentity } from '@/utils/canvas/canvasQuote'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

export interface CanvasPublicationMediaSelection {
  resource: CanvasResource
  nodeId?: string
  sourceKey?: string
}

export function StudioCanvasPublicationMediaPicker({
  type,
  runtime,
  onClose,
  onSelect,
  video,
}: {
  type: CanvasPublicationMediaType
  runtime: CanvasProjectWorkspaceRuntime
  onClose: () => void
  onSelect: (selection: CanvasPublicationMediaSelection) => void
  video?: CanvasResource | null
}) {
  const nodes = useStudioUiStore((state) => state.nodes)
  const [tab, setTab] = useState<'canvas' | 'upload' | 'frame'>('canvas')
  const [seconds, setSeconds] = useState(0)
  const tabs =
    type === 'IMAGE' && video
      ? (['canvas', 'upload', 'frame'] as const)
      : (['canvas', 'upload'] as const)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const activeRef = useRef(true)
  const workingRef = useRef(false)
  const scope = useRef(useStudioUiStore.getState().scopeKey).current
  const identity = useRef(canvasQuoteIdentity()).current
  const label = type === 'VIDEO' ? '视频' : '封面'
  const candidates = canvasPublicationMediaNodes(nodes, type)
  const current = () =>
    activeRef.current &&
    useStudioUiStore.getState().scopeKey === scope &&
    canvasQuoteIdentity() === identity
  useEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
    }
  }, [])

  async function run(resolve: () => Promise<CanvasPublicationMediaSelection>) {
    if (workingRef.current || !current()) return
    workingRef.current = true
    setWorking(true)
    setError('')
    try {
      const selection = await resolve()
      if (!current()) return
      if (
        selection.resource.mediaType !== type ||
        !selection.resource.resourceId ||
        !selection.resource.resourceUrl
      ) {
        throw new Error(`没有返回有效的${label}素材，请重新选择`)
      }
      onSelect(selection)
    } catch (error) {
      if (current())
        setError(apiErrorMessage(error, `${label}准备失败，请重试`))
    } finally {
      workingRef.current = false
      if (current()) setWorking(false)
    }
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || workingRef.current) return
    if (!file.type.startsWith(type === 'VIDEO' ? 'video/' : 'image/')) {
      setError(`请选择${type === 'VIDEO' ? '视频' : '图片'}文件`)
      return
    }
    await run(async () => {
      const config = await loadPublicConfig()
      const check = checkFileAgainstUploadLimits(file, config?.upload ?? null)
      if (!check.ok) throw new Error(check.message)
      if (!current()) throw new Error('画布已切换，请重新选择')
      return { resource: await runtime.uploadResource(file, type) }
    })
  }

  return (
    <Modal
      open
      title={`选择作品${label}`}
      width={620}
      centered
      destroyOnHidden
      className="studio-publication-picker"
      wrapClassName="studio-publication-modal-wrap"
      footer={null}
      mask={{ closable: !working }}
      closable={!working}
      keyboard={!working}
      onCancel={onClose}
    >
      <div
        className="studio-publication-picker__tabs"
        role="tablist"
        aria-label={`${label}来源`}
      >
        {tabs.map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            id={`publication-${type}-${value}`}
            aria-controls={`publication-${type}-panel`}
            aria-selected={tab === value}
            tabIndex={tab === value ? 0 : -1}
            onKeyDown={(event) => {
              if (
                !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(
                  event.key,
                ) ||
                working
              )
                return
              event.preventDefault()
              const index = tabs.findIndex((item) => item === tab)
              const next =
                event.key === 'Home'
                  ? tabs[0]
                  : event.key === 'End'
                    ? tabs[tabs.length - 1]
                    : tabs[
                        (index +
                          (event.key === 'ArrowRight' ? 1 : -1) +
                          tabs.length) %
                          tabs.length
                      ]
              setTab(next)
              setError('')
              const buttons =
                event.currentTarget.parentElement?.querySelectorAll('button')
              buttons?.[tabs.findIndex((item) => item === next)]?.focus()
            }}
            disabled={working}
            onClick={() => {
              setTab(value)
              setError('')
            }}
          >
            {value === 'canvas'
              ? '从当前画布选择'
              : value === 'upload'
                ? '自定义上传'
                : '从作品视频截帧'}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`publication-${type}-panel`}
        aria-labelledby={`publication-${type}-${tab}`}
        aria-busy={working}
      >
        {tab === 'canvas' ? (
          <>
            <p className="studio-publication__hint">
              选择当前画布中已完成的{type === 'VIDEO' ? '视频' : '图片'}。
            </p>
            {candidates.length ? (
              <div className="studio-publication-picker__grid">
                {candidates.map((node) => (
                  <StudioCanvasTooltip key={node.id} title={node.data.title}>
                    <button
                      type="button"
                      className="studio-publication-picker__item"
                      disabled={working}
                      aria-label={`选择${label}：${node.data.title}`}
                      onClick={() =>
                        void run(async () => {
                          const sourceKey = canvasPublicationSourceKey(node)
                          const isCurrent = () => {
                            const latest = useStudioUiStore
                              .getState()
                              .nodes.find((item) => item.id === node.id)
                            return (
                              current() &&
                              !!latest &&
                              canvasPublicationSourceKey(latest) === sourceKey
                            )
                          }
                          const resource =
                            await runtime.resolveResourceReference(
                              {
                                ...studioNodeToComposerRef(node),
                                mediaKind: type === 'VIDEO' ? 'video' : 'image',
                              },
                              { isCurrent },
                            )
                          if (!isCurrent())
                            throw new Error('源素材已变化，请重新选择')
                          return { resource, nodeId: node.id, sourceKey }
                        })
                      }
                    >
                      {type === 'VIDEO' ? (
                        <video
                          src={node.data.mediaUrl}
                          preload="metadata"
                          muted
                          playsInline
                        />
                      ) : (
                        <img src={node.data.mediaUrl} alt="" loading="lazy" />
                      )}
                      <span>{node.data.title || `未命名${label}`}</span>
                    </button>
                  </StudioCanvasTooltip>
                ))}
              </div>
            ) : (
              <div className="studio-publication-picker__empty">
                当前画布还没有可用的{type === 'VIDEO' ? '视频' : '图片'}
                ，可以切换到自定义上传。
              </div>
            )}
          </>
        ) : tab === 'frame' && video ? (
          <div className="studio-publication-picker__upload">
            <p>选择作品视频中的指定时间，截取原始画面作为封面。</p>
            <label>
              截帧时间（秒）
              <InputNumber
                min={0}
                step={0.1}
                value={seconds}
                disabled={working}
                onChange={(v) => setSeconds(v || 0)}
              />
            </label>
            <Button
              disabled={working}
              loading={working}
              onClick={() =>
                void run(async () => {
                  const blob = await fetchCanvasPublicationFile(
                    video.resourceUrl,
                  )
                  if (!current()) throw new Error('画布已切换')
                  const source = URL.createObjectURL(blob)
                  try {
                    const file = await captureStudioCanvasVideoFrame(
                      source,
                      seconds,
                      'publication-cover.png',
                    )
                    if (!current()) throw new Error('画布已切换')
                    return {
                      resource: await runtime.uploadResource(file, 'IMAGE'),
                    }
                  } finally {
                    URL.revokeObjectURL(source)
                  }
                })
              }
            >
              截取并使用封面
            </Button>
          </div>
        ) : (
          <div className="studio-publication-picker__upload">
            <UploadOutlined aria-hidden />
            <p>
              从设备上传{type === 'VIDEO' ? '视频' : '图片'}作为作品{label}
            </p>
            <Button
              disabled={working}
              loading={working}
              onClick={() => inputRef.current?.click()}
            >
              选择{type === 'VIDEO' ? '视频' : '图片'}文件
            </Button>
            <input
              ref={inputRef}
              type="file"
              accept={type === 'VIDEO' ? 'video/*' : 'image/*'}
              hidden
              onChange={(event) => void upload(event)}
            />
          </div>
        )}
      </div>
      {working && (
        <p role="status" className="studio-publication__hint">
          正在准备{label}，请稍候…
        </p>
      )}
      {error && (
        <p role="alert" className="studio-publication__error">
          {error}
        </p>
      )}
    </Modal>
  )
}
