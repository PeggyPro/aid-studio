'use client'

import { useState, type RefObject } from 'react'
import {
  AppstoreAddOutlined,
  CloudUploadOutlined,
  RightOutlined,
  RollbackOutlined,
  SaveOutlined,
  SnippetsOutlined
} from '@ant-design/icons'
import type { StudioNodeKind } from '@/types/studio'
import { allowedStudioConnectionKinds } from '@/utils/studio/studioLibraryLink'
import { STUDIO_PANE_ADD_MENU, studioPaneMenuItems, type StudioPaneMenuItemId } from '@/utils/studio/studioPaneMenu'
import { useStudioContextMenuPlacement } from '@/hooks/studio/useStudioContextMenuPlacement'
import { StudioAddNodeMenuLabel } from './StudioAddNodeMenuLabel'

export interface StudioPaneContextMenuProps {
  boundsRef: RefObject<HTMLElement | null>
  client: { x: number; y: number }
  canPaste: boolean
  canUndo: boolean
  saving: boolean
  sourceKind?: StudioNodeKind
  connectionRole?: 'source' | 'target'
  platform: 'mac' | 'win'
  mode?: 'full' | 'add-only'
  studioMode?: 'flow' | 'free'
  onAdd: (kind: StudioNodeKind) => void
  onPaste: () => void
  onUndo: () => void
  onUpload: () => void
  onSave: () => void
  onClose: () => void
}

const MENU_ICON: Record<StudioPaneMenuItemId, React.ReactNode> = {
  add: <AppstoreAddOutlined />,
  paste: <SnippetsOutlined />,
  undo: <RollbackOutlined />,
  upload: <CloudUploadOutlined />,
  save: <SaveOutlined />
}

export function StudioPaneContextMenu({
  boundsRef,
  client,
  canPaste,
  canUndo,
  saving,
  sourceKind,
  connectionRole = 'source',
  platform,
  mode = 'full',
  studioMode = 'free',
  onAdd,
  onPaste,
  onUndo,
  onUpload,
  onSave,
  onClose
}: StudioPaneContextMenuProps) {
  const [addOpen, setAddOpen] = useState(mode === 'add-only')
  const menuRef = useStudioContextMenuPlacement({ boundsRef, client })
  const flyoutRef = useStudioContextMenuPlacement({
    boundsRef,
    anchorRef: menuRef,
    enabled: addOpen && mode === 'full'
  })
  const allowed = new Set(
    studioMode === 'flow'
      ? ([] as StudioNodeKind[])
      : allowedStudioConnectionKinds(sourceKind, connectionRole, studioMode)
  )
  const entries = STUDIO_PANE_ADD_MENU.filter((item) => allowed.has(item.kind))
  const command = (id: StudioPaneMenuItemId) => {
    if (id === 'add') {
      if (studioMode === 'flow') return
      setAddOpen((open) => !open)
      return
    }
    if (id === 'paste') onPaste()
    if (id === 'undo') onUndo()
    if (id === 'upload') onUpload()
    if (id === 'save') onSave()
    onClose()
  }

  return (
    <div
      ref={menuRef}
      className={`studio-pane-context-menu${mode === 'add-only' ? ' studio-pane-context-menu--add-only' : ''}`}
      role={mode === 'full' ? 'menu' : 'dialog'}
      aria-label={mode === 'full' ? '画布右键菜单' : '添加画布节点'}
    >
      {mode === 'full' ? (
        <div className="studio-pane-context-menu__root">
          {studioPaneMenuItems({ canPaste, canUndo, saving, platform })
            .filter((item) => {
              if (studioMode === 'flow' && (item.id === 'add' || item.id === 'upload')) return false
              return true
            })
            .map((item) => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              aria-haspopup={item.id === 'add' ? 'menu' : undefined}
              aria-expanded={item.id === 'add' ? addOpen : undefined}
              onClick={() => command(item.id)}
            >
              <i>{MENU_ICON[item.id]}</i>
              <span>{item.label}</span>
              {item.shortcut ? <small>{item.shortcut}</small> : null}
              {item.id === 'add' ? <RightOutlined className="studio-pane-context-menu__arrow" /> : null}
            </button>
          ))}
        </div>
      ) : null}

      {addOpen && studioMode === 'free' ? (
        <div ref={flyoutRef} className="studio-pane-context-menu__flyout" aria-label="添加节点列表">
          {entries.map(({ kind }) => (
            <button key={kind} type="button" onClick={() => onAdd(kind)}>
              <StudioAddNodeMenuLabel kind={kind} />
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
