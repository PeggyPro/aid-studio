const MIRROR_STYLE_PROPS = [
  'boxSizing',
  'width',
  'height',
  'overflowX',
  'overflowY',
  'borderTopWidth',
  'borderRightWidth',
  'borderBottomWidth',
  'borderLeftWidth',
  'paddingTop',
  'paddingRight',
  'paddingBottom',
  'paddingLeft',
  'fontStyle',
  'fontVariant',
  'fontWeight',
  'fontStretch',
  'fontSize',
  'lineHeight',
  'fontFamily',
  'textAlign',
  'textTransform',
  'letterSpacing',
  'wordSpacing',
  'tabSize'
] as const

/** 计算 textarea 内指定字符索引处的视口坐标（用于 @ 面板定位）。 */
export function resolveTextareaCaretViewport(
  textarea: HTMLTextAreaElement,
  index: number
): { left: number; top: number } {
  const style = window.getComputedStyle(textarea)
  const mirror = document.createElement('div')
  mirror.setAttribute('aria-hidden', 'true')
  MIRROR_STYLE_PROPS.forEach((prop) => {
    mirror.style[prop] = style[prop]
  })
  mirror.style.position = 'absolute'
  mirror.style.visibility = 'hidden'
  mirror.style.pointerEvents = 'none'
  mirror.style.whiteSpace = 'pre-wrap'
  mirror.style.wordWrap = 'break-word'
  mirror.style.overflow = 'hidden'
  mirror.style.width = `${textarea.clientWidth}px`

  const before = textarea.value.slice(0, index)
  mirror.textContent = before
  const marker = document.createElement('span')
  marker.textContent = textarea.value.slice(index, index + 1) || '.'
  mirror.appendChild(marker)
  document.body.appendChild(mirror)

  const rect = textarea.getBoundingClientRect()
  const left = rect.left
    + marker.offsetLeft
    - textarea.scrollLeft
    + Number.parseFloat(style.borderLeftWidth || '0')
    + Number.parseFloat(style.paddingLeft || '0')
  const top = rect.top
    + marker.offsetTop
    - textarea.scrollTop
    + Number.parseFloat(style.borderTopWidth || '0')
    + Number.parseFloat(style.paddingTop || '0')

  document.body.removeChild(mirror)
  return { left, top }
}

export interface StudioMentionPanelAnchor {
  left: number
  top: number
  maxHeight: number
  placement: 'above' | 'below'
}

const PANEL_GAP = 10

export function resolveStudioMentionPanelAnchor(
  textarea: HTMLTextAreaElement,
  mentionStart: number
): StudioMentionPanelAnchor {
  const caret = resolveTextareaCaretViewport(textarea, mentionStart)
  const viewportHeight = window.innerHeight
  const spaceAbove = caret.top - PANEL_GAP
  const spaceBelow = viewportHeight - caret.top - PANEL_GAP
  const openAbove = caret.top > viewportHeight * 0.45 || spaceAbove >= spaceBelow

  const top = openAbove
    ? Math.max(PANEL_GAP, caret.top - PANEL_GAP)
    : Math.min(viewportHeight - PANEL_GAP, caret.top + 20 + PANEL_GAP)

  const maxHeight = openAbove
    ? Math.max(180, spaceAbove)
    : Math.max(180, spaceBelow)

  const left = Math.min(
    Math.max(PANEL_GAP, caret.left),
    window.innerWidth - 468 - PANEL_GAP
  )

  return {
    left,
    top,
    maxHeight,
    placement: openAbove ? 'above' : 'below'
  }
}
