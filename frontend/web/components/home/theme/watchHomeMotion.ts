/** Decorative motion only needs a clock while its surface is on screen. */
export function watchHomeMotion(element: Element, onChange: (visible: boolean) => void) {
  let intersecting = false
  let visible = false
  let observing = true
  const sync = () => {
    if (!observing) return
    const next = intersecting && !document.hidden
    if (next === visible) return
    visible = next
    onChange(next)
  }
  const observer = new IntersectionObserver(([entry]) => {
    intersecting = Boolean(entry?.isIntersecting)
    sync()
  })
  observer.observe(element)
  document.addEventListener('visibilitychange', sync)
  return () => {
    observing = false
    observer.disconnect()
    document.removeEventListener('visibilitychange', sync)
  }
}
