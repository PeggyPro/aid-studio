/** Browser speech input. Audio is owned by this session and never uploaded to AID. */
export type StudioSpeechResultEvent = {
  results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }>
}

export type StudioSpeechRecognizer = {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  onstart: (() => void) | null
  onresult: ((event: StudioSpeechResultEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start: () => void
  abort: () => void
}

type SpeechWindow = Window & {
  SpeechRecognition?: new () => StudioSpeechRecognizer
  webkitSpeechRecognition?: new () => StudioSpeechRecognizer
}

function recognitionConstructor() {
  if (typeof window === 'undefined') return undefined
  const speechWindow = window as SpeechWindow
  return speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition
}

export function studioSpeechInputUnavailableReason(): string {
  if (typeof window === 'undefined') return '正在检查语音输入能力'
  if (!window.isSecureContext) return '语音输入需要 HTTPS 安全连接或 localhost'
  if (!navigator.mediaDevices?.getUserMedia) return '当前浏览器无法访问麦克风'
  if (!recognitionConstructor()) return '当前浏览器不支持语音转文字，请使用支持语音识别的浏览器'
  if (!window.AudioContext) return '当前浏览器不支持实时音量监测'
  return ''
}

function speechErrorMessage(code: string): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed': return '麦克风或语音识别权限被拒绝，请在浏览器站点设置中允许后重试'
    case 'audio-capture': return '无法读取麦克风，请检查设备连接和系统麦克风权限'
    case 'network': return '浏览器语音识别服务连接失败，请检查网络后重试'
    case 'language-not-supported': return '当前浏览器的语音识别服务不支持中文'
    case 'no-speech': return '未识别到说话内容，请靠近麦克风后重试'
    default: return '语音识别已中断，请重试'
  }
}

const speechOwner: { current: StudioSpeechInputSession | null } = { current: null }

export class StudioSpeechInputSession {
  private closed = false
  private started = false
  private stream: MediaStream | null = null
  private context: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private analyser: AnalyserNode | null = null
  private recognition: StudioSpeechRecognizer | null = null
  private frame = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  private restartTimer: ReturnType<typeof setTimeout> | undefined
  private completed = ''
  private lastText = ''
  private quickEnds = 0
  private cycleStartedAt = 0

  constructor(private readonly callbacks: {
    isCurrent: () => boolean
    onState: (state: 'requesting' | 'connecting' | 'listening' | 'idle') => void
    onText: (text: string, confirmed: string) => void
    onLevel: (level: number) => void
    onError: (message: string) => void
    onFinish: () => void
  }) {}

  private isCurrent() {
    if (this.closed) return false
    if (this.callbacks.isCurrent()) return true
    this.dispose()
    return false
  }

  async start() {
    if (this.started || this.closed) return
    this.started = true
    const unavailable = studioSpeechInputUnavailableReason()
    if (unavailable) { this.fail(unavailable); return }
    speechOwner.current?.stop()
    speechOwner.current = this
    this.callbacks.onState('requesting')
    // Also own page lifecycle while the browser permission dialog is pending.
    document.addEventListener('visibilitychange', this.onVisibilityChange)
    window.addEventListener('pagehide', this.onPageHide)
    this.timer = setTimeout(() => this.fail('麦克风权限申请尚未完成，请允许访问后重试'), 30_000)
    try {
      // Create/resume in the click's user activation, before the permission await.
      this.context = new AudioContext()
      void this.context.resume().catch(() => {
        if (this.isCurrent()) this.fail('音量监测启动失败，请重新点击语音输入')
      })
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (!this.isCurrent()) { stream.getTracks().forEach((track) => track.stop()); return }
      this.stream = stream
      stream.getAudioTracks().forEach((track) => {
        track.addEventListener('ended', this.onDeviceEnded)
      })
      this.source = this.context.createMediaStreamSource(stream)
      this.analyser = this.context.createAnalyser()
      this.analyser.fftSize = 256
      this.source.connect(this.analyser)
      const samples = new Float32Array(this.analyser.fftSize)
      let lastLevelAt = 0
      const measure = (now: number) => {
        if (!this.isCurrent() || !this.analyser) return
        if (now - lastLevelAt >= 50) {
          this.analyser.getFloatTimeDomainData(samples)
          const rms = Math.sqrt(samples.reduce((sum, sample) => sum + sample * sample, 0) / samples.length)
          this.callbacks.onLevel(Math.min(1, rms * 8))
          lastLevelAt = now
        }
        this.frame = requestAnimationFrame(measure)
      }
      this.frame = requestAnimationFrame(measure)
      this.startCycle()
    } catch (error) {
      if (!this.isCurrent()) return
      const name = error instanceof Error ? error.name : ''
      this.fail(name === 'NotAllowedError' || name === 'SecurityError'
        ? speechErrorMessage('not-allowed')
        : name === 'NotFoundError' ? '未找到麦克风，请连接设备后重试'
        : name === 'NotReadableError' ? '麦克风被占用或不可用，请检查设备后重试'
        : '语音输入启动失败，请检查麦克风后重试')
    }
  }

