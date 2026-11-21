/** Reserve the real scroll viewport and trailing layout space for feedback, never constrain result cards. */
export function casePlazaFeedbackHeight(container: HTMLElement): number {
  const visual = window.visualViewport
  let top = visual?.offsetTop || 0
  let bottom = top + (visual?.height || window.innerHeight)
  let trailing = 16
  for (let parent = container.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
    const style = getComputedStyle(parent)
    trailing += (parseFloat(style.paddingBottom) || 0) + (parseFloat(style.borderBottomWidth) || 0)
    if (/(auto|scroll)/.test(style.overflowY)) {
      const rect = parent.getBoundingClientRect()
      top = Math.max(top, rect.top + parent.clientTop)
      bottom = Math.min(bottom, rect.top + parent.clientTop + parent.clientHeight)
      break
    }
    trailing += parseFloat(style.marginBottom) || 0
  }
  const viewportHeight = Math.max(0, bottom - top)
  return Math.floor(Math.min(viewportHeight, Math.max(Math.min(48, viewportHeight),
    bottom - Math.max(top, container.getBoundingClientRect().top) - trailing)))
}
