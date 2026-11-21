/** All distances are screen pixels; every layer follows the same travelling crest. */
export const STUDIO_EDGE_WASH_LAYERS = [
  ['halo', 150], ['veil', 120], ['silk', 80], ['crest', 26]
] as const
export const STUDIO_EDGE_MOTES = [
  { behind: 172, speed: .94, glimmer: 2.8 },
  { behind: 214, speed: .9, glimmer: 3.7 },
  { behind: 262, speed: .86, glimmer: 3.2 }
] as const
export const STUDIO_EDGE_WASH_SPACING = 460
export const STUDIO_EDGE_WASH_SPEED = 240
export const STUDIO_EDGE_WASH_DURATION = STUDIO_EDGE_WASH_SPACING / STUDIO_EDGE_WASH_SPEED

/** Absolute page time gives newly visible edges the current phase, without an entrance flash. */
export function studioEdgeAnimationDelay(timeSeconds: number, duration: number): string {
  return `${-((timeSeconds % duration + duration) % duration)}s`
}
