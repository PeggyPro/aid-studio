/** Overflow below this does not need an opening animation. */
export const CONVERSATION_REVEAL_MIN_DISTANCE = 48

/** Opening a conversation eases toward the newest message. Later updates stay immediate. */
export function conversationRevealDuration(distance: number, reducedMotion: boolean): number {
  if (reducedMotion || distance <= CONVERSATION_REVEAL_MIN_DISTANCE) return 0
  return Math.min(760, Math.round(280 + distance * 0.12))
}

export function conversationRevealOffset(elapsed: number, duration: number, maxScroll: number): number {
  if (maxScroll <= 0 || duration <= 0 || elapsed >= duration) return Math.max(0, maxScroll)
  const progress = Math.min(1, Math.max(0, elapsed / duration))
  const eased = 1 - (1 - progress) ** 3
  return maxScroll * eased
}
