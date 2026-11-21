'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { CameraOutlined, CaretRightOutlined, QuestionCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { App, Alert, Button, Image, InputNumber, Select, Space } from 'antd'
import QRCode from 'qrcode'
import type { DirectorSceneData } from '@/types/studio'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { isDirectorCameraLocked } from './directorCameraState'
import { DirectorPathTransformFields } from './DirectorPathTransformFields'
import {
  addDirectorPresetToScene,
  startDirectorCameraPathDrawing,
  updateDirectorCameraClip,
  type DirectorPathDrawMode
} from './directorMotionPanelState'
import type { DirectorCameraMovePreset } from '@/utils/studio/directorPathPresets'
import type { DirectorViewportHandle } from './DirectorViewport'
import { DirectorPrevizRenderer } from './directorPrevizRenderer'
import { resolveDirectorCamera } from './directorCameraState'
import type { DirectorCameraVideoSource } from '@/utils/studio/directorRtcSession'
import { createDirectorPreviewFrameClock } from '@/utils/studio/directorPreviewFrameClock'
import { useVirtualCameraHost } from '@/hooks/studio/useVirtualCameraHost'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

const PRESETS: Array<{ value: DirectorCameraMovePreset; label: string }> = [
  { value: 'orbit', label: '环绕' },
  { value: 'half-orbit', label: '半弧' },
  { value: 'dolly-in', label: '推近' },
  { value: 'dolly-out', label: '拉远' },
  { value: 'crane', label: '升降' },
  { value: 'truck', label: '横移' },
  { value: 'spiral', label: '螺旋上升' }
]

const DRAW_MODES: Array<{ value: DirectorPathDrawMode; label: string }> = [
  { value: 'stroke', label: '铅笔' },
  { value: 'circle', label: '圆环' },
  { value: 'line', label: '直线' },
  { value: 'rect', label: '矩形' },
  { value: 'pen', label: '钢笔' }
]

