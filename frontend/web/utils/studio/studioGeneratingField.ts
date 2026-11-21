import type { StudioMediaKind } from '@/types/studio'

/** 第二种「显影」原稿的绘制公式；单位、配色、噪点和运动系数均与 Demo 一致。 */
export interface StudioGeneratingFieldState {
  ctx: CanvasRenderingContext2D
  w: number
  h: number
  dpr: number
  dark: boolean
  kind: StudioMediaKind
  queued: boolean
  progress: number | null
  clarity: number
  done: boolean
  reducedMotion: boolean
  noiseFrames?: HTMLCanvasElement[]
}

let noiseFrames: HTMLCanvasElement[] | undefined
export function studioGeneratingNoise(): HTMLCanvasElement[] {
  // 首次浏览器绘制时才分配，并由所有节点共用，服务端渲染不访问 document。
  return noiseFrames ??= Array.from({ length: 5 }, () => {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 180
    const context = canvas.getContext('2d')!
    const pixels = context.createImageData(180, 180)
    for (let i = 0; i < pixels.data.length; i += 4) {
      const value = Math.random() * 255
      pixels.data[i] = pixels.data[i + 1] = pixels.data[i + 2] = value
      pixels.data[i + 3] = 255
    }
    context.putImageData(pixels, 0, 0)
    return canvas
  })
}

const PALETTE = {
  dark: ['rgba(214,222,236,.62)', 'rgba(140,160,196,.7)', 'rgba(232,206,186,.46)', 'rgba(110,126,158,.72)'],
  light: ['rgba(120,138,176,.42)', 'rgba(170,186,214,.55)', 'rgba(214,190,170,.38)', 'rgba(96,112,146,.32)']
}

export function drawStudioGeneratingField(s: StudioGeneratingFieldState, t: number) {
  const { ctx, w, h, kind, dpr: DPR, dark: d } = s
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0)
  ctx.globalCompositeOperation = 'source-over'; ctx.globalAlpha = 1; ctx.filter = 'none'
  ctx.fillStyle = d ? '#1d1f23' : '#f4f6fa'; ctx.fillRect(0, 0, w, h)
  const target = s.done ? 1 : s.queued ? 0.04 : s.progress == null ? 0.22 + 0.08 * Math.sin(t * .5) : s.progress / 100
  s.clarity = s.reducedMotion ? target : s.clarity + (target - s.clarity) * (s.done ? .08 : .06)
  const p = s.clarity
  if (kind === 'audio') return drawAudio(s, t, p)

  const pal = PALETTE[d ? 'dark' : 'light'], m = Math.min(w, h)
  const speed = s.queued ? .25 : 1
  ctx.save()
  ctx.filter = `blur(${Math.round(m * (0.17 - p * 0.1))}px)`
  ctx.globalCompositeOperation = d ? 'screen' : 'multiply'
  ctx.globalAlpha = s.queued ? .4 : .72 + p * .28
  for (let i = 0; i < 4; i++) {
    const ph = i * 1.9
    let x = w * (.5 + .3 * Math.sin(t * .16 * speed + ph))
    const y = h * (.5 + .26 * Math.cos(t * .13 * speed + ph * 1.3))
    if (kind === 'video') x = ((t * .045 * speed * (1 + i * .25) + i * .31) % 1.5 - .25) * w
    const r = m * (.36 + .07 * Math.sin(t * .3 + i) + p * .06)
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    g.addColorStop(0, pal[i]); g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g; ctx.fillRect(-40, -40, w + 80, h + 80)
  }
  ctx.restore()

  if (kind === 'video' && !s.queued) {
    const sx = ((t * .3) % 1.4 - .2) * w
    const g = ctx.createLinearGradient(sx - w * .18, 0, sx + w * .18, 0)
    const c = d ? '255,255,255' : '255,255,255'
    g.addColorStop(0, `rgba(${c},0)`); g.addColorStop(.5, `rgba(${c},${d ? .07 : .35})`); g.addColorStop(1, `rgba(${c},0)`)
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h)
  }

  const noise = s.noiseFrames ?? studioGeneratingNoise()
  const frame = noise[Math.floor(t * 14) % noise.length]
  ctx.save()
  ctx.globalAlpha = (d ? .16 : .12) * (1 - p) + .03
  ctx.globalCompositeOperation = d ? 'overlay' : 'soft-light'
  ctx.fillStyle = ctx.createPattern(frame, 'repeat')!; ctx.fillRect(0, 0, w, h)
  ctx.restore()

  const v = ctx.createRadialGradient(w / 2, h / 2, m * .3, w / 2, h / 2, Math.max(w, h) * .75)
  v.addColorStop(0, 'rgba(0,0,0,0)'); v.addColorStop(1, d ? 'rgba(0,0,0,.35)' : 'rgba(40,55,85,.08)')
  ctx.fillStyle = v; ctx.fillRect(0, 0, w, h)
}

