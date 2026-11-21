/** One continuous coordinate drives the whole cross and its neighbourhood. */
export function emotionDotVisual(column: number, row: number, affinity: number, arousal: number) {
  const x = (Math.max(-1, Math.min(1, affinity)) + 1) * 2,
    y = (1 - Math.max(-1, Math.min(1, arousal))) * 2
  const distance = Math.hypot(column - x, row - y)
  const proximity = Math.exp((-distance * distance) / 1.6)
  return { highlighted: column === Math.round(x) || row === Math.round(y), scale: 1 + 0.85 * proximity }
}
