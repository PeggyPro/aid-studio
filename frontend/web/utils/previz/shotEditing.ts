import type { PrevizCommand, PrevizScene } from '@/types/previz'
import { copy, valueAt } from './core'

export type PrevizShotEdge = 'start' | 'end'

/** Adjacent shots share a cut. Trimming the final cut crops playback, never speeds up animation. */
export function resizePrevizShot(scene: PrevizScene, shotId: string, edge: PrevizShotEdge, frame: number, maxFrames = scene.timeline.frameCount): PrevizCommand {
  if (!Number.isFinite(frame)) throw new Error('镜头边界无效')
  const index = scene.shots.findIndex(shot => shot.id === shotId)
  if (index < 0) throw new Error('镜头已变化，请重新拖动')
  const shots = copy(scene.shots), timeline = copy(scene.timeline), shot = shots[index]
  const last = index === shots.length - 1
  const min = edge === 'start' ? index ? shots[index - 1].startFrame + 1 : 0 : Math.max(shot.startFrame + 1, last ? 30 : 1)
  const max = edge === 'start' ? index ? shot.endFrameExclusive - 1 : 0 : last ? Math.max(scene.timeline.frameCount, maxFrames) : shots[index + 1].endFrameExclusive - 1
  const cut = Math.max(min, Math.min(max, Math.round(frame)))
  if (edge === 'start') {
    if (index) { shot.startFrame = cut; shots[index - 1].endFrameExclusive = cut }
  } else {
    shot.endFrameExclusive = cut
    if (!last) shots[index + 1].startFrame = cut
    else {
      timeline.frameCount = cut
      if (cut < scene.timeline.frameCount) timeline.tracks = timeline.tracks.map(track => {
        const value = valueAt(track, cut - 1)
        return { ...track, keys: [...track.keys.filter(key => key.frame < cut - 1), { frame: cut - 1, value }] }
      })
    }
  }
  return { type: 'timeline.replace', timeline, shots }
}
