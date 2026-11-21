export type StudioTextEditorCommand =
  | { type: 'format-block'; value: 'p' | 'h1' | 'h2' | 'h3' }
  | { type: 'bold' }
  | { type: 'italic' }
  | { type: 'ordered-list' }
  | { type: 'unordered-list' }
  | { type: 'divider' }
  | { type: 'copy' }
  | { type: 'expand' }

export const STUDIO_TEXT_EDITOR_COMMAND_EVENT = 'studio:text-editor-command'
export const STUDIO_TEXT_EDITOR_FOCUS_EVENT = 'studio:focus-text-editor'

/** 聚焦请求可能早于编辑器挂上监听；挂载后再消费，避免「自己编写」丢一次进入编辑。 */
let pendingTextEditorFocusId: string | null = null

export function requestStudioTextEditorFocus(nodeId: string): void {
  pendingTextEditorFocusId = nodeId
  window.dispatchEvent(new CustomEvent(STUDIO_TEXT_EDITOR_FOCUS_EVENT, { detail: { nodeId } }))
}

export function consumePendingStudioTextEditorFocus(nodeId: string): boolean {
  if (pendingTextEditorFocusId !== nodeId) return false
  pendingTextEditorFocusId = null
  return true
}

export function dispatchStudioTextEditorCommand(nodeId: string, command: StudioTextEditorCommand): void {
  window.dispatchEvent(new CustomEvent(STUDIO_TEXT_EDITOR_COMMAND_EVENT, {
    detail: { nodeId, command }
  }))
}