  private startCycle() {
    if (!this.isCurrent()) return
    clearTimeout(this.timer)
    this.callbacks.onState('connecting')
    this.cycleStartedAt = Date.now()
    this.timer = setTimeout(() => this.fail('浏览器语音识别服务启动超时，请检查网络后重试'), 12_000)
    try {
      const Recognition = recognitionConstructor()!
      const recognition = new Recognition()
      this.recognition = recognition
      recognition.lang = 'zh-CN'
      recognition.continuous = true
      recognition.interimResults = true
      recognition.maxAlternatives = 1
      recognition.onstart = () => {
        if (!this.isCurrent() || this.recognition !== recognition) return
        clearTimeout(this.timer)
        this.callbacks.onState('listening')
      }
      recognition.onresult = (event) => {
        if (!this.isCurrent() || this.recognition !== recognition) return
        // results is the complete current cycle, including corrected/retracted interim entries.
        let confirmed = ''
        let interim = ''
        for (let index = 0; index < event.results.length; index += 1) {
          const result = event.results[index]
          if (result.isFinal) confirmed += result[0].transcript
          else interim += result[0].transcript
        }
        this.lastText = this.completed + confirmed + interim
        this.quickEnds = 0
        this.callbacks.onText(this.lastText, this.completed + confirmed)
      }
      recognition.onerror = ({ error }) => {
        if (!this.isCurrent() || this.recognition !== recognition) return
        this.fail(speechErrorMessage(error))
      }
      recognition.onend = () => {
        if (!this.isCurrent() || this.recognition !== recognition) return
        clearTimeout(this.timer)
        this.detachRecognition()
        // Keep the last displayed partial when a browser ends a listening cycle.
        this.completed = this.lastText
        this.quickEnds = Date.now() - this.cycleStartedAt < 2000 ? this.quickEnds + 1 : 0
        if (this.quickEnds >= 3) { this.fail('浏览器语音识别服务持续中断，请稍后重试'); return }
        this.callbacks.onState('connecting')
        this.restartTimer = setTimeout(() => this.startCycle(), 250)
      }
      recognition.start()
    } catch {
      this.fail('浏览器语音识别服务无法启动，请重新点击语音输入')
    }
  }

  private onDeviceEnded = () => this.fail('麦克风已断开或权限被撤销，请检查后重试')
  private onVisibilityChange = () => { if (document.hidden) this.stop() }
  private onPageHide = () => this.stop()

  private fail(message: string) {
    if (this.closed) return
    const current = this.callbacks.isCurrent()
    this.release()
    this.callbacks.onState('idle')
    if (current) { this.callbacks.onFinish(); this.callbacks.onError(message) }
  }

  stop() {
    if (this.closed) return
    const current = this.callbacks.isCurrent()
    this.release()
    this.callbacks.onState('idle')
    if (current) this.callbacks.onFinish()
  }

  /** Scope changes discard pending callbacks; already confirmed text remains saved. */
  dispose() { if (!this.closed) { this.release(); this.callbacks.onState('idle') } }

  private detachRecognition() {
    const recognition = this.recognition
    this.recognition = null
    if (!recognition) return
    recognition.onstart = recognition.onresult = recognition.onerror = recognition.onend = null
    try { recognition.abort() } catch { /* Already ended. */ }
  }

  private release() {
    this.closed = true
    if (speechOwner.current === this) speechOwner.current = null
    clearTimeout(this.timer)
    clearTimeout(this.restartTimer)
    cancelAnimationFrame(this.frame)
    this.detachRecognition()
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    window.removeEventListener('pagehide', this.onPageHide)
    this.stream?.getAudioTracks().forEach((track) => track.removeEventListener('ended', this.onDeviceEnded))
    this.stream?.getTracks().forEach((track) => track.stop())
    this.source?.disconnect()
    this.analyser?.disconnect()
    if (this.context && this.context.state !== 'closed') void this.context.close().catch(() => {})
    this.stream = null
    this.context = null
    this.source = null
    this.analyser = null
    this.callbacks.onLevel(0)
  }
}
