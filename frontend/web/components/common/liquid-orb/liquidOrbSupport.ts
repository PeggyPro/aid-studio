let cachedSupport: boolean | null = null

export function supportsWebGL() {
  if (cachedSupport != null) return cachedSupport
  if (typeof document === 'undefined') {
    cachedSupport = false
    return false
  }
  try {
    const canvas = document.createElement('canvas')
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl') || canvas.getContext('experimental-webgl')
    if (!gl || typeof (gl as WebGLRenderingContext).getExtension !== 'function') {
      cachedSupport = false
      return false
    }
    const lose = (gl as WebGLRenderingContext).getExtension('WEBGL_lose_context')
    lose?.loseContext()
    cachedSupport = true
    return true
  } catch {
    cachedSupport = false
    return false
  }
}

export function prefersReducedMotion() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function readBackdropRgb(element: HTMLElement | null, theme: 'dark' | 'light'): [number, number, number] {
  const fallback: [number, number, number] = theme === 'dark' ? [0.04, 0.045, 0.052] : [0.952, 0.955, 0.96]
  if (!element || typeof window === 'undefined') return fallback
  let node: HTMLElement | null = element.parentElement
  while (node && node !== document.documentElement) {
    const parsed = parseCssRgb(window.getComputedStyle(node).backgroundColor)
    if (parsed) return parsed
    node = node.parentElement
  }
  return parseCssRgb(window.getComputedStyle(document.body).backgroundColor) ?? fallback
}

function parseCssRgb(color: string) {
  const match = color.match(/rgba?\(([^)]+)\)/)
  if (!match) return null
  const parts = match[1].split(',').map((item) => parseFloat(item))
  if (parts.length < 3 || parts.some((item) => !Number.isFinite(item))) return null
  if ((parts.length > 3 ? parts[3] : 1) < 0.02) return null
  return [parts[0] / 255, parts[1] / 255, parts[2] / 255] as [number, number, number]
}
