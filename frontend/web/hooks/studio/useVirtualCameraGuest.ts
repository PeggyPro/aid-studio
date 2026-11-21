'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'
import type { DirectorCamera } from '@/types/studio'
import {
  directorCameraApi,
  directorCameraErrorText,
  DirectorCameraError,
  type DirectorCameraRoom
} from '@/utils/studio/directorCameraApi'
import { DirectorRtcSession } from '@/utils/studio/directorRtcSession'
import { resolveDirectorCameraOrientation } from '@/utils/studio/directorCameraPose'
import {
  parseVirtualCameraMessage,
  type VirtualCameraMessage,
  type VirtualCameraSnapshot
} from '@/utils/studio/virtualCameraMessages'
import { advanceVirtualCameraLook, type VirtualCameraControl } from '@/utils/studio/virtualCameraPose'
import type { GuestSocketState } from '@/components/studio/director/virtualCameraGuestState'

export function useVirtualCameraGuest({
  sessionId,
  token,
  enabled,
  onThemeChange
}: {
  sessionId: string
  token: string
  enabled: boolean
  onThemeChange?: (theme: StudioFreeTheme) => void
}) {
  const [ws, setWs] = useState<GuestSocketState>('idle')
  const [snapshot, setSnapshot] = useState<VirtualCameraSnapshot | null>(null)
  const [camera, setCamera] = useState<DirectorCamera | null>(null)
  const [recording, setRecording] = useState(false)
  const [locked, setLocked] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [generation, setGeneration] = useState(0)
  const [stream, setStream] = useState<MediaStream | null>(null)
  const roomRef = useRef<{ key: string; room: DirectorCameraRoom } | null>(null)
  const joiningRef = useRef<{ key: string; request: Promise<DirectorCameraRoom> } | null>(null)
  const socketRef = useRef<DirectorRtcSession | null>(null)
  const controlRef = useRef<Omit<VirtualCameraControl, 'seq' | 't'>>({
    move: { x: 0, y: 0 },
    crane: 0,
    look: { yaw: 0, pitch: 0 },
    focalMm: 50,
    recording: 'idle',
    layout: 'landscape'
  })
  const seqRef = useRef(0)
  const lastSendAt = useRef<number | null>(null)
  const turnRef = useRef({ x: 0, y: 0, sensitivity: 1 })
  const setTurnInput = useCallback((x: number, y: number, sensitivity: number) => {
    turnRef.current = { x, y, sensitivity }
  }, [])
  const stopInput = useCallback(() => {
    controlRef.current.move = { x: 0, y: 0 }
    controlRef.current.crane = 0
    setTurnInput(0, 0, 1)
    lastSendAt.current = null
  }, [setTurnInput])

  const send = useCallback((message: VirtualCameraMessage) => {
    socketRef.current?.send(message)
  }, [])

  const sendControl = useCallback(() => {
    if (!socketRef.current?.can('CONTROL') || document.hidden) {
      lastSendAt.current = null
      return
    }
    const now = performance.now()
    const previous = lastSendAt.current ?? now
    lastSendAt.current = now
    const turn = turnRef.current
    if (turn.x || turn.y) controlRef.current.look = advanceVirtualCameraLook(controlRef.current.look, turn, (now - previous) / 1000, turn.sensitivity)
    const control: VirtualCameraControl = {
      ...controlRef.current,
      move: { ...controlRef.current.move },
      look: { ...controlRef.current.look },
      seq: ++seqRef.current,
      t: now
    }
    send({ type: 'pose', control })
    controlRef.current.recording = 'idle'
  }, [send])

  const handleMessageRef = useRef<(raw: unknown) => void>(() => {})
  handleMessageRef.current = (raw) => {
    const message = parseVirtualCameraMessage(raw)
    if (!message) return
    if (message.type === 'theme') onThemeChange?.(message.theme)
    else if (message.type === 'snapshot') {
      stopInput()
      setSnapshot(message.snapshot)
      setCamera(message.snapshot.camera)
      const orientation = resolveDirectorCameraOrientation(message.snapshot.camera)
      controlRef.current.look = { yaw: orientation.yaw, pitch: orientation.pitch }
      controlRef.current.focalMm = message.snapshot.lens?.focalLengthMm ?? 50
    } else if (message.type === 'camera') {
      setCamera(message.camera)
      setRecording(message.recording)
      setLocked(false)
    } else if (message.type === 'camera:switch' && message.snapshot) {
      stopInput()
      setSnapshot(message.snapshot)
      setCamera(message.snapshot.camera)
      const orientation = resolveDirectorCameraOrientation(message.snapshot.camera)
      controlRef.current.look = { yaw: orientation.yaw, pitch: orientation.pitch }
      controlRef.current.focalMm = message.snapshot.lens?.focalLengthMm ?? controlRef.current.focalMm
    } else if (message.type === 'kick' || message.type === 'reset') {
      stopInput()
      setWs('reset')
      socketRef.current?.close()
    } else if (message.type === 'error') {
      if (message.code === 'EXPIRED') setWs('reset')
      else if (message.code === 'CAMERA_LOCKED') {
        stopInput()
        setLocked(true)
        setErrorMessage('请先在电脑解锁机位')
      } else {
        setWs('error')
        setErrorMessage(
          message.code === 'FULL' ? '该机位已在其它手机连接' : (message.message ?? '连接中断，请保持网络')
        )
      }
    }
  }

  useEffect(() => {
    if (!enabled) return
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(sessionId) || !/^[a-zA-Z0-9_-]{43}$/.test(token)) {
      setWs('reset')
      setErrorMessage('配对信息无效，请从电脑重新扫码')
      return
    }
    let disposed = false,
      peer: DirectorRtcSession | null = null
    let timer: ReturnType<typeof setInterval> | undefined
    setWs('joining')
    setErrorMessage('正在验证配对信息…')
    setSnapshot(null)
    setStream(null)
    setCamera(null)
    setLocked(false)
    setRecording(false)
    stopInput()
    controlRef.current.recording = 'idle'
    const start = async () => {
      const key = `${sessionId}/${token}`
      const previous = roomRef.current?.key === key ? roomRef.current.room : null
      const join = () => {
        if (joiningRef.current?.key === key) return joiningRef.current.request
        const request = directorCameraApi.join(sessionId, token)
        joiningRef.current = { key, request }
        void request.catch(() => { if (joiningRef.current?.request === request) joiningRef.current = null })
        return request
      }
      const room =
        previous?.guestGrant
          ? { ...previous, ...(await directorCameraApi.state(sessionId, previous.guestGrant)) }
          : await join()
      if (disposed) return
      if (!room.guestGrant) throw new Error('配对授权缺失，请重新扫码')
      roomRef.current = { key, room: { ...room, wsTicket: undefined } }
      setErrorMessage('正在连接电脑信令…')
      joiningRef.current = null
      seqRef.current = 0
      peer = new DirectorRtcSession({
        role: 'guest',
        room,
        grant: room.guestGrant,
        onMessage: (m) => handleMessageRef.current(m),
        onStream: (s) => {
          if (!disposed) setStream(s)
        },
        onState: (state, text) => {
          if (disposed) return
          if (state !== 'connected') stopInput()
          setWs(state)
          setErrorMessage(text ?? (state === 'waiting' ? '正在等待电脑机位上线…' : ''))
        }
      })
      socketRef.current = peer
      setWs('connecting')
      await peer.open(room.wsTicket)
      timer = setInterval(sendControl, 1000 / 30)
    }
    void start().catch((e) => {
      if (!disposed) {
        const rescan = e instanceof DirectorCameraError && ['INVALID_PAIRING', 'INVALID_GRANT', 'GUEST_FULL', 'SESSION_EXPIRED', 'SESSION_UNAVAILABLE'].includes(e.code ?? '')
        setWs(rescan ? 'reset' : 'error')
        setErrorMessage(directorCameraErrorText(e))
      }
    })
    window.addEventListener('blur', stopInput)
    document.addEventListener('visibilitychange', stopInput)
    return () => {
      disposed = true
      clearInterval(timer)
      peer?.close()
      socketRef.current = null
      window.removeEventListener('blur', stopInput)
      document.removeEventListener('visibilitychange', stopInput)
    }
  }, [enabled, generation, sendControl, sessionId, stopInput, token])

  useEffect(() => {
    if (!enabled || (ws !== 'waiting' && (ws !== 'connected' || snapshot))) return
    const timeout = setTimeout(() => {
      socketRef.current?.close()
      setWs('error')
      setErrorMessage(ws === 'waiting'
        ? '局域网视频连接超时，请确认电脑机位仍打开，手机和电脑网络可互通，再点此重试'
        : '电脑机位同步超时，请点此重试')
    }, 20000)
    return () => clearTimeout(timeout)
  }, [enabled, snapshot, ws])

  const reportVideoFrame = useCallback((stream: MediaStream) => socketRef.current?.reportVideoFrame(stream), [])

  const patchControl = useCallback((patch: Partial<Omit<VirtualCameraControl, 'seq' | 't'>>) => {
    controlRef.current = {
      ...controlRef.current,
      ...patch,
      move: patch.move ? { ...patch.move } : controlRef.current.move,
      look: patch.look ? { ...patch.look } : controlRef.current.look
    }
  }, [])

  const toggleRecording = useCallback(() => {
    if (ws !== 'connected' || !snapshot) return
    const next = recording ? 'stop' : 'start'
    controlRef.current.recording = next
    send({ type: 'record', recording: next })
    sendControl()
  }, [recording, send, sendControl, snapshot, ws])

  return {
    reportVideoFrame,
    ws,
    snapshot,
    stream,
    camera,
    recording,
    locked,
    errorMessage,
    patchControl,
    setTurnInput,
    stopInput,
    sendControl,
    toggleRecording,
    retry: () => setGeneration((value) => value + 1)
  }
}
