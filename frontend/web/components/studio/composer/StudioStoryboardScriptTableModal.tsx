'use client'

import { useState } from 'react'
import { Button, Input, InputNumber, Modal, Space } from 'antd'
import type { StudioCanvasNode, StudioStoryboardDialogueItem, StudioStoryboardTableRow } from '@/types/studio'

export function StudioStoryboardScriptTableModal({
  open,
  node,
  onClose,
  onSave
}: {
  open: boolean
  node: StudioCanvasNode
  onClose: () => void
  onSave: (rows: StudioStoryboardTableRow[]) => void
}) {
  const [rows, setRows] = useState<StudioStoryboardTableRow[]>(() => rowsFromNode(node))

  const patchRow = (id: string, patch: Partial<StudioStoryboardTableRow>) => {
    setRows((current) => current.map((row) => row.id === id ? { ...row, ...patch } : row))
  }
  const addRow = () => setRows((current) => [...current, createRow(current.length + 1)])

  return (
    <Modal
      open={open}
      width="100%"
      title={`${node.data.title} · 分镜脚本表格`}
      footer={null}
      style={{ top: 0, maxWidth: '100vw', paddingBottom: 0 }}
      className="create-flow-modal studio-storyboard-table-modal"
      onCancel={onClose}
    >
      <div className="studio-storyboard-table">
        <div className="studio-storyboard-table__toolbar"><span>本地表格编辑不会创建生成任务</span><Button onClick={addRow}>添加镜头</Button></div>
        <div className="studio-storyboard-table__grid">
          <header><span>镜号</span><span>景别</span><span>画面与动作</span><span>灯光</span><span>台词 / 旁白</span><span>音效</span><span>运镜</span><span>时长</span><span>最终提示词</span><span>运动提示词</span><span>操作</span></header>
          {rows.map((row) => (
            <div key={row.id}>
              <Input value={row.shot} onChange={(event) => patchRow(row.id, { shot: event.target.value })} />
              <Input value={row.shotSize} onChange={(event) => patchRow(row.id, { shotSize: event.target.value })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} value={row.description} onChange={(event) => patchRow(row.id, { description: event.target.value })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} value={row.lighting} onChange={(event) => patchRow(row.id, { lighting: event.target.value })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} value={dialogueToText(row.dialogue)} placeholder="角色：台词&#10;旁白：内容" onChange={(event) => patchRow(row.id, { dialogue: dialogueFromText(event.target.value, row.id) })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} value={row.soundEffect} onChange={(event) => patchRow(row.id, { soundEffect: event.target.value })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} value={row.cameraMovement} onChange={(event) => patchRow(row.id, { cameraMovement: event.target.value })} />
              <Space.Compact>
                <InputNumber min={.5} max={120} step={.5} value={row.duration} onChange={(value) => patchRow(row.id, { duration: Number(value) || 1 })} />
                <Button disabled tabIndex={-1}>秒</Button>
              </Space.Compact>
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} value={row.finalPrompt} onChange={(event) => patchRow(row.id, { finalPrompt: event.target.value })} />
              <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} value={row.motionPrompt} onChange={(event) => patchRow(row.id, { motionPrompt: event.target.value })} />
              <Button danger type="text" onClick={() => setRows((current) => current.filter((item) => item.id !== row.id))}>删除</Button>
            </div>
          ))}
        </div>
        <footer><Button onClick={onClose}>取消</Button><Button type="primary" disabled={!rows.length} onClick={() => onSave(rows)}>保存到节点</Button></footer>
      </div>
    </Modal>
  )
}

export function storyboardRowsToPrompt(rows: StudioStoryboardTableRow[]): string {
  return rows.map((row) => [
    row.shot || '镜头',
    row.shotSize || '景别待定',
    row.description || '画面待补充',
    row.lighting ? `灯光：${row.lighting}` : '',
    row.dialogue.length ? `台词：${dialogueToText(row.dialogue).replace(/\n/g, '；')}` : '',
    row.soundEffect ? `音效：${row.soundEffect}` : '',
    row.cameraMovement ? `运镜：${row.cameraMovement}` : '',
    `${row.duration || 1}秒`,
    row.finalPrompt ? `最终提示词：${row.finalPrompt}` : '',
    row.motionPrompt ? `运动提示词：${row.motionPrompt}` : ''
  ].filter(Boolean).join('｜')).join('\n')
}

function rowsFromNode(node: StudioCanvasNode): StudioStoryboardTableRow[] {
  const stored = node.data.storyboardRows
  if (Array.isArray(stored)) {
    const normalized = stored.flatMap((value, index) => {
      if (!value || typeof value !== 'object') return []
      const row = value as Partial<StudioStoryboardTableRow> & { title?: unknown }
      return [{
        id: String(row.id || `row-${index + 1}`),
        shot: String(row.shot || `镜头 ${index + 1}`),
        duration: Number(row.duration) > 0 ? Number(row.duration) : 3,
        description: [row.title, row.description].map((item) => String(item || '').trim()).filter(Boolean).join('：'),
        shotSize: String(row.shotSize || ''),
        lighting: String(row.lighting || ''),
        dialogue: Array.isArray(row.dialogue) ? row.dialogue : [],
        soundEffect: String(row.soundEffect || ''),
        cameraMovement: String(row.cameraMovement || ''),
        finalPrompt: String(row.finalPrompt || ''),
        motionPrompt: String(row.motionPrompt || ''),
        rowColor: row.rowColor
      }]
    })
    if (normalized.length) return normalized
  }
  const localText = node.data.resultSummary || (node.data.status === 'success' ? node.data.prompt : '')
  const paragraphs = String(localText || '').split(/\r?\n+/).map((line) => line.trim()).filter(Boolean)
  if (paragraphs.length) return paragraphs.map((description, index) => ({ ...createRow(index + 1), description }))
  return [createRow(1)]
}

function createRow(index: number): StudioStoryboardTableRow {
  return {
    id: globalThis.crypto?.randomUUID?.() || `row-${Date.now()}-${index}`,
    shot: `镜头 ${index}`,
    shotSize: '',
    description: '',
    duration: 3,
    lighting: '',
    dialogue: [],
    soundEffect: '',
    cameraMovement: '',
    finalPrompt: '',
    motionPrompt: ''
  }
}

function dialogueToText(items: StudioStoryboardDialogueItem[]): string {
  return items.map((item) => item.kind === 'narration'
    ? `旁白：${item.content}`
    : `${item.speaker || '角色'}：${item.content}`).join('\n')
}

function dialogueFromText(value: string, rowId: string): StudioStoryboardDialogueItem[] {
  return value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line, index) => {
    const separator = line.search(/[:：]/)
    const speaker = separator >= 0 ? line.slice(0, separator).trim() : ''
    const content = separator >= 0 ? line.slice(separator + 1).trim() : line
    return speaker === '旁白'
      ? { id: `${rowId}-dialogue-${index + 1}`, kind: 'narration' as const, content }
      : { id: `${rowId}-dialogue-${index + 1}`, kind: 'dialogue' as const, speaker: speaker || undefined, content }
  })
}
