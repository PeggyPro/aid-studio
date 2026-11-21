import { createRandomUuid } from '@/utils/clientId'
import { DIRECTOR_LIVE_PREVIEW_FPS } from './directorPreviewFrameClock'
import { resolveCanvasApiUrl } from '@/utils/canvas/canvasApiClient'
import { canvasCollaborationWebsocketUrl } from '@/utils/canvas/canvasCollaborationSocket'
import { directorCameraApi, directorCameraErrorText, DirectorCameraError, type DirectorCameraRoom, type DirectorCameraLease } from './directorCameraApi'
import { parseVirtualCameraMessage, type VirtualCameraMessage } from './virtualCameraMessages'

function isLanAddress(address: string): boolean {
  if (/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+local$/i.test(address) && address.length <= 253) return true
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(address)) {
    const octets = address.split('.').map(Number)
    if (octets.some(value => value > 255)) return false
    const [a, b] = octets
    return a === 10 || a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168 || a === 169 && b === 254
  }
  if (!/^(f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):[0-9a-f:]+$/i.test(address)) return false
  try { return Boolean(new URL('http://[' + address + ']')) } catch { return false }
}

export function isDirectorLanCandidate(candidate: RTCIceCandidateInit): boolean {
  const fields = (candidate.candidate ?? '').trim().split(/\s+/)
  return fields[6] === 'typ' && fields[7] === 'host' && isLanAddress(fields[4] ?? '')
}

interface CandidateStats {
  address?: string
  ip?: string
  ipAddress?: string
  candidateType?: string
  port?: number
  protocol?: string
}
interface PairStats {
  localCandidateId: string
  remoteCandidateId: string
  selected?: boolean
  nominated?: boolean
  state?: string
}

function lanStatsCandidate(candidate: CandidateStats | undefined, advertised: RTCIceCandidateInit[]) {
  if (!candidate?.candidateType) return 'unknown'
  const reportedAddress = candidate.address || candidate.ip || candidate.ipAddress
  const address = reportedAddress === '0.0.0.0' || reportedAddress === '::' ? undefined : reportedAddress
  const type = candidate.candidateType
  if (!['host', 'prflx'].includes(type) || address && !isLanAddress(address)) return 'rejected'
  if (type === 'host' && address) return 'verified'
  if (!candidate.port || !candidate.protocol) {
    return type === 'host' && advertised.length > 0 && advertised.every(isDirectorLanCandidate) ? 'verified' : 'unknown'
  }
  const signaledSocket = advertised.some(announcement => {
    if (!isDirectorLanCandidate(announcement)) return false
    const [, , protocol, , host, port] = (announcement.candidate ?? '').trim().split(/\s+/)
    const mdns = host.toLowerCase().endsWith('.local')
    return protocol.toLowerCase() === candidate.protocol?.toLowerCase()
      && Number(port) === candidate.port
      && (address ? host.toLowerCase() === address.toLowerCase() || mdns : type === 'host' || mdns)
  })
  return signaledSocket ? 'verified' : 'unknown'
}

