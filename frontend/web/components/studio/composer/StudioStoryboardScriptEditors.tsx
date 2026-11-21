'use client'

/* eslint-disable @next/next/no-img-element -- 画布素材支持运行时 URL、blob 与 data 预览。 */
import { useState } from 'react'
import { AudioOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Input, Modal, Spin, message } from 'antd'
import type { StudioCanvasNode, StudioStoryboardAssetKind, StudioStoryboardDialogueItem, StudioStoryboardTableRow } from '@/types/studio'

export function StudioStoryboardTextModal({ open, title, value, placeholder, onClose, onSave }: { open: boolean; title: string; value: string; placeholder: string; onClose: () => void; onSave: (value: string) => void }) {
  const [draft, setDraft] = useState(() => ({ source: value, value }))
  const input = draft.source === value ? draft.value : value
  return <Modal open={open} title={title} footer={null} width={620} destroyOnHidden className="create-flow-modal studio-storyboard-editor-modal" onCancel={onClose}><Input.TextArea autoFocus rows={7} value={input} placeholder={placeholder} onChange={(event) => setDraft({ source: value, value: event.target.value })} /><footer><span>点击保存后立即写入脚本节点</span><Button type="primary" onClick={() => onSave(input.trim())}>保存</Button></footer></Modal>
}

export function StudioStoryboardDialogueModal({ row, onClose, onSave }: { row?: StudioStoryboardTableRow; onClose: () => void; onSave: (items: StudioStoryboardDialogueItem[]) => void }) {
  const sourceKey = `${row?.id || ''}:${JSON.stringify(row?.dialogue ?? [])}`
  const [draft, setDraft] = useState(() => ({ sourceKey, items: row?.dialogue ?? [] }))
  const items = draft.sourceKey === sourceKey ? draft.items : row?.dialogue ?? []
  const patch = (id: string, change: Partial<StudioStoryboardDialogueItem>) => setDraft({ sourceKey, items: items.map((item) => item.id === id ? { ...item, ...change } : item) })
  const add = (kind: 'dialogue' | 'narration') => {
    if (items.length >= 50) { message.info('单个镜头最多 50 条对白旁白'); return }
    setDraft({ sourceKey, items: [...items, { id: `line-${Date.now()}-${items.length}`, kind, content: '' }] })
  }
  const limitReached = items.length >= 50
  return <Modal open={Boolean(row)} title={row ? `${row.shot} · 对白旁白` : ''} footer={null} width={720} destroyOnHidden className="create-flow-modal studio-storyboard-editor-modal" onCancel={onClose}><div className="studio-storyboard-dialogue">{items.map((item) => <div key={item.id}><b className={item.kind === 'dialogue' ? 'is-dialogue' : 'is-narration'}>{item.kind === 'dialogue' ? '台词' : '旁白'}</b>{item.kind === 'dialogue' ? <Input value={item.speaker} placeholder="未绑定角色" onChange={(event) => patch(item.id, { speaker: event.target.value })} /> : null}<Input value={item.content} placeholder={item.kind === 'dialogue' ? '输入台词' : '输入旁白'} onChange={(event) => patch(item.id, { content: event.target.value })} /><button type="button" onClick={() => setDraft({ sourceKey, items: items.filter((line) => line.id !== item.id) })}>×</button></div>)}{!items.length ? <p>暂无对白或旁白，可按需添加多条内容。</p> : null}<footer><div><Button disabled={limitReached} title={limitReached ? '单个镜头最多 50 条' : undefined} onClick={() => add('dialogue')}><PlusOutlined />新增台词</Button><Button disabled={limitReached} title={limitReached ? '单个镜头最多 50 条' : undefined} onClick={() => add('narration')}><PlusOutlined />新增旁白</Button></div><Button type="primary" onClick={() => onSave(items)}>保存</Button></footer></div></Modal>
}

export function StudioStoryboardMovementModal({ row, options, onClose, onSave }: { row?: StudioStoryboardTableRow; options: Array<{ label: string; value: string; imageUrl?: string }>; onClose: () => void; onSave: (value: string) => void }) {
  const sourceKey = `${row?.id || ''}:${row?.cameraMovement || ''}`
  const [draft, setDraft] = useState(() => ({ sourceKey, value: row?.cameraMovement ?? '' }))
  const value = draft.sourceKey === sourceKey ? draft.value : row?.cameraMovement ?? ''
  return <Modal open={Boolean(row)} title={row ? `${row.shot} · 选择运镜方式` : ''} footer={null} width={820} destroyOnHidden className="create-flow-modal studio-storyboard-editor-modal" onCancel={onClose}><div className="studio-storyboard-movement-grid">{options.map((option) => <button key={option.value} type="button" className={value === option.value ? 'is-active' : ''} onClick={() => setDraft({ sourceKey, value: option.value })}>{option.imageUrl ? <img src={option.imageUrl} alt="" /> : <i><AudioOutlined /></i>}<span>{option.label}</span></button>)}{!options.length ? <p>运镜数据暂未加载，请稍后重试。</p> : null}</div><footer className="studio-storyboard-editor-modal__footer"><Button onClick={onClose}>取消</Button><Button type="primary" disabled={!value} onClick={() => onSave(value)}>保存</Button></footer></Modal>
}

