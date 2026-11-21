'use client'

import { createPrevizFromImage } from '@/utils/studio/directorCanvasBridge'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  CopyOutlined,
  DeleteOutlined,
  FileAddOutlined,
  SnippetsOutlined
} from '@ant-design/icons'
import type { XYPosition } from '@xyflow/react'
import type { RefObject } from 'react'
import { useStudioContextMenuPlacement } from '@/hooks/studio/useStudioContextMenuPlacement'

export function StudioNodeContextMenu({
  boundsRef,
  client,
  nodeId,
  canPaste,
  locked = false,
  variant = 'default',
  onCopy,
  onDuplicate,
  onPaste,
  onDelete,
  onCopyToClipboard
}: {
  boundsRef: RefObject<HTMLElement | null>
  nodeId?: string
  client: XYPosition
  canPaste: boolean
  locked?: boolean
  variant?: 'default' | 'grid_storyboard'
  onCopy: () => void
  onDuplicate: () => void
  onPaste: () => void
  onDelete: () => void
  onCopyToClipboard: () => void
}) {
  const menuRef = useStudioContextMenuPlacement({ boundsRef, client })
  const node = useStudioUiStore(s => s.nodes.find(n => n.id === nodeId))
  const isGrid = variant === 'grid_storyboard'
  return (
    <div
      ref={menuRef}
      className="studio-context-menu"
      role="menu"
      aria-label={isGrid ? '分镜组操作' : '节点操作'}
    >
      {node?.data.kind === 'image' && node.data.mediaUrl && <button role="menuitem" type="button" disabled={locked} onClick={() => createPrevizFromImage(nodeId)}>创建3D预演并连线</button>}
      <button role="menuitem" type="button" disabled={locked} onClick={onDuplicate}>
        <FileAddOutlined /> {isGrid ? '创建分镜组副本' : '创建副本'}
      </button>
      <button className="is-danger" role="menuitem" type="button" disabled={locked} onClick={onDelete}>
        <DeleteOutlined /> {isGrid ? '删除分镜组' : '删除'}
      </button>
      <button role="menuitem" type="button" disabled={locked} onClick={onCopy}>
        <CopyOutlined /> 复制
      </button>
      <button role="menuitem" type="button" disabled={locked || !canPaste} onClick={onPaste}>
        <SnippetsOutlined /> 粘贴
      </button>
      {!isGrid ? (
        <button role="menuitem" type="button" disabled={locked} onClick={onCopyToClipboard}>
          <CopyOutlined /> 复制到剪切板
        </button>
      ) : null}
    </div>
  )
}
