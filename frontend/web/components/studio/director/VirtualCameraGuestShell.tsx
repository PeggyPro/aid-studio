'use client'

import { useEffect, useRef, useState } from 'react'
import { CameraOutlined, CompassOutlined, LoadingOutlined, MobileOutlined, SettingOutlined, SlidersOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { resolveDirectorCameraOrientation } from '@/utils/studio/directorCameraPose'
import { smoothLook } from '@/utils/studio/virtualCameraPose'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'
import { useVirtualCameraGuest } from '@/hooks/studio/useVirtualCameraGuest'
import { guestViewStatus, type GuestPermissionState, type GuestSceneState } from './virtualCameraGuestState'
import { VirtualCameraLift, VirtualCameraStick } from './VirtualCameraGuestControls'

const FOCAL_PRESETS = [15, 30, 50, 70, 90]

export function VirtualCameraGuestShell({ sessionId, token, onThemeChange }: { sessionId: string; token: string; onThemeChange?: (theme: StudioFreeTheme) => void }) {
  const [permission, setPermission] = useState<GuestPermissionState>('requesting')
  const [preferWide, setPreferWide] = useState(true)
  const [devicePortrait, setDevicePortrait] = useState(false)
  const [sceneState, setSceneState] = useState<GuestSceneState>('loading')
  const [readyTimedOut, setReadyTimedOut] = useState(false)
  const videoRef=useRef<HTMLVideoElement>(null)
  const [gyro, setGyro] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [gyroNotice, setGyroNotice] = useState('')
  const [gyroMultiplier, setGyroMultiplier] = useState(1)
  const [stability, setStability] = useState(0.35)
  const [turnSpeed, setTurnSpeed] = useState(1)
  const [focalMm, setFocalMm] = useState(50)
  const [focalSlider, setFocalSlider] = useState(false)
  const lift = useRef<-1 | 0 | 1>(0)
  const gyroCalibration = useRef<{ alpha: number; beta: number; yaw: number; pitch: number } | null>(null)
  const smoothedLook = useRef({ yaw: 0, pitch: 0 })
  const rotated = preferWide && devicePortrait
  const layout = !devicePortrait || rotated ? 'landscape' : 'portrait'

  useEffect(() => {
    setPermission(!window.isSecureContext ? 'denied'
      : typeof window.RTCPeerConnection !== 'function' || typeof window.WebSocket !== 'function'
        ? 'unsupported' : 'granted')
  }, [])

  useEffect(() => {
    const portrait = window.matchMedia('(orientation: portrait)')
    const sync = () => setDevicePortrait(portrait.matches)
    sync()
    portrait.addEventListener('change', sync)
    return () => portrait.removeEventListener('change', sync)
  }, [])

  const guest = useVirtualCameraGuest({ sessionId, token, enabled: permission === 'granted', onThemeChange })
  const latestCamera = useRef(guest.camera)
  useEffect(() => { latestCamera.current = guest.camera }, [guest.camera])
  const status = guestViewStatus({ permission, ws: guest.ws, snapshot: Boolean(guest.snapshot), scene: sceneState })
  const ready = status.kind === 'ready'
  const previewPending = status.kind === 'connecting' && sceneState === 'ready' && Boolean(guest.snapshot)
  const playBlocked = Boolean(guest.stream && sceneState === 'error' && ['waiting', 'connected'].includes(guest.ws))
  const canRetry = playBlocked || status.kind === 'load-error' || status.kind === 'disconnected' || readyTimedOut
  useEffect(() => {
    setReadyTimedOut(false)
    if (guest.ws !== 'connected' || ready) return
    const timer = window.setTimeout(() => setReadyTimedOut(true), 8000)
    return () => window.clearTimeout(timer)
  }, [guest.ws, ready])
  const retry = () => {
    setReadyTimedOut(false)
    if (playBlocked && videoRef.current) {
      setSceneState('loading')
      void videoRef.current.play().catch(() => setSceneState('error'))
    } else guest.retry()
  }

  useEffect(() => {
    lift.current = 0
    guest.stopInput()
    guest.patchControl({ layout })
    gyroCalibration.current = null
  }, [guest.patchControl, guest.stopInput, guest.snapshot, layout, ready, rotated])
  useEffect(() => {
    if (!guest.snapshot) return
    setFocalMm(guest.snapshot.lens?.focalLengthMm ?? 50)
  }, [guest.snapshot])
  useEffect(() => {
    if (!gyro || !ready || guest.locked) return
    const handler = (event: DeviceOrientationEvent) => {
      const camera = latestCamera.current
      if (event.alpha === null || event.beta === null || !camera || document.hidden) return
      if (!gyroCalibration.current) {
        const base = resolveDirectorCameraOrientation(camera)
        gyroCalibration.current = { alpha: event.alpha, beta: event.beta, yaw: base.yaw, pitch: base.pitch }
        smoothedLook.current = { yaw: base.yaw, pitch: base.pitch }
      }
      const base = gyroCalibration.current
      const alphaDelta = ((event.alpha - base.alpha + 540) % 360 - 180) * gyroMultiplier
      const betaDelta = (event.beta - base.beta) * gyroMultiplier
      const angle = window.screen.orientation?.angle ?? (window.orientation === -90 ? 270 : 90)
      const reversed = !devicePortrait && angle === 270
      const sign = reversed ? -1 : 1
      const axes = layout === 'landscape'
        ? { yaw: base.yaw - betaDelta * sign, pitch: base.pitch - alphaDelta * sign }
        : { yaw: base.yaw - alphaDelta, pitch: base.pitch + betaDelta }
      smoothedLook.current = smoothLook(smoothedLook.current, axes, stability)
      guest.patchControl({ look: smoothedLook.current })
    }
    const recalibrate = () => { gyroCalibration.current = null }
    window.addEventListener('deviceorientation', handler)
    window.addEventListener('orientationchange', recalibrate)
    document.addEventListener('visibilitychange', recalibrate)
    return () => {
      window.removeEventListener('deviceorientation', handler)
      window.removeEventListener('orientationchange', recalibrate)
      document.removeEventListener('visibilitychange', recalibrate)
    }
  }, [devicePortrait, guest.locked, guest.patchControl, gyro, gyroMultiplier, layout, ready, stability])

  const requestGyro = async (checked: boolean) => {
    setGyroNotice('')
    if (!checked) { setGyro(false); return }
    try {
      const orientation = window.DeviceOrientationEvent as typeof DeviceOrientationEvent & { requestPermission?: () => Promise<'granted' | 'denied'> }
      if (!orientation || orientation.requestPermission && await orientation.requestPermission() !== 'granted') throw new Error('denied')
      gyroCalibration.current = null; setGyro(true)
    } catch {
      setGyro(false)
      setGyroNotice('当前浏览器未授权体感，仍可使用右侧摇杆。')
      setSettingsOpen(true)
    }
  }

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    let active = true
    video.srcObject = guest.stream
    setSceneState('loading')
    if (guest.stream) void video.play().catch(() => { if (active) setSceneState('error') })
    return () => { active = false; video.srcObject = null }
  }, [guest.stream])
  useEffect(() => {
    if (guest.ws !== 'connected' || !guest.snapshot || sceneState !== 'loading') return
    const timeout = setTimeout(() => setSceneState('error'), 15000)
    return () => clearTimeout(timeout)
  }, [guest.ws, guest.snapshot, sceneState])

  const acceptVideoFrame = (video: HTMLVideoElement) => {
    if (!guest.stream || video.srcObject !== guest.stream || video.readyState < 2 || !video.videoWidth) return
    guest.reportVideoFrame(guest.stream)
    setSceneState('ready')
  }

  const chooseFocal = (value: number) => {
    setFocalMm(value)
    guest.patchControl({ focalMm: value })
    guest.sendControl()
  }
  const changeLift = (direction: -1 | 1, active: boolean) => {
    if (!active && lift.current !== direction) return
    lift.current = active ? direction : 0
    guest.patchControl({ crane: lift.current })
  }
  const canControl = ready && !guest.locked
  const layoutAction = rotated ? '切换竖屏布局' : '切换横屏布局'
  const controlKey = [guest.snapshot?.cameraId, layout, rotated].join(':')

  return (
    <main className="virtual-camera-shell">
      <div className={`virtual-camera-console is-${layout}${rotated ? ' is-rotated' : ''}${ready ? ' is-ready' : ''}`}>
        <header className="virtual-camera-topbar">
          <div className="virtual-camera-brand">
            <span className="virtual-camera-brand__icon"><CameraOutlined /></span>
            <div><strong>虚拟相机</strong><span className="virtual-camera-live">机位直连</span></div>
          </div>
          <div className="virtual-camera-actions">
            {devicePortrait ? <button type="button" className="virtual-camera-tool" aria-label={layoutAction}
              onClick={() => setPreferWide(!preferWide)}><MobileOutlined rotate={rotated ? 0 : 90} /></button> : null}
            <button type="button" className="virtual-camera-tool" aria-label="运镜设置" aria-expanded={settingsOpen}
              onClick={() => { setFocalSlider(false); setSettingsOpen(!settingsOpen) }}><SettingOutlined /></button>
            <button type="button" className="virtual-camera-tool" aria-label="体感运镜" aria-pressed={gyro} disabled={!canControl}
              onClick={() => void requestGyro(!gyro)}><CompassOutlined /><span className="virtual-camera-tool__label">体感</span></button>
            <button type="button" className="virtual-camera-record" disabled={!canControl} onClick={guest.toggleRecording}>
              <i aria-hidden="true" className={guest.recording ? 'is-recording' : undefined} />{guest.recording ? '停止' : '录制'}
            </button>
          </div>
        </header>
        <div className="virtual-camera-stage">
          <div className="virtual-camera-viewport">
            <video ref={videoRef} autoPlay muted playsInline aria-label="电脑机位实时画面"
              onLoadedData={event => acceptVideoFrame(event.currentTarget)}
              onPlaying={event => acceptVideoFrame(event.currentTarget)} onError={() => setSceneState('error')} />
            <div className="virtual-camera-frame" aria-hidden="true"><i /><i /><i /><i /></div>
          </div>
          {canControl ? <div className="virtual-camera-lift-bank" key={`lift:${controlKey}`}>
            <VirtualCameraLift direction={1} onChange={changeLift} />
            <VirtualCameraLift direction={-1} onChange={changeLift} />
          </div> : null}
          {ready && guest.locked ? <div className="virtual-camera-lock-hint" role="status">请在电脑端解锁机位</div> : null}
          {canControl ? <footer className="virtual-camera-controls" key={`controls:${controlKey}`}>
            <VirtualCameraStick label="移动" rotated={rotated} onChange={(x, y) => guest.patchControl({ move: { x, y } })} />
            <div className="virtual-camera-lens">
              {focalSlider ? <div className="virtual-camera-lens-adjust">
                <label>焦段 <strong>{Math.round(focalMm)} mm</strong>
                  <input aria-label="自定义焦段" type="range" min={12} max={200} value={Math.round(focalMm)} onChange={event => chooseFocal(event.currentTarget.valueAsNumber)} />
                </label>
                <button className="virtual-camera-tool" type="button" onClick={() => setFocalSlider(false)}>完成</button>
              </div> : null}
              <div className="virtual-camera-focals" role="group" aria-label="焦段">
                {FOCAL_PRESETS.map(value => <button key={value} type="button" aria-label={`${value} 毫米`}
                  aria-pressed={Math.round(focalMm) === value} onClick={() => chooseFocal(value)}>{value}</button>)}
                <button type="button" aria-label="自定义焦段" aria-expanded={focalSlider} onClick={() => {
                  setSettingsOpen(false); setFocalSlider(!focalSlider)
                }}><SlidersOutlined /></button>
              </div>
              <span className="virtual-camera-control-caption">焦段 <b>{Math.round(focalMm)} mm</b></span>
            </div>
            {gyro ? <div className="virtual-camera-gyro-active"><CompassOutlined /><span>体感运镜</span></div>
              : <VirtualCameraStick label="旋转" rotated={rotated} onChange={(x, y) => guest.setTurnInput(x, y, turnSpeed)} />}
          </footer> : null}
          {ready && settingsOpen ? <aside className="virtual-camera-settings" aria-label="运镜设置">
            <strong><SettingOutlined /> 运镜设置</strong>
            <label>摇杆转速 <b>{turnSpeed.toFixed(1)}×</b>
              <input aria-label="摇杆转速" type="range" min={0.4} max={1.6} step={0.1} value={turnSpeed} onChange={event => setTurnSpeed(event.currentTarget.valueAsNumber)} />
            </label>
            <label>体感倍率 <b>{gyroMultiplier.toFixed(1)}×</b>
              <input aria-label="体感倍率" type="range" min={0.3} max={3} step={0.1} value={gyroMultiplier} onChange={event => setGyroMultiplier(event.currentTarget.valueAsNumber)} />
            </label>
            <label>体感平滑
              <input aria-label="体感平滑" type="range" min={0} max={1} step={0.05} value={stability} onChange={event => setStability(event.currentTarget.valueAsNumber)} />
            </label>
            {gyroNotice ? <p role="status">{gyroNotice}</p> : null}
          </aside> : null}
        </div>
        {!ready ? <div className={`virtual-camera-status${previewPending ? ' is-preview' : ''}`}>
          <div className="virtual-camera-status__card">
            <div className="virtual-camera-status__icon" aria-hidden="true">
              {!canRetry && ['connecting', 'loading'].includes(status.kind) ? <LoadingOutlined /> : <CameraOutlined />}
            </div>
            <div role="status">
              <strong>{previewPending ? '已收到机位画面' : '虚拟相机'}</strong>
              <p>{playBlocked ? '请点一下播放电脑传来的机位画面' : readyTimedOut ? '未收到完整的机位画面，请重试' : previewPending ? '正在取得运镜授权…' : guest.errorMessage || status.message}</p>
            </div>
            {canRetry || devicePortrait ? <div className="virtual-camera-status__actions">
              {canRetry ? <Button type="primary" onClick={retry}>{playBlocked ? '播放机位画面' : '重试连接'}</Button> : null}
              {devicePortrait ? <button type="button" className="virtual-camera-tool" onClick={() => setPreferWide(!preferWide)}>{layoutAction}</button> : null}
            </div> : null}
            {status.kind === 'reset' ? <small>在电脑的预演台生成新二维码后扫码。</small> : null}
          </div>
        </div> : null}
      </div>
    </main>
  )
}
