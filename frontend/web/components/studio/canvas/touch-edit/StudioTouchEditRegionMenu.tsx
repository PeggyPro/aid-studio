'use client'

import { EditOutlined, DragOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import { commitDraftToEdits, pushTouchEditHistory } from '@/utils/studio/touchEditSessionModel'
import { studioRegionThumbStyle } from '@/utils/studio/studioRegionThumbStyle'

export function StudioTouchEditRegionMenu({
  nodeId,
  imageUrl
}: {
  nodeId: string
  imageUrl?: string
}) {
  const session = useStudioUiStore((state) => state.touchEditSession)
  const patchTouchEditSession = useStudioUiStore((state) => state.patchTouchEditSession)
  const mediaUrl = useStudioUiStore((state) => (
    imageUrl
    || state.nodes.find((item) => item.id === nodeId)?.data.mediaUrl
    || ''
  ))

  if (!session || session.nodeId !== nodeId || !session.draft) return null

  const draft = session.draft
  const label = draft.detectLabel?.trim() || '选区'
  const thumbStyle = studioRegionThumbStyle(mediaUrl, draft.rect, 28)

  const applyCommit = (instruction: string, kind: 'modify' | 'move') => {
    const text = instruction.trim()
    if (!text) return
    let next = pushTouchEditHistory(session)
    next = {
      ...next,
      draft: draft ? { ...draft, kind } : null
    }
    next = commitDraftToEdits(next, text)
    patchTouchEditSession({
      draft: next.draft,
      edits: next.edits,
      menuPhase: next.menuPhase,
      activeRegionId: next.activeRegionId,
      history: next.history,
      draftInstruction: ''
    })
  }

  if (session.menuPhase === 'modifyInput') {
    return (
      <div className="studio-touch-edit-region-menu" role="dialog" aria-label="修改选区">
        <div className="studio-touch-edit-region-menu__thumb" style={thumbStyle} />
        <span className="studio-touch-edit-region-menu__label">{label}</span>
        <input
          className="studio-touch-edit-region-menu__input"
          value={session.draftInstruction}
          placeholder="描述想修改的内容"
          onChange={(event) => patchTouchEditSession({ draftInstruction: event.target.value })}
          onKeyDown={(event) => {
            if (event.key === 'Enter') applyCommit(session.draftInstruction, 'modify')
          }}
        />
        <button
          type="button"
          className="studio-touch-edit-region-menu__add"
          onClick={() => applyCommit(session.draftInstruction, 'modify')}
        >
          添加
        </button>
      </div>
    )
  }

  if (session.menuPhase === 'moveConfirm') {
    return (
      <div className="studio-touch-edit-region-menu" role="dialog" aria-label="移动说明">
        <input
          className="studio-touch-edit-region-menu__input"
          style={{ width: 180 }}
          value={session.draftInstruction}
          placeholder="补充移动说明"
          onChange={(event) => patchTouchEditSession({ draftInstruction: event.target.value })}
          onKeyDown={(event) => {
            if (event.key === 'Enter') applyCommit(session.draftInstruction || '移动选区', 'move')
          }}
        />
        <button
          type="button"
          className="studio-touch-edit-region-menu__add"
          onClick={() => applyCommit(session.draftInstruction || '移动选区', 'move')}
        >
          添加
        </button>
      </div>
    )
  }

  return (
    <div className="studio-touch-edit-region-menu" role="toolbar" aria-label="选区操作">
      <div className="studio-touch-edit-region-menu__thumb" style={thumbStyle} />
      <span className="studio-touch-edit-region-menu__label">{label}</span>
      <button
        type="button"
        className="studio-touch-edit-bar__text-btn"
        onClick={() => patchTouchEditSession({ menuPhase: 'modifyInput', draftInstruction: '' })}
      >
        <EditOutlined />
        修改
      </button>
      <button
        type="button"
        className="studio-touch-edit-bar__text-btn"
        onClick={() => {
          if (!draft.fromRect) {
            patchTouchEditSession({
              draft: { ...draft, kind: 'move', fromRect: { ...draft.rect } },
              menuPhase: 'action'
            })
          }
        }}
      >
        <DragOutlined />
        移动
      </button>
    </div>
  )
}