/** Keep LAN proof independent of browser-specific counters for decoded video. */
export function directorLanVideoReady(
  stats: RTCStatsReport,
  role: 'host' | 'guest',
  local: RTCIceCandidateInit[],
  remote: RTCIceCandidateInit[],
  selectedPair?: RTCIceCandidatePair | null,
  receivedFrame = false
): boolean {
  const transportPairs = new Set<string>()
  const mediaTransports = new Set<string>()
  const candidates: PairStats[] = []
  let hasVideo = role === 'guest' && receivedFrame
  stats.forEach(stat => {
    if (stat.type === 'transport' && stat.selectedCandidatePairId) transportPairs.add(stat.selectedCandidatePairId)
    if (stat.type === 'candidate-pair' && (stat.selected || stat.nominated && stat.state === 'succeeded')) candidates.push(stat)
    if ((stat.kind ?? stat.mediaType) === 'video' && stat.type === (role === 'host' ? 'outbound-rtp' : 'inbound-rtp')) {
      if (stat.transportId) mediaTransports.add(stat.transportId)
      const frames = role === 'host' ? [stat.framesEncoded, stat.framesSent] : [stat.framesDecoded, stat.framesReceived, stat.framesRendered]
      hasVideo ||= frames.some(value => Number.isFinite(value) && value > 0)
        || (Number.isFinite(stat.frameWidth) && stat.frameWidth > 0 && Number.isFinite(stat.frameHeight) && stat.frameHeight > 0)
    }
  })
  if (!hasVideo) return false

  const mediaPairs = new Set([...mediaTransports].map(id => stats.get(id)?.selectedCandidatePairId).filter(Boolean))
  const pairs = mediaPairs.size ? mediaPairs : transportPairs
  if (pairs.size > 1) return false
  const explicit = candidates.filter(candidate => candidate.selected)
  const fallback = explicit.length ? explicit : candidates
  const pair = pairs.size === 1 ? stats.get(pairs.values().next().value!) as PairStats | undefined
    : fallback.length === 1 ? fallback[0] : undefined
  const fromNative = (candidate?: RTCIceCandidate): CandidateStats | undefined => candidate ? {
    candidateType: candidate.type ?? undefined,
    address: candidate.address ?? undefined,
    port: candidate.port ?? undefined,
    protocol: candidate.protocol ?? undefined
  } : undefined
  const verify = (record: CandidateStats | undefined, native: RTCIceCandidate | undefined, advertised: RTCIceCandidateInit[]) => {
    const proof = lanStatsCandidate(record, advertised)
    // The video transport's selected stats are authoritative. Some browsers
    // expose a different privacy-filtered alias through the native ICE view.
    return proof === 'unknown' ? lanStatsCandidate(fromNative(native), advertised) === 'verified' : proof === 'verified'
  }
  return verify(pair && stats.get(pair.localCandidateId), selectedPair?.local, local)
    && verify(pair && stats.get(pair.remoteCandidateId), selectedPair?.remote, remote)
}

export function directorSignalingUrl(path: string): string {
  const directApiBase = process.env.NEXT_PUBLIC_CANVAS_API_BASE_URL
  const devProxy = process.env.AID_DEV_WS_PROXY
  const useDirect = process.env.NODE_ENV === 'development' && (devProxy === '0' || devProxy === 'false')
    && /^https?:\/\//i.test(directApiBase || '') && !/^\w+:\/\//i.test(path)
  const source = new URL(canvasCollaborationWebsocketUrl(useDirect ? resolveCanvasApiUrl(path) : path))
  source.search = ''
  source.hash = ''
  return source.href
}
export interface DirectorCameraVideoSource {
  canvas: HTMLCanvasElement
  subscribeFrames: (listener: () => void) => () => void
}

