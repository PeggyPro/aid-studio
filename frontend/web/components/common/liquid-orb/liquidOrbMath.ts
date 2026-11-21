const TAU = Math.PI * 2

export function clamp01(value: number) {
  return Math.max(0, Math.min(1, value))
}

export function springFromResponse(response: number, dampingRatio: number) {
  const omega = TAU / Math.max(response, 1e-4)
  return {
    stiffness: omega * omega,
    damping: 2 * Math.max(0, dampingRatio) * omega
  }
}

export function springFromBounce(duration: number, bounce: number) {
  return springFromResponse(duration, Math.max(0.05, 1 - Math.max(0, bounce)))
}

export type SpringParams = { stiffness: number; damping: number }

export class Spring {
  value: number
  velocity = 0
  target: number
  params: SpringParams

  constructor(value: number, params: SpringParams) {
    this.value = value
    this.target = value
    this.params = params
  }

  setTarget(target: number, params?: SpringParams) {
    if (params) this.params = params
    this.target = target
  }

  jump(value: number) {
    this.value = value
    this.velocity = 0
    this.target = value
  }

  step(dt: number) {
    const next = integrateSpring(this.value, this.velocity, this.target, this.params, dt)
    this.value = next[0]
    this.velocity = next[1]
    return this.value
  }
}

function integrateSpring(
  value: number,
  velocity: number,
  target: number,
  params: SpringParams,
  dt: number
): [number, number] {
  const stiffness = params.stiffness
  const omega = Math.sqrt(stiffness)
  const dampingHalf = params.damping / 2
  const time = Math.max(dt, 0)
  const displacement = value - target
  if (time <= 0 || (displacement === 0 && velocity === 0)) return [value, velocity]

  let nextDisplacement: number
  let nextVelocity: number
  if (dampingHalf < omega) {
    const dampedOmega = Math.sqrt(stiffness - dampingHalf * dampingHalf)
    const decay = Math.exp(-dampingHalf * time)
    const cos = Math.cos(dampedOmega * time)
    const sin = Math.sin(dampedOmega * time)
    const coeff = (velocity + dampingHalf * displacement) / dampedOmega
    const shaped = displacement * cos + coeff * sin
    nextDisplacement = decay * shaped
    nextVelocity = decay * (-dampingHalf * shaped + (-displacement * dampedOmega * sin + coeff * dampedOmega * cos))
  } else if (omega < dampingHalf) {
    const root = Math.sqrt(dampingHalf * dampingHalf - stiffness)
    const r1 = -dampingHalf + root
    const r2 = -dampingHalf - root
    const c1 = (velocity - r2 * displacement) / (r1 - r2)
    const c2 = displacement - c1
    const e1 = Math.exp(r1 * time)
    const e2 = Math.exp(r2 * time)
    nextDisplacement = c1 * e1 + c2 * e2
    nextVelocity = c1 * r1 * e1 + c2 * r2 * e2
  } else {
    const decay = Math.exp(-dampingHalf * time)
    const coeff = velocity + dampingHalf * displacement
    const shaped = displacement + coeff * time
    nextDisplacement = decay * shaped
    nextVelocity = decay * (coeff - dampingHalf * shaped)
  }
  return [target + nextDisplacement, nextVelocity]
}
