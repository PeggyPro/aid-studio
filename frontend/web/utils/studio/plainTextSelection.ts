import {
  createEditorTextSelection,
  type EditorTextReplacement,
  type EditorTextSelection
} from '@/utils/quill/editorTextSelection'

export function createPlainTextSelection(
  documentText: string,
  start: number,
  end: number
): EditorTextSelection | null {
  const normalizedStart = Math.max(0, Math.min(start, documentText.length))
  const normalizedEnd = Math.max(normalizedStart, Math.min(end, documentText.length))
  return createEditorTextSelection(documentText, normalizedStart, normalizedEnd - normalizedStart)
}

export function readPlainTextSelectionFromElement(
  root: HTMLElement,
  documentText: string
): EditorTextSelection | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer)) return null

  const start = measurePlainTextOffset(root, range.startContainer, range.startOffset)
  const end = measurePlainTextOffset(root, range.endContainer, range.endOffset)
  if (start == null || end == null || end <= start) return null
  return createPlainTextSelection(documentText, start, end)
}

function measurePlainTextOffset(root: HTMLElement, node: Node, offset: number): number | null {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let total = 0
  while (walker.nextNode()) {
    const textNode = walker.currentNode
    if (textNode === node) return total + offset
    total += textNode.textContent?.length ?? 0
  }
  return null
}

export type PlainTextApplyResult = 'applied' | 'stale' | 'empty'

export function applyPlainTextReplacements(
  documentText: string,
  replacements: EditorTextReplacement[]
): { result: PlainTextApplyResult; text: string } {
  if (!replacements.length) return { result: 'empty', text: documentText }
  const sorted = [...replacements].sort(
    (left, right) => right.selection.index - left.selection.index
  )
  let next = documentText
  for (const item of sorted) {
    const current = next.slice(item.selection.index, item.selection.index + item.selection.length)
    if (current !== item.selection.text) return { result: 'stale', text: documentText }
    const replacement = item.replacement.trim()
    if (!replacement) return { result: 'empty', text: documentText }
    next =
      next.slice(0, item.selection.index) +
      replacement +
      next.slice(item.selection.index + item.selection.length)
  }
  return { result: 'applied', text: next }
}
