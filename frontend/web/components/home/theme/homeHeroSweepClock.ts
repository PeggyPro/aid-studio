const MAX_FRAME_MS = 50

/**
 * 点阵扫光时钟。一轮播放只覆盖周期开头的扫光段，后面是间隔。
 * 悬停会让当前这轮扫完再停住，不会中途冻结，也不会接着开下一轮。
 * 离开后从停住的相位继续，间隔走完才开始下一轮。
 */
export function createHomeHeroSweepClock(periodMs: number, runRatio: number) {
  let elapsed = 0
  let last = Number.NaN
  let mode: 'play' | 'finish' | 'hold' = 'play'

  const phaseOf = () => (elapsed % periodMs) / periodMs

  return {
    finishThenHold() {
      if (mode !== 'play') return
      mode = phaseOf() < runRatio ? 'finish' : 'hold'
    },
    play() {
      mode = 'play'
    },
    pause() {
      last = Number.NaN
    },
    delayUntilSweep() {
      if (mode === 'hold') return Infinity
      return phaseOf() < runRatio ? 0 : periodMs - (elapsed % periodMs)
    },
    phase(now: number, maxStep = MAX_FRAME_MS) {
      if (Number.isNaN(last)) last = now
      const delta = Math.min(Math.max(0, now - last), maxStep)
      last = now
      if (mode === 'play') elapsed += delta
      else if (mode === 'finish' && delta > 0) {
        const room = periodMs * runRatio - (elapsed % periodMs)
        if (delta >= room) {
          elapsed += Math.max(0, room)
          mode = 'hold'
        } else elapsed += delta
      }
      return phaseOf()
    }
  }
}