export function DirectorVirtualCameraPanel({
  scene,
  cameraId,
  onChange,
  onPreviewChange,
  getLiveScene,
  onSelect,
  beforePair,
  workspaceId,
  viewport
}: {
  scene: DirectorSceneData
  cameraId: string
  onChange: (scene: DirectorSceneData) => void
  onPreviewChange: (scene: DirectorSceneData, cameraId: string) => void
  getLiveScene?: () => DirectorSceneData
  onSelect: (cameraId: string) => void
  beforePair?: () => Promise<void>
  workspaceId: string
  viewport?: React.RefObject<DirectorViewportHandle | null>
}) {
  const { message } = App.useApp()
  const [video, setVideo] = useState<DirectorCameraVideoSource | null>(null)
  const [previewError, setPreviewError] = useState('')
  const latest = useRef({ scene, cameraId, getLiveScene })
  useEffect(() => {
    if (!viewport) return
    let renderer: DirectorPrevizRenderer
    try {
      renderer = new DirectorPrevizRenderer()
      renderer.resize(960, 540)
      setPreviewError('')
    } catch {
      setPreviewError('当前设备无法启动手机取景画面')
      return
    }
    const listeners = new Set<() => void>()
    let frame = 0
    const frameDue = createDirectorPreviewFrameClock()
    const tick = (now: number) => {
      frame = 0
      if (!listeners.size) return
      if (!document.hidden && frameDue(now)) {
        const runtime = viewport.current?.getRuntime()
        const current = latest.current
        const scene = current.getLiveScene?.() ?? current.scene
        const camera = resolveDirectorCamera(scene, current.cameraId)
        if (runtime && camera) {
          renderer.syncInteractiveCamera(current.cameraId, camera)
          renderer.drawInteractive(runtime, scene, useDirectorPlaybackStore.getState().playhead, false)
          listeners.forEach(listener => listener())
        }
      }
      frame = requestAnimationFrame(tick)
    }
    setVideo({
      canvas: renderer.previewCanvas,
      subscribeFrames: listener => {
        listeners.add(listener)
        if (!frame) frame = requestAnimationFrame(tick)
        return () => {
          listeners.delete(listener)
          if (!listeners.size) {
            cancelAnimationFrame(frame)
            frame = 0
          }
        }
      },
    })
    return () => {
      cancelAnimationFrame(frame)
      listeners.clear()
      renderer.dispose()
    }
  }, [viewport])
  const [duration, setDuration] = useState(3)
  const [selectedClipId, setSelectedClipId] = useState<string>()
  const [qrImage, setQrImage] = useState<{ url: string; data: string }>()
  const [expiredPairingDeadline, setExpiredPairingDeadline] = useState<number>()
  const locked = isDirectorCameraLocked(scene, cameraId)
  const host = useVirtualCameraHost({
    workspaceId,
    video,
    scene,
    cameraId,
    beforePair,
    getLiveScene,
    onPreviewChange,
    onCommitChange: onChange,
    onCameraRejected: onSelect,
    onError: (text) => message.warning(text)
  })
  const boundCameraId = host.session?.cameraId ?? cameraId
  useEffect(() => {
    latest.current = { scene, cameraId: boundCameraId, getLiveScene }
  }, [scene, boundCameraId, getLiveScene])
  const clips = useMemo(
    () =>
      (scene.timeline?.pathClips ?? []).filter(
        (clip) => clip.targetKind === 'camera' && clip.targetId === cameraId
      ),
    [cameraId, scene.timeline?.pathClips]
  )
  const pairingDeadline = host.connection === 'waiting' ? host.session?.pairingExpiresAtMs : undefined
  const pairingExpired = pairingDeadline !== undefined && pairingDeadline === expiredPairingDeadline
  useEffect(() => {
    if (pairingDeadline === undefined) return
    const timer = window.setTimeout(() => setExpiredPairingDeadline(pairingDeadline), Math.max(0, pairingDeadline - Date.now()))
    return () => window.clearTimeout(timer)
  }, [pairingDeadline])
  const selectedClip = clips.find((clip) => clip.id === selectedClipId)

  useEffect(() => {
    if (selectedClipId && clips.some((clip) => clip.id === selectedClipId)) return
    setSelectedClipId(clips.at(-1)?.id)
  }, [clips, selectedClipId])

  useEffect(() => {
    let disposed = false
    if (!host.qrUrl || host.connection !== 'waiting') return
    const url = host.qrUrl
    void QRCode.toDataURL(url, { width: 196, margin: 1, color: { dark: '#111820', light: '#ffffff' } })
      .then((value) => {
        if (!disposed) setQrImage({ url, data: value })
      })
      .catch(() => {
        if (!disposed) setQrImage(undefined)
      })
    return () => {
      disposed = true
    }
  }, [host.connection, host.qrUrl])

  const runPreset = (value: string) => {
    const [mode, preset] = value.split(':') as ['replace' | 'append', DirectorCameraMovePreset]
    try {
      const next = addDirectorPresetToScene(
        scene,
        cameraId,
        preset,
        mode,
        duration,
        useDirectorPlaybackStore.getState().playhead
      )
      onChange(next)
      setSelectedClipId(next.timeline?.pathClips?.filter((clip) => clip.targetId === cameraId).at(-1)?.id)
    } catch (error) {
      message.warning((error as Error).message)
    }
  }

  const recordLabel = host.recording ? '停止并保存'
    : host.recordingStatus === 'error' && host.recordingTake ? '重试保存' : '录制运镜'
  const recordDisabled = host.connection !== 'connected' || locked || host.recordingBusy || (!host.recording && !!host.recordingStartBlockedReason)
  const recordHint = host.recordingBusy ? '正在处理录制，请稍候'
    : host.recording ? '停止录制并保存到当前时间轴'
      : locked ? '请先解锁当前机位' : host.recordingStartBlockedReason || '将手机运镜录制到当前画布的动画时间轴'
  const pairLabel = host.connection === 'error' ? '重试连接'
    : host.session?.guestOnline ? '重新配对' : '刷新二维码'
  const pairBlockedReason = previewError || (host.recording || host.recordingBusy ? '请先结束当前录制'
    : host.connection === 'creating' ? '正在建立配对会话' : '')
  const pairHint = pairBlockedReason || (host.session?.guestOnline ? '断开当前手机并生成新二维码' : '生成新的手机配对二维码')
  const recordStatus = host.recordingStatus === 'starting' ? '正在确认开始录制…'
    : host.recordingStatus === 'saving' ? '正在保存并同步时间轴…'
      : host.recordingTake && host.recordingStatus === 'recording'
        ? `正在录制 · 已保存 ${host.recordingTake.frameCount} 帧` : ''
  const savedTakes = host.recordingDrafts.filter(take => take.state !== 'DISCARDED')

  return (
    <div className="director-inspector__scroll director-motion-panel">
      <section
        className="director-control-section director-control-section--flat director-virtual-camera"
        aria-label="虚拟相机"
      >
        <div className="director-section-title">
          <span className="director-virtual-camera__heading">
            <strong>虚拟相机</strong>
            <StudioCanvasTooltip title="手机和电脑连接同一个 Wi-Fi 后扫码。配对和运镜时请保持此面板打开。">
              <button type="button" className="director-virtual-camera__help" aria-label="虚拟相机连接说明"><QuestionCircleOutlined /></button>
            </StudioCanvasTooltip>
          </span>
          <span
            className={`director-virtual-camera__status${host.connection === 'connected' ? ' is-connected' : ''}`}
          >
            <i />
            {previewError ? '取景不可用' : host.connection === 'connected'
              ? '已连接'
              : host.connection === 'creating'
                ? '正在建会话'
                : host.connection === 'error' ? '连接失败' : '等待连接'}
          </span>
        </div>
        {host.connection === 'connected' ? (
          <div role="status" className="director-virtual-camera__connected">手机已连接 · 当前机位实时预览</div>
        ) : host.connection === 'waiting' && !host.session?.guestOnline && !pairingExpired && host.qrUrl && qrImage?.url === host.qrUrl ? (
          <div className="director-virtual-camera__qr">
            <Image preview={false} src={qrImage.data} alt="手机虚拟相机连接二维码" />
          </div>
        ) : (
          <div role="status" className="director-virtual-camera__connected">
            {previewError || (host.connection === 'error' ? host.connectionError || '信令会话暂不可用'
              : host.connection === 'creating' ? '正在建立信令会话…'
                : host.session?.guestOnline ? '手机已扫码，正在建立局域网直连…'
                  : pairingExpired ? '二维码已过期，请刷新'
                    : !host.session?.pairingCode ? '请生成新二维码'
                      : !host.qrUrl ? '当前环境缺少手机可访问的 HTTPS 入口' : '正在准备连接二维码…')}
          </div>
        )}
        <div className="director-virtual-camera__actions">
          <StudioCanvasTooltip title={recordHint}>
            <span tabIndex={recordDisabled ? 0 : undefined}>
              <Button size="small" icon={<CameraOutlined />} danger={host.recording} loading={host.recordingBusy}
                disabled={recordDisabled}
                onClick={host.toggleRecording}>{recordLabel}</Button>
            </span>
          </StudioCanvasTooltip>
          <StudioCanvasTooltip title={pairHint}>
            <span tabIndex={pairBlockedReason ? 0 : undefined}>
              <Button size="small" icon={<ReloadOutlined />} loading={host.refreshing}
                disabled={!!pairBlockedReason}
                onClick={host.refreshQr}>{pairLabel}</Button>
            </span>
          </StudioCanvasTooltip>
        </div>
        {recordStatus && <div role="status" className="director-virtual-camera__record-status">{recordStatus}</div>}
        {host.recordingError && <Alert type="warning" showIcon message={host.recordingError} />}
        <details className="director-virtual-camera__history">
          <summary><CaretRightOutlined aria-hidden />录制与恢复{savedTakes.length > 0 && <span>{savedTakes.length}</span>}</summary>
          <div className="director-virtual-camera__history-tools">
            <StudioCanvasTooltip title="断线后可恢复服务端已保存的片段。请先重新连接原机位，恢复内容会插入当前播放位置。">
              <button type="button" className="director-virtual-camera__help" aria-label="录制恢复说明"><QuestionCircleOutlined /></button>
            </StudioCanvasTooltip>
            <Button size="small" type="text" loading={host.recordingsLoading} disabled={host.recordingBusy}
              onClick={() => void host.refreshRecordings()}>刷新录制</Button>
          </div>
          {savedTakes.map(take => <div key={take.takeId} className="director-virtual-camera__take">
            <div>{(take.durationMs / 1000).toFixed(1)} 秒 · {take.frameCount} 帧 · {take.state === 'APPLIED' ? '已应用' : take.state === 'READY' ? '已保存待应用' : '待恢复'}{take.recovered ? '（保存前缀）' : ''}</div>
            <Space wrap>
              <StudioCanvasTooltip title={take.state !== 'APPLIED' && host.connection !== 'connected' ? '请先重新连接原机位' : undefined}>
                <span><Button size="small" disabled={host.recordingBusy || host.recording || (take.state !== 'APPLIED' && host.connection !== 'connected')}
                  onClick={() => host.recoverRecording(take)}>{take.state === 'APPLIED' ? '同步录制' : '恢复片段'}</Button></span>
              </StudioCanvasTooltip>
              {take.state !== 'APPLIED' && <Button danger size="small" disabled={host.recordingBusy || host.recording} onClick={() => host.discardRecording(take)}>丢弃草稿</Button>}
            </Space>
          </div>)}
          {!savedTakes.length && !host.recordingsLoading && <p className="director-virtual-camera__record-status">暂无已保存录制</p>}
          {host.recordingCursor && <Button size="small" loading={host.recordingsLoading} onClick={() => void host.refreshRecordings(host.recordingCursor!)}>更多录制</Button>}
        </details>
      </section>

      <section
        className={`director-control-section director-control-section--flat${locked ? ' is-locked' : ''}`}
        aria-label="预设运镜"
      >
        <div className="director-section-title">
          <strong>预设运镜</strong>
        </div>
        <label className="director-field">
          <span>时长</span>
          <Space.Compact>
            <InputNumber
              size="small"
              min={0.2}
              step={0.1}
              value={duration}
              disabled={locked}
              onChange={(value) => value !== null && setDuration(Math.max(0.2, value))}
            />
            <span className="director-field-unit">秒</span>
          </Space.Compact>
        </label>
        <Select<string>
          aria-label="选择预设运镜"
          placeholder="选择替换或追加运镜"
          disabled={locked}
          value={null}
          onChange={runPreset}
          options={[
            {
              label: '替换运镜',
              options: PRESETS.map((item) => ({ value: `replace:${item.value}`, label: item.label }))
            },
            {
              label: '追加运镜',
              options: PRESETS.map((item) => ({ value: `append:${item.value}`, label: item.label }))
            }
          ]}
        />
      </section>

      <section
        className={`director-control-section director-control-section--flat${locked ? ' is-locked' : ''}`}
        aria-label="创建运动轨迹"
      >
        <div className="director-section-title">
          <strong>创建运动轨迹</strong>
        </div>
        <Select<DirectorPathDrawMode>
          aria-label="选择轨迹绘制工具"
          placeholder="铅笔 / 圆环 / 直线 / 矩形 / 钢笔"
          disabled={locked}
          value={null}
          options={DRAW_MODES}
          onChange={(mode) => {
            try {
              onChange(startDirectorCameraPathDrawing(scene, cameraId, mode))
            } catch (error) {
              message.warning((error as Error).message)
            }
          }}
        />
      </section>

      <section className="director-control-section director-control-section--flat" aria-label="选择轨迹片段">
        <div className="director-section-title">
          <strong>当前片段</strong>
        </div>
        <Select
          aria-label="选择轨迹片段"
          placeholder="本机位暂无轨迹"
          value={selectedClipId}
          options={clips.map((clip, index) => ({
            value: clip.id,
            label: `${index + 1}. ${clip.preset ? PRESETS.find((item) => item.value === clip.preset)?.label : '自定义轨迹'} · ${clip.start.toFixed(1)}–${clip.end.toFixed(1)}s`
          }))}
          onChange={setSelectedClipId}
        />
      </section>

      <DirectorPathTransformFields
        clip={selectedClip}
        disabled={locked}
        onChange={(clip) => {
          try {
            onChange(updateDirectorCameraClip(scene, clip))
          } catch (error) {
            message.warning((error as Error).message)
          }
        }}
      />
    </div>
  )
}
