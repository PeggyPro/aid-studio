/** Continuous expression weights; the submitted business value still snaps to the 25-point grid. */
export function studioEmotionExpression(affinity: number, arousal: number): Record<string, number> {
  const a = Math.max(-1, Math.min(1, affinity)),
    e = Math.max(-1, Math.min(1, arousal))
  const warm = Math.max(0, -a),
    distant = Math.max(0, a)
  const excited = Math.max(0, e),
    calm = Math.max(0, -e)
  const weights: Record<string, number> = {
    jawOpen: excited * (0.12 + distant * 0.25 + warm * 0.14),
    mouthFunnel: distant * excited * 0.18
  }
  for (const side of ['L', 'R']) {
    weights[`browInnerUp_${side}`] = excited * (0.3 + distant * 0.45)
    weights[`mouthSmile_${side}`] = warm * (0.26 + excited * 0.32)
    weights[`mouthFrown_${side}`] = distant * (0.22 + calm * 0.3)
    weights[`mouthPress_${side}`] = distant * (0.15 + calm * 0.3)
    weights[`browDown_${side}`] = distant * (1 - excited) * 0.35
    weights[`browOuterUp_${side}`] = excited * 0.55
    weights[`eyeWide_${side}`] = excited * (0.4 + distant * 0.4)
    weights[`eyeSquint_${side}`] = warm * excited * 0.45 + distant * calm * 0.18
    weights[`eyeBlink_${side}`] = calm * 0.28
    weights[`cheekSquint_${side}`] = warm * (0.1 + excited * 0.18)
  }
  return weights
}
