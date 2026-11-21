'use client'

import { create } from 'zustand'
import { message } from 'antd'
import type { Node, NodeChange } from '@xyflow/react'
import type { SmartEditCommand, SmartEditTimeline } from '@/types/studio'
import { useStudioUiStore } from './studioUi'
import { applySmartEditCommand, pushSmartEditHistory, redoSmartEdit, undoSmartEdit, type SmartEditHistory } from '@/utils/studio/smartEditCommands'
import { defaultStudioSmartEditState, SMART_EDIT_SOURCE_LIMIT, smartEditDistinctSourceCount, smartEditMediaClips } from '@/utils/studio/studioSmartEdit'

interface EditorState {
  scopeKey: string
  nodeId: string | null
  selectedClipId: string | null
  playheadSeconds: number
  playing: boolean
  chrome: 'expanded' | 'docked'
  closing: boolean
  session: number
  previewTimeline: SmartEditTimeline | null
  buffering: boolean
  dockHeight: number
  notice: { type: 'info' | 'success' | 'warning' | 'error'; text: string } | null
  histories: Record<string, SmartEditHistory>
  exportProgress: Record<string, number>
  /** 服务端成片进度；不锁定时间轴，完成时按指纹决定是否写回。 */
  composeProgress: Record<string, number>
}
const initial: EditorState = { scopeKey: '', nodeId: null, selectedClipId: null, playheadSeconds: 0, playing: false, chrome: 'expanded', closing: false, session: 0, previewTimeline: null, buffering: false, dockHeight: 0, notice: null, histories: {}, exportProgress: {}, composeProgress: {} }
// 高频播放头和撤销记录不进入工作区，不触发全画布保存。
export const useStudioSmartEdit = create<EditorState>(() => initial)

/** Docked video dimensions belong to React Flow's local projection, not the saved workspace. */
export function filterSmartEditPersistentChanges<T extends Node>(changes: NodeChange<T>[], scopeKey: string): NodeChange<T>[] {
  const editor = useStudioSmartEdit.getState()
  if (editor.scopeKey !== scopeKey || !editor.nodeId || editor.chrome !== 'docked') return changes
  return changes.filter((change) => change.type !== 'dimensions' || change.id !== editor.nodeId)
}

export function openSmartEditEditor(nodeId: string) {
  const workspace = useStudioUiStore.getState()
  const node = workspace.nodes.find((candidate) => candidate.id === nodeId)
  if (node?.data.kind !== 'smart_edit') return
  const current = useStudioSmartEdit.getState()
  if (current.nodeId === nodeId && current.scopeKey === workspace.scopeKey && !current.closing) {
    useStudioSmartEdit.setState({ chrome: 'expanded' })
    return
  }
  useStudioSmartEdit.setState({ ...(current.scopeKey === workspace.scopeKey ? {} : initial), scopeKey: workspace.scopeKey, nodeId, selectedClipId: null, playheadSeconds: 0, playing: false, chrome: 'expanded', closing: false, session: current.session + 1, previewTimeline: null, notice: null, buffering: false })
  workspace.selectNodes([nodeId])
}
export function closeSmartEditEditor() { useStudioSmartEdit.setState({ nodeId: null, playing: false, selectedClipId: null, closing: false, previewTimeline: null }) }
export function requestCloseSmartEditEditor() { useStudioSmartEdit.setState({ closing: true, playing: false }) }
export function notifySmartEdit(nodeId: string, type: 'info' | 'success' | 'warning' | 'error', text: string, scopeKey = useStudioUiStore.getState().scopeKey) {
  if (useStudioUiStore.getState().scopeKey !== scopeKey) return
  const editor = useStudioSmartEdit.getState()
  if (editor.scopeKey === scopeKey && editor.nodeId === nodeId) useStudioSmartEdit.setState({ notice: { type, text } })
  else void message[type](text)
}
export function dispatchSmartEdit(nodeId: string, command: SmartEditCommand, recordHistory = true): boolean {
  const workspace = useStudioUiStore.getState()
  const node = workspace.nodes.find((candidate) => candidate.id === nodeId)
  if (node?.data.kind !== 'smart_edit') return false
  if (command.type === 'setChrome') {
    const editor = useStudioSmartEdit.getState()
    if (editor.scopeKey !== workspace.scopeKey || editor.nodeId !== nodeId) return false
    useStudioSmartEdit.setState({ chrome: command.chrome })
    return true
  }
  const previous = node.data.smartEdit ?? defaultStudioSmartEditState()
  const next = applySmartEditCommand(previous, command)
  if (next === previous) return false
  if (smartEditDistinctSourceCount(next.timeline) > SMART_EDIT_SOURCE_LIMIT) { notifySmartEdit(nodeId, 'warning', '智能剪辑最多引用 24 个素材'); return false }
  if (Object.values(next.timeline).filter(Array.isArray).reduce((count, items) => count + items.length, 0) > 200 || new TextEncoder().encode(JSON.stringify(next.timeline, (key, value) => key === 'sourceUrl' ? undefined : value)).length > 110000) { notifySmartEdit(nodeId, 'warning', '时间轴内容已达单节点保存上限，请拆分为多个剪辑节点'); return false }
  const editor = useStudioSmartEdit.getState()
  const histories = editor.scopeKey === workspace.scopeKey ? editor.histories : {}
  const history = histories[nodeId] || { past: [], future: [] }
  if (recordHistory) useStudioSmartEdit.setState({ scopeKey: workspace.scopeKey, histories: { ...histories, [nodeId]: pushSmartEditHistory(history, previous, next, command) } })
  workspace.updateNodeData(nodeId, { smartEdit: next })
  return true
}
export function travelSmartEditHistory(nodeId: string, direction: 'undo' | 'redo') {
  const workspace = useStudioUiStore.getState()
  const node = workspace.nodes.find((candidate) => candidate.id === nodeId)
  const editor = useStudioSmartEdit.getState()
  if (!node?.data.smartEdit || editor.scopeKey !== workspace.scopeKey) return
  const history = editor.histories[nodeId] || { past: [], future: [] }
  const restored = (direction === 'undo' ? undoSmartEdit : redoSmartEdit)(node.data.smartEdit, history)
  // 来源已经删除时，撤销手工操作不能让失效片段重新变成可导出。
  let next = restored.state
  for (const clip of smartEditMediaClips(next.timeline)) {
    if (!workspace.nodes.some((source) => source.id === clip.sourceNodeId)) next = applySmartEditCommand(next, { type: 'markInvalid', clipId: clip.id, invalid: true })
  }
  useStudioSmartEdit.setState({ histories: { ...editor.histories, [nodeId]: restored.history }, playing: false })
  workspace.updateNodeData(nodeId, { smartEdit: next })
}