type Options = {
  role: 'host' | 'guest'
  room: DirectorCameraRoom
  grant?: string
  video?: DirectorCameraVideoSource
  onMessage: (m: VirtualCameraMessage) => void
  onState: (s: 'waiting' | 'connected' | 'error', message?: string) => void
  onRoomState?: (room: DirectorCameraRoom) => void
  onStream?: (s: MediaStream | null) => void
}
/** One owner for ticket authentication, LAN-only negotiation, peer binding and short-lived control authority. */
export class DirectorRtcSession {
  room: DirectorCameraRoom
  private ws: WebSocket | null = null
  private pc: RTCPeerConnection | null = null
  private channels: Record<string, RTCDataChannel> = {}
  private stream: MediaStream | null = null
  private releaseVideoFrame: (() => void) | null = null
  private authenticationTimeout?: ReturnType<typeof setTimeout>
  private authenticated = false
  private beforeAuth: unknown[] = []
  private earlySignals: Array<{ roomEpoch: string; peerEpoch: number; negotiationId: string; messageId: string }> = []
  private guestWaitTimeout?: ReturnType<typeof setTimeout>
  private peerFailure?: string
  private ping?: ReturnType<typeof setInterval>
  private tickTimer?: ReturnType<typeof setInterval>
  private leaseTimer?: ReturnType<typeof setInterval>
  private disconnectTimer?: ReturnType<typeof setTimeout>
  private negotiationTimer?: ReturnType<typeof setTimeout>
  private lease: DirectorCameraLease | null = null
  private deadline = 0
  private renewingEpoch: string | null = null
  private stopped = false
  private failed = false
  private chain = Promise.resolve()
  private negotiationSeen = ''
  private negotiating = false
  private readyReported = false
  private remoteVideoTrack?: MediaStreamTrack
  private receivedFrame = false
  private checkingPeer: RTCPeerConnection | null = null
  private hello = false
  private peerHello = false
  private guestAnnounced = false
  private offerSent = false
  private candidates: RTCIceCandidateInit[] = []
  private queued: RTCIceCandidateInit[] = []
  private local: RTCIceCandidateInit[] = []
  private remote: RTCIceCandidateInit[] = []
  private poseSeq = 0
  private lastPoseSeq = -1
  private applied = new Set<string>()
  constructor(private o: Options) {
    this.room = o.room
  }
  async open(ticket?: string) {
    if (this.stopped) return
    const response = ticket
      ? { wsTicket: ticket, signalingPath: this.room.signalingPath }
      : await directorCameraApi.ticket(this.room.sessionId, this.o.grant)
    if (this.stopped) return
    const ws = (this.ws = new WebSocket(directorSignalingUrl(response.signalingPath)))
    if (this.o.role === 'guest') this.guestWaitTimeout = setTimeout(() => {
      if (this.room.state !== 'READY') this.fail('机位连接超时，请在电脑保持配对面板打开后重试')
    }, 22000)
    this.authenticationTimeout = setTimeout(() => this.fail('信令认证超时，请重试'), 10000)
    ws.onmessage = (e) => {
      if (this.stopped || this.ws !== ws || typeof e.data !== 'string' || e.data.length > 131072) return
      this.chain = this.chain
        .then(async () => {
          const message = JSON.parse(e.data)
          const epoch = message.peerEpoch ?? message.data?.peerEpoch
          try {
            await this.signalMessage(message, response.wsTicket)
          } catch (error) {
            if (epoch !== undefined && epoch !== this.room.peerEpoch) return
            throw error
          }
        })
        .catch(error => this.fail(directorCameraErrorText(error)))
    }
    ws.onerror = () => {
      if (!this.stopped) this.fail(this.authenticated ? '信令连接已中断，请重试' : '信令握手失败，请检查当前站点的 WebSocket 网关和来源配置')
    }
    ws.onclose = () => {
      if (!this.stopped) this.fail('连接已断开，请重新连接')
    }
  }
  private raw(type: string, payload: unknown = {}, messageId = createRandomUuid()) {
    if (this.stopped || this.ws?.readyState !== 1) return
    const r = this.room
    this.ws.send(
      JSON.stringify({
        protocolVersion: 1,
        type,
        messageId,
        sessionId: r.sessionId,
        roomEpoch: r.roomEpoch,
        peerEpoch: r.peerEpoch,
        negotiationId: r.negotiationId,
        payload
      })
    )
  }
  private async signalMessage(m: any, ticket: string) {
    if (m.type === 'AUTH_REQUIRED') {
      this.ws?.send(JSON.stringify({ type: 'AUTH', ticket }))
      return
    }
    if (m.type === 'ERROR') {
      const error = new DirectorCameraError(m.message, m.code)
      if (m.fatal) this.fail(directorCameraErrorText(error))
      else if (['RTC_NEGOTIATION_FAILED', 'NETWORK_POLICY_REJECTED', 'VIDEO_NOT_READY'].includes(m.code))
        this.failPeer(directorCameraErrorText(error), m.code)
      return
    }
    if (m.type === 'CONNECTED') {
      if (this.authenticated || this.failed) return
      clearTimeout(this.authenticationTimeout)
      this.authenticated = true
      this.ping = setInterval(() => this.raw('PING'), 5000)
      await this.acceptRoom(m.data)
      for (const pending of this.beforeAuth.splice(0)) await this.signalMessage(pending, ticket)
      if (this.o.role === 'guest' && this.room.state === 'WAITING') this.raw('NEGOTIATION_REQUEST')
      return
    }
    if (!this.authenticated) {
      if (this.beforeAuth.length < 132) this.beforeAuth.push(m)
      return
    }
    if (this.failed || this.stopped) return
    if (m.type === 'SESSION_STATE') {
      await this.acceptRoom(m.data)
      return
    }
    if (m.type === 'AUTH_LEASE') {
      this.acceptLease(m.data, performance.now())
      return
    }
    if (m.type === 'SIGNAL_ACK' || m.type === 'PONG') return
    if (['SESSION_CLOSED', 'ENDPOINT_REPLACED'].includes(m.type)) {
      this.fail('会话已关闭或被替换，请重新扫码')
      return
    }
    if (m.type === 'NEGOTIATION_REQUEST' && this.o.role === 'host') {
      await this.negotiate()
      return
    }
    if (!['RTC_OFFER', 'RTC_ANSWER', 'ICE_CANDIDATE'].includes(m.type) || m.roomEpoch !== this.room.roomEpoch
      || !Number.isSafeInteger(m.peerEpoch) || m.peerEpoch < this.room.peerEpoch) return
    if (m.peerEpoch > this.room.peerEpoch || !this.pc && this.room.state === 'NEGOTIATING') {
      if (this.earlySignals.length < 132 && !this.earlySignals.some(signal => signal.messageId === m.messageId))
        this.earlySignals.push(m)
      return
    }
    const pc = this.pc
    if (!pc || m.negotiationId !== this.room.negotiationId) return
    if (m.type === 'RTC_OFFER' && this.o.role === 'guest') {
      await pc.setRemoteDescription(m.payload)
      for (const c of this.queued.splice(0)) await pc.addIceCandidate(c)
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      if (this.pc !== pc) return
      this.raw('RTC_ANSWER', { type: 'answer', sdp: answer.sdp })
      this.flush()
    } else if (m.type === 'RTC_ANSWER' && this.o.role === 'host') {
      await pc.setRemoteDescription(m.payload)
      for (const c of this.queued.splice(0)) await pc.addIceCandidate(c)
    } else if (m.type === 'ICE_CANDIDATE') {
      if (!isDirectorLanCandidate(m.payload)) {
        this.fail('网络不符合局域网直连要求')
        return
      }
      this.remote.push(m.payload)
      if (pc.remoteDescription) await pc.addIceCandidate(m.payload)
      else this.queued.push(m.payload)
    }
    if (m.messageId) this.raw('SIGNAL_ACK', { messageId: m.messageId })
  }
  private async acceptRoom(data: DirectorCameraRoom) {
    if (this.failed || data?.sessionId !== this.room.sessionId || data.roomEpoch !== this.room.roomEpoch
      || !Number.isSafeInteger(data.stateVersion) || data.stateVersion < this.room.stateVersion) return
    const previous = this.room
    this.room = { ...previous, ...data }
    this.o.onRoomState?.(this.room)
    if (data.state === 'NEGOTIATING') {
      if (data.negotiationId && data.negotiationId !== this.negotiationSeen) await this.setup()
    } else if (data.state === 'READY') {
      clearTimeout(this.negotiationTimer)
      clearTimeout(this.guestWaitTimeout)
      if (this.pc && previous.state !== 'READY') {
        this.o.onState('connected')
        if (this.o.role === 'host') void this.renew().catch(() => undefined)
      }
    } else {
      this.closePeer()
      if (data.state === 'SUSPENDED') {
        this.o.onState('error', this.peerFailure ?? '局域网直连已暂停，请重试或在电脑重新配对')
      } else if (['CLOSED', 'EXPIRED'].includes(data.state)) {
        this.fail('会话已关闭或已过期，请重新扫码')
      } else {
        this.o.onState('waiting', data.hostOnline && data.guestOnline ? '正在建立局域网直连…' : undefined)
        if (data.state === 'WAITING') await this.negotiate()
      }
    }
  }
  async negotiate() {
    if (
      this.stopped ||
      this.failed ||
      this.negotiating ||
      this.o.role !== 'host' || !this.authenticated || !this.room.hostOnline || !this.room.guestOnline ||
      !['WAITING', 'SUSPENDED'].includes(this.room.state)
    )
      return
    this.negotiating = true
    try {
      const room = await directorCameraApi.negotiate(this.room)
      if (this.stopped) return
      if (room.stateVersion < this.room.stateVersion) return
      await this.acceptRoom(room)
    } catch (error) {
      if (!(error instanceof DirectorCameraError) || !['STALE_EPOCH', 'PEER_OFFLINE'].includes(error.code ?? '')) throw error
    } finally {
      this.negotiating = false
    }
  }
  private async setup() {
    this.closePeer()
    if (this.stopped || !this.room.peerBindingNonce || !this.room.negotiationId) return
    this.negotiationSeen = this.room.negotiationId
    clearTimeout(this.guestWaitTimeout)
    this.peerFailure = undefined
    this.hello = false
    this.peerHello = false
    this.guestAnnounced = false
    this.readyReported = false
    this.lastPoseSeq = -1
    this.poseSeq = 0
    this.offerSent = false
    this.candidates = []
    this.queued = []
    this.local = []
    this.remote = []
    this.applied.clear()
    const pc = (this.pc = new RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle' }))
    this.o.onState('waiting', '正在建立局域网直连…')
    const until = this.room.negotiationUntilMs
    const remaining = typeof until === 'number' ? Math.max(0, Math.min(15000, until - this.room.serverTimeMs)) : 15000
    this.negotiationTimer = setTimeout(() => {
      if (this.pc !== pc || this.room.state === 'READY') return
      const linked = pc.connectionState === 'connected' || ['connected', 'completed'].includes(pc.iceConnectionState)
      const message = !linked ? '局域网直连未建立。请确认手机已连上 Wi-Fi，且路由器允许设备互访，再重试'
        : !this.hello || !this.peerHello ? '视频通道已建立，但机位控制握手超时，请重试'
          : this.readyReported ? '已收到视频，正在等待另一端确认机位，请重试'
            : '机位直连已建立，但尚未核验到可用视频，请确认电脑预演窗口仍打开并重试'
      this.failPeer(message, 'NEGOTIATION_TIMEOUT')
    }, remaining)
    pc.onicecandidate = (e) => {
      if (this.pc !== pc) return
      if (e.candidate && isDirectorLanCandidate(e.candidate)) {
        const c = e.candidate.toJSON()
        this.local.push(c)
        if (this.offerSent) this.raw('ICE_CANDIDATE', c)
        else this.candidates.push(c)
      }
    }
    const connectionChanged = () => {
      if (this.pc !== pc) return
      if (pc.connectionState === 'failed' || pc.iceConnectionState === 'failed') {
        this.failPeer('未能建立局域网直连，请点重试', 'ICE_FAILED')
      } else if (pc.connectionState === 'disconnected' || pc.iceConnectionState === 'disconnected') {
        this.disconnectTimer ??= setTimeout(() => {
          if (this.pc === pc) this.failPeer('局域网直连已中断，请点重试', 'ICE_DISCONNECTED')
        }, 3000)
      } else {
        clearTimeout(this.disconnectTimer)
        this.disconnectTimer = undefined
      }
    }
    pc.onconnectionstatechange = connectionChanged
    pc.oniceconnectionstatechange = connectionChanged
    if (this.o.role === 'host') {
      if (!this.o.video?.canvas.captureStream) throw new Error('浏览器不支持视频传输')
      const manualFrames = typeof CanvasCaptureMediaStreamTrack !== 'undefined'
        && typeof CanvasCaptureMediaStreamTrack.prototype.requestFrame === 'function'
      this.stream = this.o.video.canvas.captureStream(manualFrames ? 0 : DIRECTOR_LIVE_PREVIEW_FPS)
      const track = this.stream.getVideoTracks()[0] as CanvasCaptureMediaStreamTrack | undefined
      if (!track) throw new Error('浏览器未能建立视频轨道')
      this.releaseVideoFrame = this.o.video.subscribeFrames(() => {
        if (manualFrames && this.pc === pc && this.can('PREVIEW')) track.requestFrame()
      })
      track.enabled = false
      pc.addTransceiver(track, { direction: 'sendonly', streams: [this.stream] })
      this.wire(pc.createDataChannel('pose', { ordered: false, maxRetransmits: 0 }))
      this.wire(pc.createDataChannel('state', { ordered: true }))
      await this.renew()
      if (this.pc !== pc) return
      track.enabled = this.can('PREVIEW')
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      if (this.pc !== pc) return
      this.raw('RTC_OFFER', { type: 'offer', sdp: offer.sdp })
      this.flush()
      this.leaseTimer = setInterval(() => {
        void this.renew().catch(() => {
          if (this.pc === pc && performance.now() > this.deadline) this.failPeer('运镜授权已过期，请重新连接', 'LEASE_EXPIRED')
        })
      }, 2000)
    } else {
      pc.ondatachannel = (e) => this.wire(e.channel)
      pc.ontrack = (e) => {
        if (this.pc !== pc || e.track.kind !== 'video') return
        this.remoteVideoTrack = e.track
        this.o.onStream?.(e.streams[0] ?? new MediaStream([e.track]))
      }
    }
    const pending = this.earlySignals.filter(signal => signal.peerEpoch === this.room.peerEpoch)
    this.earlySignals = this.earlySignals.filter(signal => signal.peerEpoch > this.room.peerEpoch)
    for (const signal of pending) await this.signalMessage(signal, '')
    this.tickTimer = setInterval(() => {
      void this.tick(pc).catch(() => { /* The negotiation deadline reports a persistent failure. */ })
    }, 200)
  }
  private flush() {
    this.offerSent = true
    for (const c of this.candidates.splice(0)) this.raw('ICE_CANDIDATE', c)
  }
  private async renew() {
    const room = this.room,
      start = performance.now(),
      epoch = `${room.roomEpoch}:${room.peerEpoch}:${room.bindingEpoch}`
    if (this.renewingEpoch === epoch) return
    this.renewingEpoch = epoch
    try {
      const lease = await directorCameraApi.lease(room)
      if (
        !this.stopped &&
        this.room.peerEpoch === room.peerEpoch &&
        this.room.bindingEpoch === room.bindingEpoch
      )
        this.acceptLease(lease, start)
    } finally {
      if (this.renewingEpoch === epoch) this.renewingEpoch = null
    }
  }
  private acceptLease(lease: DirectorCameraLease, start: number) {
    if (
      lease.roomEpoch !== this.room.roomEpoch ||
      lease.peerEpoch !== this.room.peerEpoch ||
      lease.bindingEpoch !== this.room.bindingEpoch ||
      lease.leaseSeq <= (this.lease?.leaseSeq ?? -1)
    )
      return
    this.lease = lease
    this.deadline = start + Math.max(0, Math.min(5000, lease.expiresAtMs - lease.serverTimeMs) - 350)
    if (this.o.role === 'host') this.state('AUTH_STATE', { lease })
  }
  private binding() {
    const r = this.room
    return {
      roomEpoch: r.roomEpoch,
      peerEpoch: r.peerEpoch,
      bindingEpoch: r.bindingEpoch,
      peerBindingNonce: r.peerBindingNonce
    }
  }
  private matches(m: any) {
    return Object.entries(this.binding()).every(([k, v]) => v !== undefined && m[k] === v)
  }
  can(permission: string) {
    if (permission !== 'PREVIEW' && this.disconnectTimer !== undefined) return false
    return (
      !this.stopped &&
      performance.now() < this.deadline &&
      Boolean(this.lease?.permissions.includes(permission))
    )
  }
  private state(type: string, payload: unknown = {}, commandId?: string): boolean {
    const c = this.channels.state
    if (c?.readyState !== 'open' || c.bufferedAmount >= 65536) return false
    c.send(JSON.stringify({ type, ...this.binding(), payload, ...(commandId ? { commandId } : {}) }))
    return true
  }
  send(message: VirtualCameraMessage): boolean {
    if (!this.hello || !this.peerHello) return false
    if (message.type === 'pose') {
      const c = this.channels.pose
      if (
        this.room.state !== 'READY' ||
        !this.can('CONTROL') ||
        document.hidden ||
        c?.readyState !== 'open' ||
        c.bufferedAmount > 32768
      )
        return false
      c.send(JSON.stringify({ ...this.binding(), seq: ++this.poseSeq, payload: message }))
      return true
    }
    if (message.type === 'record' && !this.can('RECORD')) return false
    return this.state('APP_MESSAGE', message, createRandomUuid())
  }
  private wire(channel: RTCDataChannel) {
    if (!['pose', 'state'].includes(channel.label) || this.channels[channel.label]) {
      channel.close()
      return
    }
    this.channels[channel.label] = channel
    const sendHello = () => { if (channel.label === 'state') this.state('HELLO') }
    channel.onopen = sendHello
    if (channel.readyState === 'open') sendHello()
    channel.onmessage = (e) => {
      if (this.stopped || typeof e.data !== 'string' || e.data.length > 32768) return
      let m: any
      try {
        m = JSON.parse(e.data)
      } catch {
        return
      }
      if (!this.matches(m)) return
      if (channel.label === 'pose') {
        if (
          this.o.role !== 'host' ||
          this.room.state !== 'READY' ||
          !this.can('CONTROL') ||
          !this.hello ||
          !this.peerHello ||
          !Number.isSafeInteger(m.seq) ||
          m.seq <= this.lastPoseSeq ||
          document.hidden
        )
          return
        const parsed = parseVirtualCameraMessage(m.payload)
        if (parsed?.type !== 'pose') return
        this.lastPoseSeq = m.seq
        this.o.onMessage(parsed)
        return
      }
      if (m.type === 'HELLO') {
        this.peerHello = true
        this.state('HELLO_ACK')
        this.announceGuest()
        return
      }
      if (m.type === 'HELLO_ACK') {
        this.hello = true
        this.announceGuest()
        return
      }
      if (m.type === 'AUTH_STATE' && this.o.role === 'guest') {
        this.acceptLease(m.payload?.lease, performance.now())
        return
      }
      if (m.type === 'APP_MESSAGE') {
        const parsed = parseVirtualCameraMessage(m.payload)
        if (!parsed) return
        if (parsed.type === 'record' && (this.room.state !== 'READY' || !this.can('RECORD'))) return
        if (m.commandId && this.applied.has(m.commandId)) {
          this.state('COMMAND_ACK', { ok: true }, m.commandId)
          return
        }
        if (m.commandId) {
          this.applied.add(m.commandId)
          if (this.applied.size > 256) this.applied.delete(this.applied.values().next().value!)
        }
        this.o.onMessage(parsed)
        if (m.commandId) this.state('COMMAND_ACK', { ok: true }, m.commandId)
      }
    }
  }
  private announceGuest() {
    if (this.o.role !== 'guest' || !this.hello || !this.peerHello || this.guestAnnounced) return
    this.guestAnnounced = true
    this.state('APP_MESSAGE', { type: 'hello', role: 'guest' })
  }
  reportVideoFrame(stream: MediaStream) {
    if (this.o.role === 'guest' && this.remoteVideoTrack && stream.getVideoTracks().some((track) => track === this.remoteVideoTrack)) this.receivedFrame = true
  }
  private async tick(pc: RTCPeerConnection) {
    if (this.pc !== pc || this.stopped) return
    if (this.o.role === 'host' && !this.can('PREVIEW')) {
      this.failPeer('运镜授权已过期，请重新连接', 'LEASE_EXPIRED')
      return
    }
    if (this.o.role === 'guest' && this.lease && !this.can('PREVIEW')) {
      this.failPeer('运镜授权已过期，请重新连接', 'LEASE_EXPIRED')
      return
    }
    if (!this.hello || !this.peerHello) this.state('HELLO')
    if (
      this.readyReported || this.checkingPeer === pc ||
      (pc.connectionState !== 'connected' && !['connected', 'completed'].includes(pc.iceConnectionState)) ||
      this.disconnectTimer !== undefined ||
      !this.hello ||
      !this.peerHello ||
      this.channels.pose?.readyState !== 'open' ||
      this.channels.state?.readyState !== 'open'
    )
      return
    this.checkingPeer = pc
    try {
      const stats = await pc.getStats()
      if (this.pc !== pc || this.readyReported || this.disconnectTimer !== undefined) return
      let selectedPair: RTCIceCandidatePair | null | undefined
      try {
        const video = pc.getTransceivers().find(item => item.receiver.track.kind === 'video')
        const ice = video?.sender.transport?.iceTransport ?? video?.receiver.transport?.iceTransport
          ?? pc.sctp?.transport?.iceTransport
        selectedPair = ice?.getSelectedCandidatePair?.()
      } catch { /* Stats still carry the selected candidate pair. */ }
      if (directorLanVideoReady(stats, this.o.role, this.local, this.remote, selectedPair, this.receivedFrame)) {
        this.readyReported = true
        this.raw('PEER_READY', { candidateType: 'host', videoReady: true, channelsReady: true })
      }
    } finally {
      if (this.checkingPeer === pc) this.checkingPeer = null
    }
  }
  async switchCamera(cameraId: string) {
    this.closePeer()
    const room = await directorCameraApi.action(this.room, 'SWITCH_CAMERA', cameraId)
    this.closePeer()
    this.room = room
    this.negotiationSeen = ''
    this.o.onState('waiting')
    await this.negotiate()
  }
  private closePeer() {
    this.releaseVideoFrame?.()
    this.releaseVideoFrame = null
    clearInterval(this.tickTimer)
    clearInterval(this.leaseTimer)
    clearTimeout(this.disconnectTimer)
    this.disconnectTimer = undefined
    clearTimeout(this.negotiationTimer)
    this.stream?.getTracks().forEach((t) => t.stop())
    this.stream = null
    this.remoteVideoTrack = undefined
    this.receivedFrame = false
    this.checkingPeer = null
    this.lease = null
    this.deadline = 0
    const pc = this.pc
    this.pc = null
    this.channels = {}
    if (pc) {
      pc.onconnectionstatechange = null
      pc.oniceconnectionstatechange = null
      pc.close()
    }
    this.o.onStream?.(null)
  }
  private failPeer(message: string, reason: string) {
    if (!this.pc || this.stopped || this.failed) return
    this.peerFailure = message
    this.raw('PEER_FAILED', { reason })
    this.closePeer()
    this.o.onState('error', message)
  }
  private fail(message: string) {
    if (this.stopped || this.failed) return
    this.failed = true
    clearTimeout(this.authenticationTimeout)
    clearTimeout(this.guestWaitTimeout)
    if (this.authenticated && this.pc) this.raw('PEER_FAILED', { reason: 'CLIENT_DISCONNECTED' })
    this.closePeer()
    clearInterval(this.ping)
    this.ws?.close()
    this.o.onState('error', message)
  }
  close() {
    this.stopped = true
    this.beforeAuth = []
    this.earlySignals = []
    clearTimeout(this.guestWaitTimeout)
    clearTimeout(this.authenticationTimeout)
    clearInterval(this.ping)
    this.closePeer()
    this.ws?.close()
    this.ws = null
  }
}
