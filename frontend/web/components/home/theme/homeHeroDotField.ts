const GAP = 22
const TILE = GAP * 8
const DOT_RADIUS = 0.9
const LEVELS = 128
const POINTER_FALLOFF = 2 * 120 ** 2
const SWEEP_FALLOFF = 2 * 46 ** 2
const MIN_LIGHT = 0.5 / LEVELS
const POINTER_REACH = Math.sqrt(-Math.log(MIN_LIGHT) * POINTER_FALLOFF)
const SWEEP_REACH = Math.sqrt(-Math.log(MIN_LIGHT / 0.5) * SWEEP_FALLOFF)
const RING_REACH = Math.sqrt(-Math.log(MIN_LIGHT) * 900)

type Point = { x: number; y: number; diagonal: number }
type Tile = {
  left: number; top: number; right: number; bottom: number
  points: Point[]
  base: Path2D
  lit: boolean
}
type Light = { x: number; y: number; strength: number }
type Ripple = { x: number; y: number; radius: number }
type Palette = { cool: string; hot: string }

function addDot(path: Path2D, x: number, y: number, radius: number) {
  path.moveTo(x + radius, y)
  path.arc(x, y, radius, 0, Math.PI * 2)
}

function distanceToTileSquared(tile: Tile, x: number, y: number) {
  const dx = Math.max(tile.left - x, x - tile.right, 0)
  const dy = Math.max(tile.top - y, y - tile.bottom, 0)
  return dx * dx + dy * dy
}

function ringCrossesTile(tile: Tile, ring: Ripple) {
  if (distanceToTileSquared(tile, ring.x, ring.y) > (ring.radius + RING_REACH) ** 2) return false
  const dx = Math.max(Math.abs(tile.left - ring.x), Math.abs(tile.right - ring.x))
  const dy = Math.max(Math.abs(tile.top - ring.y), Math.abs(tile.bottom - ring.y))
  return dx * dx + dy * dy >= Math.max(0, ring.radius - RING_REACH) ** 2
}

/** Static tiles keep their pixels. Repaint only tiles touched by the current or previous light. */
export function createHomeHeroDotField(context: CanvasRenderingContext2D, width: number, height: number) {
  const tiles: Tile[] = []
  const base = new Path2D()
  for (let top = 0; top < height; top += TILE) {
    for (let left = 0; left < width; left += TILE) {
      const right = Math.min(left + TILE, width)
      const bottom = Math.min(top + TILE, height)
      const tile: Tile = { left, top, right, bottom, points: [], base: new Path2D(), lit: false }
      for (let y = top + GAP / 2; y < bottom; y += GAP) {
        for (let x = left + GAP / 2; x < right; x += GAP) {
          tile.points.push({ x, y, diagonal: x * 0.8 + y * 0.6 })
          addDot(tile.base, x, y, DOT_RADIUS)
        }
      }
      if (!tile.points.length) continue
      base.addPath(tile.base)
      tiles.push(tile)
    }
  }

  let palette: Palette | null = null
  return {
    draw(nextPalette: Palette, pointer: Light, sweep: number | null, ripple: Ripple | null) {
      const reset = palette !== nextPalette
      if (reset) {
        palette = nextPalette
        context.clearRect(0, 0, width, height)
        context.fillStyle = nextPalette.cool
        context.globalAlpha = 0.1
        context.fill(base)
      }

      const paths = new Map<number, Path2D>()
      const bucket = (key: number) => {
        let path = paths.get(key)
        if (!path) {
          path = new Path2D()
          paths.set(key, path)
        }
        return path
      }
      const rippleStrength = ripple ? Math.max(0, 1 - ripple.radius / 800) : 0
      for (const tile of tiles) {
        const byPointer = pointer.strength > 0.01
          && distanceToTileSquared(tile, pointer.x, pointer.y) < POINTER_REACH ** 2
        const bySweep = sweep !== null && sweep + SWEEP_REACH >= tile.left * 0.8 + tile.top * 0.6
          && sweep - SWEEP_REACH <= tile.right * 0.8 + tile.bottom * 0.6
        const byRipple = ripple && rippleStrength > 0 && ringCrossesTile(tile, ripple)
        const lit = Boolean(byPointer || bySweep || byRipple)
        if (!lit && (!tile.lit || reset)) {
          tile.lit = false
          continue
        }
        tile.lit = lit
        context.clearRect(tile.left, tile.top, tile.right - tile.left, tile.bottom - tile.top)
        if (!lit) {
          bucket(0).addPath(tile.base)
          continue
        }
        for (const point of tile.points) {
          let light = byPointer
            ? Math.exp(-((point.x - pointer.x) ** 2 + (point.y - pointer.y) ** 2) / POINTER_FALLOFF) * pointer.strength
            : 0
          if (bySweep) light = Math.max(light, Math.exp(-((point.diagonal - sweep) ** 2) / SWEEP_FALLOFF) * 0.5)
          if (byRipple) {
            const distance = Math.sqrt((point.x - ripple.x) ** 2 + (point.y - ripple.y) ** 2)
            light = Math.max(light, Math.exp(-((distance - ripple.radius) ** 2) / 900) * rippleStrength)
          }
          // Group subpixel-equivalent dots while still switching to the exact hot color threshold.
          const level = Math.round(light * LEVELS)
          const key = light > 0.4 ? level + LEVELS + 1 : level
          addDot(bucket(key), point.x, point.y, DOT_RADIUS + level / LEVELS * 1.1)
        }
      }
      for (const [key, path] of paths) {
        const hot = key > LEVELS
        const level = hot ? key - LEVELS - 1 : key
        context.fillStyle = hot ? nextPalette.hot : nextPalette.cool
        context.globalAlpha = 0.1 + level / LEVELS * 0.7
        context.fill(path)
      }
      context.globalAlpha = 1
    }
  }
}
