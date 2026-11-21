/** React Flow's wrapper can retain the old aspect-ratio size after media metadata loads. */
export function studioNodeVisualElement(node: HTMLElement): HTMLElement {
  return node.querySelector<HTMLElement>('.studio-node-card, .studio-group-card')
    ?? node.querySelector<HTMLElement>('.studio-image-node-preview, .studio-node-card__preview')
    ?? node
}

export function studioNodeVisualRect(node: HTMLElement): DOMRect {
  const rect = studioNodeVisualElement(node).getBoundingClientRect()
  return rect.width > 0 && rect.height > 0 ? rect : node.getBoundingClientRect()
}
