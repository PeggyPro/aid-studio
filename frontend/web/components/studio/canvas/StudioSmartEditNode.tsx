'use client'

import { ScissorOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import { dispatchSmartEdit, openSmartEditEditor, useStudioSmartEdit } from '@/stores/studioSmartEdit'
import { StudioSmartEditPreview, StudioSmartEditSourcePreview } from './StudioSmartEditPreview'
import { useCanvasProjectRuntime } from '../free/CanvasProjectRuntimeContext'
import { StudioComposerChip } from '../composer/StudioComposerChip'
import type { StudioNodeData } from '@/types/studio'
import { defaultStudioSmartEditState, SMART_EDIT_PLAYBOOKS, smartEditSources } from '@/utils/studio/studioSmartEdit'
import './studio-smart-edit-editor.css'

export function StudioSmartEditNode({ nodeId, data }: { nodeId: string; data: StudioNodeData }) {
  const runtime = useCanvasProjectRuntime()
  const current = useStudioUiStore((state) => state.nodes.find((node) => node.id === nodeId)?.data) ?? data
  const source = useStudioUiStore((state) => smartEditSources(nodeId, state.nodes, state.edges).find((item) => item.data.kind === 'video'))
  const hasSources = useStudioUiStore((state) => smartEditSources(nodeId, state.nodes, state.edges).length > 0)
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const editorOpen = useStudioSmartEdit((state) => state.scopeKey === scopeKey && state.nodeId === nodeId)
  const chrome = useStudioSmartEdit((state) => state.chrome)
  const draft = useStudioSmartEdit((state) => state.scopeKey === scopeKey && state.nodeId === nodeId ? state.previewTimeline : null)
  const edit = current.smartEdit ?? defaultStudioSmartEditState()
  const previewEdit = draft && !edit.timelineInitialized ? { ...edit, timeline: draft } : edit
  const docked = editorOpen && chrome === 'docked'
  const exporting = useStudioSmartEdit((state) => state.exportProgress[`${scopeKey}:${nodeId}`] !== undefined)
  const owner = runtime?.nodeEditLockOwner(nodeId)
  const readOnly = exporting || Boolean(runtime && (!runtime.canEdit || !runtime.canMutate || owner))
  const hasTimeline = Object.values(previewEdit.timeline).some((items) => Array.isArray(items) && items.length > 0)
  return <section className={`studio-smart-edit-node nowheel${docked ? ' is-preview' : ''}`} aria-label={docked ? '剪辑视频' : '剪辑成片配置'}>
    {!docked ? <div className="studio-smart-edit-node__playbooks" role="tablist" aria-label="剪辑玩法">
      {SMART_EDIT_PLAYBOOKS.map((item, index) => <StudioComposerChip
        key={item.id} label={item.label} arrow={false} role="tab" aria-selected={edit.playbook === item.id}
        active={edit.playbook === item.id} tabIndex={edit.playbook === item.id ? 0 : -1}
        className="nodrag" disabled={readOnly || current.status === 'generating'}
        onClick={() => dispatchSmartEdit(nodeId, { type: 'setPlaybook', playbook: item.id })}
        onKeyDown={(event) => {
          const next = event.key === 'ArrowRight' ? (index + 1) % 3 : event.key === 'ArrowLeft' ? (index + 2) % 3 : event.key === 'Home' ? 0 : event.key === 'End' ? 2 : -1
          if (next < 0) return
          event.preventDefault(); event.stopPropagation()
          dispatchSmartEdit(nodeId, { type: 'setPlaybook', playbook: SMART_EDIT_PLAYBOOKS[next].id })
          ;(event.currentTarget.parentElement?.children[next] as HTMLElement)?.focus()
        }} />)}
    </div> : null}
    {hasSources || hasTimeline || current.mediaUrl ? <div className="studio-smart-edit-node__preview nodrag">
      {editorOpen && chrome === 'expanded' ? <span className="studio-smart-edit-node__hint">正在全屏精修</span> : hasTimeline ? <StudioSmartEditPreview nodeId={nodeId} edit={previewEdit} aspectRatio={current.generationOptions?.aspectRatio} active={docked} />
        : source?.data.mediaUrl || current.mediaUrl ? <StudioSmartEditSourcePreview url={source?.data.mediaUrl || current.mediaUrl!} />
          : <span className="studio-smart-edit-node__hint">打开剪辑台整理已连接的素材</span>}
      {!editorOpen ? <button type="button" data-smart-edit-open={nodeId} className="studio-smart-edit-node__open-label" aria-label="打开剪辑成片" onClick={() => openSmartEditEditor(nodeId)}><ScissorOutlined /> 打开剪辑成片</button> : null}
    </div> : <div className="studio-smart-edit-node__empty"><ScissorOutlined /><p>空空如也，请连接视频、图片或音频节点后操作</p></div>}
    {!docked && current.errorMessage ? <small role="alert">{current.errorMessage}</small> : null}
    {!docked && owner ? <small>{owner} 正在剪辑</small> : null}
  </section>
}
