import type { StudioNodeKind } from '@/types/studio'
import { isStudioAddMenuGroup, STUDIO_ADD_MENU, studioShortcutLabel } from './studioNodeKind'

/** Free-canvas node creation shared by the toolbar, side panel and canvas menus. */
export const STUDIO_PANE_ADD_MENU = STUDIO_ADD_MENU.flatMap((entry) =>
  isStudioAddMenuGroup(entry) || entry.kind === 'frame_breakdown' || entry.kind === 'storyboard_script'
    ? [] : [entry]
)

export function isStudioPaneAddableKind(kind: StudioNodeKind): boolean {
  return STUDIO_PANE_ADD_MENU.some((item) => item.kind === kind)
}

export type StudioPaneMenuItemId = 'add' | 'paste' | 'undo' | 'upload' | 'save'

export interface StudioPaneMenuItem {
  id: StudioPaneMenuItemId
  label: string
  shortcut?: string
  disabled: boolean
}

export function studioPaneMenuItems(input: {
  canPaste: boolean
  canUndo: boolean
  saving: boolean
  platform: 'mac' | 'win'
}): StudioPaneMenuItem[] {
  return [
    { id: 'add', label: '添加', disabled: false },
    { id: 'paste', label: '粘贴', shortcut: studioShortcutLabel('paste', input.platform), disabled: !input.canPaste },
    { id: 'undo', label: '撤销', shortcut: studioShortcutLabel('undo', input.platform), disabled: !input.canUndo },
    { id: 'upload', label: '上传', disabled: false },
    { id: 'save', label: input.saving ? '保存中' : '保存', disabled: input.saving }
  ]
}