export function StudioStoryboardPromptModal({ row, promptMode, smartDisabledReason, loading, streamPreview, onMode, onClose, onPatch, onSynthesize, onStop }: { row?: StudioStoryboardTableRow; promptMode: 'smart' | 'balanced'; smartDisabledReason: string | null; loading: boolean; streamPreview: string; onMode: (mode: 'smart' | 'balanced') => void; onClose: () => void; onPatch: (patch: Partial<StudioStoryboardTableRow>) => void; onSynthesize: () => void; onStop: () => void }) {
  const sourceKey = `${row?.id || ''}:${row?.finalPrompt || ''}:${row?.motionPrompt || ''}`
  const [draft, setDraft] = useState(() => ({ sourceKey, finalPrompt: row?.finalPrompt ?? '', motionPrompt: row?.motionPrompt ?? '' }))
  const value = draft.sourceKey === sourceKey
    ? draft
    : { sourceKey, finalPrompt: row?.finalPrompt ?? '', motionPrompt: row?.motionPrompt ?? '' }
  const smartDisabled = promptMode === 'smart' && Boolean(smartDisabledReason)
  const close = () => {
    if (loading) { message.info('请先停止当前生成任务'); return }
    onPatch({ finalPrompt: value.finalPrompt, motionPrompt: value.motionPrompt })
    onClose()
  }
  return <Modal open={Boolean(row)} title={row ? `${row.shot} · 最终提示词` : ''} footer={null} width={780} destroyOnHidden closable={!loading} keyboard={!loading} mask={{ closable: !loading }} className="create-flow-modal studio-storyboard-prompt-modal" onCancel={close}><div className="studio-storyboard-prompt-editor"><label><span>分镜提示词 <small>自动保存</small></span><Input.TextArea disabled={loading} rows={7} value={value.finalPrompt} placeholder="点击立即生成提示词，重新点击会覆盖生成。" onChange={(event) => setDraft({ ...value, finalPrompt: event.target.value })} onBlur={() => onPatch({ finalPrompt: value.finalPrompt })} /></label><label><span>视频运动提示词 <small>自动保存</small></span><Input.TextArea disabled={loading} rows={7} value={value.motionPrompt} placeholder="描述动作节奏、运镜与时序。" onChange={(event) => setDraft({ ...value, motionPrompt: event.target.value })} onBlur={() => onPatch({ motionPrompt: value.motionPrompt })} /></label>{smartDisabled ? <p className="studio-storyboard-synthesis__warning">{smartDisabledReason}</p> : null}{loading ? <StudioStoryboardStream content={streamPreview} /> : null}<footer><StudioStoryboardPromptMode value={promptMode} disabled={loading} onChange={onMode} />{loading ? <Button danger onClick={onStop}>停止生成</Button> : <Button type="primary" title={smartDisabled ? smartDisabledReason || undefined : undefined} disabled={smartDisabled} onClick={onSynthesize}>{row?.finalPrompt ? '重新合成提示词' : '立即合成提示词'}</Button>}</footer></div></Modal>
}

export function StudioStoryboardAssetPickerModal({ openKind, nodes, onClose, onSelect }: { openKind: StudioStoryboardAssetKind | null; nodes: StudioCanvasNode[]; onClose: () => void; onSelect: (node: StudioCanvasNode) => void }) {
  const label = openKind === 'scene' ? '场景' : openKind === 'character' ? '角色' : openKind === 'prop' ? '道具' : ''
  return <Modal open={Boolean(openKind)} title={`选择${label}图片`} footer={null} width={960} destroyOnHidden className="create-flow-modal studio-storyboard-asset-picker" onCancel={onClose}><div className="studio-storyboard-asset-picker__grid">{nodes.map((node) => <button key={node.id} type="button" onClick={() => onSelect(node)}><img src={node.data.mediaUrl} alt={node.data.title} /><span>{node.data.title}</span></button>)}{!nodes.length ? <div className="studio-storyboard-asset-picker__empty">当前项目画布中暂无可用图片，请先上传或生成图片节点。</div> : null}</div></Modal>
}

export function StudioStoryboardPromptMode({ value, disabled = false, onChange }: { value: 'smart' | 'balanced'; disabled?: boolean; onChange: (value: 'smart' | 'balanced') => void }) {
  return <div className="studio-storyboard-prompt-mode"><button type="button" disabled={disabled} className={value === 'smart' ? 'is-active' : ''} title="等待后端提供画布分镜提示词任务接口" onClick={() => onChange('smart')}><i />智能合成 · 待接入</button><button type="button" disabled={disabled} className={value === 'balanced' ? 'is-active' : ''} onClick={() => onChange('balanced')}><i />自动平均 · 本地规则</button></div>
}

export function StudioStoryboardStream({ content }: { content: string }) {
  return <div className="studio-storyboard-stream" aria-live="polite"><header><Spin size="small" /><strong>模型正在流式合成</strong></header><pre>{content || '已连接模型，等待首个内容片段…'}</pre></div>
}
