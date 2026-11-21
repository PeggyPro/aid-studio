'use client'

import { useCallback, useEffect, useState } from 'react'
import { FileTextOutlined, LinkOutlined } from '@ant-design/icons'
import { Button, Modal, Select, Tooltip, message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { upsertComposerRef } from '@/utils/studio/studioComposerRefs'
import { storyboardRowsToPrompt, studioStoryboardProgress } from '@/utils/studio/studioStoryboardScript'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import { StudioComposerChip } from './StudioComposerChip'
import {
  StudioStoryboardScriptWorkflowModal,
  type StudioStoryboardWorkflowDraft
} from './StudioStoryboardScriptWorkflowModal'

export function StudioStoryboardScriptEntrances({
  node,
  variant = 'chips'
}: {
  node: StudioCanvasNode
  /** chips：嵌在 Composer top 行，与放大按钮水平对齐；legacy 仅测试兼容 */
  variant?: 'chips' | 'legacy'
}) {
  const nodes = useStudioUiStore((state) => state.nodes)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const [tableOpen, setTableOpen] = useState(false)
  const [sourceOpen, setSourceOpen] = useState(false)
  const [sourceId, setSourceId] = useState('')
  const content = resolveStudioScriptNodeText(node.data)
  const empty = !content
  const candidates = nodes.filter((item) => item.id !== node.id &&
    (item.data.kind === 'text' || item.data.kind === 'storyboard_script') &&
    resolveStudioScriptNodeText(item.data))
  const source = candidates.find((item) => item.id === sourceId)
  const realGenerateReason = '现有批量接口只读取项目中已保存的剧本，不接受任意源节点文本；后端暂缺按源节点生成接口'

  useEffect(() => {
    const openEditor = (event: Event) => {
      const nodeId = String((event as CustomEvent<{ nodeId?: string }>).detail?.nodeId ?? '')
      if (nodeId === node.id) setTableOpen(true)
    }
    window.addEventListener('studio:open-storyboard-editor', openEditor)
    return () => window.removeEventListener('studio:open-storyboard-editor', openEditor)
  }, [node.id])

  const connectSource = () => {
    if (!source) return
    const sourceText = resolveStudioScriptNodeText(source.data)
    updateNodeData(node.id, {
      prompt: `基于剧本源「${source.data.title}」生成分镜脚本：\n${sourceText}`,
      composerRefs: upsertComposerRef(node.data.composerRefs, studioNodeToComposerRef(source)),
      sourceScriptNodeId: source.id
    })
    if (!useStudioUiStore.getState().edges.some((edge) => edge.source === source.id && edge.target === node.id)) {
      addConnection({ source: source.id, target: node.id, sourceHandle: null, targetHandle: null }, 'reference')
    }
    message.success('剧本源已连接并写入 Composer')
  }

  /** Live edits stay selection-safe; only closing records one undo snapshot. */
  const saveDraft = useCallback((draft: StudioStoryboardWorkflowDraft, recordHistory = false) => {
    const prompt = storyboardRowsToPrompt(draft.rows)
    const complete = studioStoryboardProgress(draft.rows, draft.assets).complete
    updateNodeData(node.id, {
      storyboardRows: draft.rows,
      storyboardAssets: draft.assets,
      storyboardWorkflowStep: draft.step,
      storyboardPromptMode: draft.promptMode,
      prompt,
      resultSummary: prompt,
      mediaKind: 'text',
      status: complete ? 'success' : 'empty',
      errorMessage: undefined
    }, recordHistory)
  }, [node.id, updateNodeData])

  const actions = !empty ? (
    <div className={variant === 'chips' ? 'studio-script-entrance-chips' : 'studio-script-local-actions'}>
      {variant === 'chips' ? (
        <>
          <StudioComposerChip
            icon={<FileTextOutlined aria-hidden />}
            label="全屏表格"
            arrow={false}
            onClick={() => setTableOpen(true)}
          />
          <StudioComposerChip
            icon={<LinkOutlined aria-hidden />}
            label="连接剧本源"
            arrow={false}
            onClick={() => setSourceOpen(true)}
          />
        </>
      ) : (
        <>
          <Button icon={<FileTextOutlined />} onClick={() => setTableOpen(true)}>全屏表格</Button>
          <Button icon={<LinkOutlined />} onClick={() => setSourceOpen(true)}>连接剧本源</Button>
        </>
      )}
    </div>
  ) : null

  return (
    <>
      {actions}
      {tableOpen ? (
        <StudioStoryboardScriptWorkflowModal
          open
          node={node}
          onClose={() => setTableOpen(false)}
          onSave={saveDraft}
        />
      ) : null}
      <Modal open={sourceOpen} title="从剧本生成分镜脚本" footer={null} className="create-flow-modal studio-script-source-modal" onCancel={() => setSourceOpen(false)}>
        <div className="studio-script-source">
          <Select
            value={sourceId || undefined}
            placeholder="选择已有文本或剧本节点"
            options={candidates.map((item) => ({ value: item.id, label: item.data.title }))}
            onChange={setSourceId}
          />
          {!candidates.length ? <p>画布中暂无可用剧本源。请先创建文本节点或上传剧本。</p> : null}
          <p>{realGenerateReason}。选择源节点后仍可建立引用边并把文本写入 Composer。</p>
          <div>
            <Button disabled={!source} onClick={connectSource}>连接并写入 Composer</Button>
            <Tooltip title={realGenerateReason}><span><Button type="primary" disabled>启动真实脚本任务</Button></span></Tooltip>
          </div>
        </div>
      </Modal>
    </>
  )
}