function drawAudio(s: StudioGeneratingFieldState, t: number, p: number) {
  const { ctx, w, h, dark: d } = s, cy = h * .44, A = h * .2
  const ink = d ? '232,234,237' : '21,32,51'
  const spread = s.queued ? .15 : 1 - p * .85
  const energy = s.queued ? .12 : 1
  const base = (x: number) => {
    const env = Math.sin(Math.PI * x / w) ** 1.6
    return env * A * energy * (.55 * Math.sin(x * .045 - t * 1.6) + .3 * Math.sin(x * .11 + t * .9) + .15 * Math.sin(x * .23 - t * 2.3))
  }
  ctx.lineCap = 'round'
  for (let i = 0; i < 6; i++) {
    ctx.beginPath()
    for (let x = 18; x <= w - 18; x += 2) {
      const env = Math.sin(Math.PI * x / w) ** 1.6
      const off = env * A * .7 * spread * Math.sin(x * (.028 + i * .006) + t * (.7 + i * .22) + i * 1.1)
      const y = cy + base(x) + off
      if (x === 18) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    }
    const g = ctx.createLinearGradient(18, 0, w - 18, 0)
    const a = (s.queued ? .1 : .16 + (1 - spread) * .05)
    g.addColorStop(0, `rgba(${ink},0)`); g.addColorStop(.5, `rgba(${ink},${a})`); g.addColorStop(1, `rgba(${ink},0)`)
    ctx.strokeStyle = g; ctx.lineWidth = 1; ctx.stroke()
  }
  ctx.beginPath()
  for (let x = 18; x <= w - 18; x += 2) {
    const y = cy + base(x)
    if (x === 18) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  }
  const g = ctx.createLinearGradient(18, 0, w - 18, 0)
  const a = s.queued ? .22 : .35 + p * .6
  g.addColorStop(0, `rgba(${ink},0)`); g.addColorStop(.18, `rgba(${ink},${a})`); g.addColorStop(.82, `rgba(${ink},${a})`); g.addColorStop(1, `rgba(${ink},0)`)
  ctx.strokeStyle = g; ctx.lineWidth = 1.1 + p * .5; ctx.stroke()
}

// 全部可见节点共用原稿的 30fps 时钟；停止订阅会释放 RAF，后台时间不推进动画。
const painters = new Set<(time: number) => void>()
let animationFrame: number | null = null
let lastFrame = 0
let animationClock = 0
export function studioGeneratingTime(): number { return animationClock }

function tick(now: number) {
  animationFrame = null
  if (!painters.size) return
  if (document.hidden) lastFrame = 0
  else if (!lastFrame || now - lastFrame >= 1000 / 30) {
    const delta = lastFrame ? Math.min(100, now - lastFrame) : 0
    lastFrame = now
    animationClock += delta / 1000
    for (const paint of painters) paint(animationClock)
  }
  if (painters.size) animationFrame = requestAnimationFrame(tick)
}

export function subscribeStudioGeneratingFrames(paint: (time: number) => void): () => void {
  painters.add(paint)
  if (animationFrame === null) animationFrame = requestAnimationFrame(tick)
  return () => {
    painters.delete(paint)
    if (!painters.size) {
      if (animationFrame !== null) cancelAnimationFrame(animationFrame)
      animationFrame = null
      lastFrame = 0
    }
  }
}
