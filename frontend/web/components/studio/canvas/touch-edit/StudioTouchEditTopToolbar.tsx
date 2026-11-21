'use client'

import type { ReactNode } from 'react'
import {
  BorderOutlined,
  CloseOutlined,
  HighlightOutlined,
  AimOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { Slider } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioTouchEditTool } from '@/types/studio'
import { undoTouchEditSession } from '@/utils/studio/touchEditSessionModel'
import { StudioCanvasTooltip } from '../StudioCanvasTooltip'

function ToolButton({
  label,
  active,
  onClick,
  children
}: {
  label: string
  active?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <StudioCanvasTooltip title={label} placement="bottom">
      <button
        type="button"
        className={`studio-touch-edit-bar__tool${active ? ' is-active' : ''}`}
        aria-label={label}
        onClick={onClick}
      >
        {children}
      </button>
    </StudioCanvasTooltip>
  )
}

export function StudioTouchEditTopToolbar({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.touchEditSession)
  const patchTouchEditSession = useStudioUiStore((state) => state.patchTouchEditSession)
  const closeTouchEditSession = useStudioUiStore((state) => state.closeTouchEditSession)

  if (!session || session.nodeId !== nodeId) return null

  const setTool = (tool: StudioTouchEditTool) => {
    patchTouchEditSession({ tool, draft: null, menuPhase: 'action', draftInstruction: '' })
  }

  const onUndo = () => {
    const next = undoTouchEditSession(session)
    patchTouchEditSession({
      draft: next.draft,
      edits: next.edits,
      menuPhase: next.menuPhase,
      activeRegionId: next.activeRegionId,
      history: next.history,
      draftInstruction: next.draftInstruction
    })
  }

  return (
    <div className="studio-touch-edit-bar" role="toolbar" aria-label="元素编辑工具栏">
      <button
        type="button"
        className="studio-touch-edit-bar__text-btn"
        aria-label="关闭元素编辑"
        onClick={() => closeTouchEditSession()}
      >
        <CloseOutlined />
        <span>元素编辑</span>
      </button>
      <span className="studio-touch-edit-bar__divider" />
      <ToolButton label="单选" active={session.tool === 'point'} onClick={() => setTool('point')}>
        <AimOutlined />
      </ToolButton>
      <ToolButton label="框选" active={session.tool === 'marquee'} onClick={() => setTool('marquee')}>
        <BorderOutlined />
      </ToolButton>
      <ToolButton label="画笔" active={session.tool === 'brush'} onClick={() => setTool('brush')}>
        <HighlightOutlined />
      </ToolButton>
      {session.tool === 'brush' ? (
        <div className="studio-touch-edit-bar__brush" aria-label="画笔粗细">
          <Slider
            min={0.01}
            max={0.12}
            step={0.005}
            value={session.brushSize}
            onChange={(value) => patchTouchEditSession({ brushSize: Number(value) })}
            tooltip={{ formatter: null }}
          />
        </div>
      ) : null}
      <span className="studio-touch-edit-bar__divider" />
      <ToolButton label="撤销" onClick={onUndo}>
        <UndoOutlined />
      </ToolButton>
    </div>
  )
}
