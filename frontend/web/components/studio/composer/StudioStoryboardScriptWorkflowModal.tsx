'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
/* eslint-disable @next/next/no-img-element -- 画布资产支持运行时 URL、blob 与 data 预览。 */
import { useEffect, useMemo, useState } from 'react'
import { CheckOutlined, DeleteOutlined, EllipsisOutlined, EyeOutlined, LoadingOutlined, PlusOutlined, SaveOutlined, SwapOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { Button, InputNumber, Modal, Select, Space, message } from 'antd'
import { usePromptDictionary } from '@/hooks/usePromptDictionary'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioStoryboardAssetKind, StudioStoryboardAssetRef, StudioStoryboardTableRow } from '@/types/studio'
import { userAssetCustomCreate } from '@/utils/business/asset'
import { openImagePreviewModal } from '@/utils/openImagePreviewModal'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { apiErrorMessage } from '@/utils/apiError'
import {
  STUDIO_STORYBOARD_ROW_COLORS,
  composeBalancedStoryboardPrompt,
  createStudioStoryboardRow,
  normalizeStudioStoryboardAssets,
  patchStudioStoryboardRow,
  studioStoryboardProgress,
  studioStoryboardRowsFromNode
} from '@/utils/studio/studioStoryboardScript'
import {
  StudioStoryboardAssetPickerModal,
  StudioStoryboardDialogueModal,
  StudioStoryboardMovementModal,
  StudioStoryboardPromptMode,
  StudioStoryboardPromptModal,
  StudioStoryboardStream,
  StudioStoryboardTextModal
} from './StudioStoryboardScriptEditors'

export interface StudioStoryboardWorkflowDraft {
  rows: StudioStoryboardTableRow[]
  assets: StudioStoryboardAssetRef[]
  step: 1 | 2 | 3
  promptMode: 'smart' | 'balanced'
}

interface Props {
  open: boolean
  node: StudioCanvasNode
  onClose: () => void
  onSave: (draft: StudioStoryboardWorkflowDraft, recordHistory?: boolean) => void
}

const ASSET_SLOTS: Array<{ kind: StudioStoryboardAssetKind; label: string; hint: string }> = [
  { kind: 'scene', label: '场景', hint: '环境、空间或背景图片' },
  { kind: 'character', label: '角色', hint: '人物或主体图片' },
  { kind: 'prop', label: '道具', hint: '关键物件图片' }
]

export function StudioStoryboardScriptWorkflowModal({ open, node, onClose, onSave }: Props) {
  const canvasRuntime = useCanvasProjectRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const deleteNodes = useStudioUiStore((state) => state.deleteNodes)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const { ensureLoaded, shotSizeOptions, cameraMovementOptions } = usePromptDictionary()
  const [rows, setRows] = useState(() => studioStoryboardRowsFromNode(node))
  const [assets, setAssets] = useState(() => normalizeStudioStoryboardAssets(node.data.storyboardAssets))
  const [step, setStep] = useState<1 | 2 | 3>(node.data.storyboardWorkflowStep ?? 1)
  const [promptMode, setPromptMode] = useState<'smart' | 'balanced'>(node.data.storyboardPromptMode ?? 'smart')
  const [textEditor, setTextEditor] = useState<{ rowId: string; field: 'description' | 'lighting' | 'soundEffect'; title: string } | null>(null)
  const [dialogueRowId, setDialogueRowId] = useState<string | null>(null)
  const [movementRowId, setMovementRowId] = useState<string | null>(null)
  const [promptRowId, setPromptRowId] = useState<string | null>(null)
  const [assetPickerKind, setAssetPickerKind] = useState<StudioStoryboardAssetKind | null>(null)
  const [savingAssetNodeId, setSavingAssetNodeId] = useState<string | null>(null)
  const imageNodes = useMemo(() => nodes.filter((item) => !item.hidden && Boolean(item.data.mediaUrl) && (
    item.data.kind === 'image' || item.data.mediaKind === 'image'
  )), [nodes])
  const shotOptions = useMemo(() => shotSizeOptions.map((item) => ({ label: item.value, value: item.value })), [shotSizeOptions])
  const movementOptions = useMemo(() => cameraMovementOptions.map((item) => ({ label: item.value, value: item.value, imageUrl: item.image || undefined })), [cameraMovementOptions])
  const availableImageIds = useMemo(() => new Set(imageNodes.map((item) => item.id)), [imageNodes])
  const validAssets = useMemo(() => normalizeStudioStoryboardAssets(assets, availableImageIds), [assets, availableImageIds])
  const progress = useMemo(() => studioStoryboardProgress(rows, validAssets), [rows, validAssets])
  const smartDisabledReason = '后端暂未提供按画布分镜数据合成提示词的任务接口；请先使用自动平均或手动编辑提示词'
  const busy = false
  const visibleStep: 1 | 2 | 3 = step

  useEffect(() => { void ensureLoaded() }, [ensureLoaded])
  useEffect(() => {
    if (!open) return
    setRows(studioStoryboardRowsFromNode(node))
    setAssets(normalizeStudioStoryboardAssets(node.data.storyboardAssets))
    setStep(node.data.storyboardWorkflowStep ?? 1)
    setPromptMode(node.data.storyboardPromptMode ?? 'smart')
  }, [open, node.id])

  const buildDraft = (
    nextRows = rows,
    nextAssets = validAssets,
    nextStep = step,
    nextMode = promptMode
  ): StudioStoryboardWorkflowDraft => ({
    rows: nextRows,
    assets: nextAssets,
    step: nextStep,
    promptMode: nextMode
  })
  const flushSave = (
    nextRows = rows,
    nextAssets = validAssets,
    nextStep = step,
    nextMode = promptMode,
    recordHistory = false
  ) => onSave(buildDraft(nextRows, nextAssets, nextStep, nextMode), recordHistory)
  const requestClose = () => {
    flushSave(rows, validAssets, step, promptMode, true)
    onClose()
  }
  const persist = (
    nextRows = rows,
    nextAssets = validAssets,
    nextStep = step,
    nextMode = promptMode
  ) => flushSave(nextRows, nextAssets, nextStep, nextMode, false)
  const patchRow = (id: string, patch: Partial<StudioStoryboardTableRow>) => { const next = rows.map((row) => row.id === id ? patchStudioStoryboardRow(row, patch) : row); setRows(next); persist(next) }
  const addRow = () => {
    if (rows.length >= 100) { message.info('最多支持 100 个镜头'); return }
    const next = [...rows, createStudioStoryboardRow(rows.length + 1)]; setRows(next); persist(next)
  }
  const removeRow = (id: string) => {
    if (rows.length === 1) { message.info('至少保留一个镜头'); return }
    const next = rows.filter((row) => row.id !== id).map((row, index) => ({ ...row, shot: `镜头 ${index + 1}` }))
    setRows(next); persist(next)
  }
  const chooseStep = (next: 1 | 2 | 3) => {
    if (busy) { message.info('提示词任务执行中，请先停止生成'); return }
    setStep(next); persist(rows, validAssets, next)
  }
  const chooseMode = (next: 'smart' | 'balanced') => {
    if (busy) return
    setPromptMode(next); persist(rows, validAssets, step, next)
  }
  const selectAsset = (kind: StudioStoryboardAssetKind, source: StudioCanvasNode) => {
    if (!source.data.mediaUrl) return
    const next = normalizeStudioStoryboardAssets([
      ...validAssets.filter((asset) => asset.kind !== kind),
      { kind, nodeId: source.id, title: source.data.title, imageUrl: source.data.mediaUrl }
    ], availableImageIds)
    setAssets(next); persist(rows, next); setAssetPickerKind(null)
  }
  const clearAsset = (kind: StudioStoryboardAssetKind) => { const next = validAssets.filter((asset) => asset.kind !== kind); setAssets(next); persist(rows, next) }
  const jumpToNode = (nodeId: string) => {
    requestClose()
    selectNodes([nodeId])
    window.requestAnimationFrame(() => window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId } })))
  }
  const saveToPersonalAssets = async (source: StudioCanvasNode, kind: StudioStoryboardAssetKind) => {
    if (!source.data.mediaUrl || savingAssetNodeId) return
    setSavingAssetNodeId(source.id)
    try {
      await userAssetCustomCreate({ assetType: kind, assetName: source.data.title, imageUrl: source.data.mediaUrl, promptText: source.data.prompt || undefined })
      message.success('已保存到个人资产库')
    } catch (error: unknown) {
      const detail = error as { msg?: string; message?: string }
      message.error(detail.msg || detail.message || '保存个人资产失败')
    } finally { setSavingAssetNodeId(null) }
  }
  const confirmDeleteImageNode = (source: StudioCanvasNode) => Modal.confirm({
    title: `删除“${source.data.title}”？`, content: '会从项目画布删除图片节点及其连线，并清除脚本中的对应资产引用。',
    okText: '删除', cancelText: '取消', okButtonProps: { danger: true }, className: 'create-flow-modal',
    async onOk() {
      try {
        if (canvasRuntime) await canvasRuntime.deleteNodes([source.id])
        else deleteNodes([source.id])
        const next = validAssets.filter((asset) => asset.nodeId !== source.id)
        setAssets(next)
        persist(rows, next)
      } catch (error: unknown) {
        message.error(apiErrorMessage(error, '删除图片节点失败'))
        throw error
      }
    }
  })
  const synthesize = (targetRows = rows) => {
    if (!targetRows.every((row) => row.description.trim())) { message.warning('请先补全待合成镜头的画面描述'); return }
    if (promptMode === 'smart') { message.error(smartDisabledReason); return }
    const generated = targetRows.map((row) => ({ ...row, ...composeBalancedStoryboardPrompt(row) }))
    const byId = new Map(generated.map((row) => [row.id, row]))
    const next = rows.map((row) => byId.get(row.id) ?? row)
    setRows(next)
    persist(next)
    message.success('已按镜头内容自动平均合成')
  }

  const activeTextRow = textEditor ? rows.find((row) => row.id === textEditor.rowId) : undefined
  const activeDialogueRow = rows.find((row) => row.id === dialogueRowId)
  const activeMovementRow = rows.find((row) => row.id === movementRowId)
  const activePromptRow = rows.find((row) => row.id === promptRowId)
  return <Modal open={open} width="100%" title={null} footer={null} closable={false} keyboard={!busy} mask={{ closable: false }} style={{ top: 0, maxWidth: '100vw', paddingBottom: 0 }} className="create-flow-modal studio-storyboard-table-modal studio-storyboard-workflow-modal" onCancel={requestClose}>
    <div className="studio-storyboard-workflow">
      <StepHeader active={visibleStep} progress={progress} disabled={busy} onStep={chooseStep} onClose={busy ? () => message.info('请先停止当前合成任务') : requestClose} />
      <main>
        {visibleStep === 1 ? <ShotTable rows={rows} shotSizeOptions={shotOptions} onPatch={patchRow} onAdd={addRow} onRemove={removeRow} onOpenText={(rowId, field, title) => setTextEditor({ rowId, field, title })} onOpenDialogue={setDialogueRowId} onOpenMovement={setMovementRowId} onOpenPrompt={setPromptRowId} /> : null}
        {visibleStep === 2 ? <AssetStep assets={validAssets} imageNodes={imageNodes} savingAssetNodeId={savingAssetNodeId} onOpenPicker={setAssetPickerKind} onClear={clearAsset} onJump={jumpToNode} onSavePersonal={(source, kind) => { void saveToPersonalAssets(source, kind) }} onDelete={confirmDeleteImageNode} /> : null}
        {visibleStep === 3 ? <SynthesisStep rows={rows} promptMode={promptMode} smartDisabledReason={smartDisabledReason} synthesizing={false} streamPreview="" onMode={chooseMode} onOpenPrompt={setPromptRowId} onSynthesize={() => synthesize()} onStop={() => undefined} /> : null}
      </main>
      <footer className="studio-storyboard-workflow__footer">
        <button
          type="button"
          className="studio-storyboard-workflow__nav"
          disabled={visibleStep === 1 || busy}
          onClick={() => chooseStep((visibleStep - 1) as 1 | 2)}
        >
          上一步
        </button>
        <span>{progress.promptCount}/{progress.totalShots} 个镜头已具备最终提示词</span>
        {visibleStep < 3 ? (
          <button
            type="button"
            className="studio-storyboard-workflow__nav is-primary"
            disabled={busy}
            onClick={() => chooseStep((visibleStep + 1) as 2 | 3)}
          >
            下一步：{visibleStep === 1 ? '准备资产' : '合成提示词'}
          </button>
        ) : (
          <button
            type="button"
            className="studio-storyboard-workflow__nav is-primary"
            disabled={!progress.complete || busy}
            onClick={requestClose}
          >
            <CheckOutlined />完成编辑
          </button>
        )}
      </footer>
    </div>
    {activeTextRow && textEditor ? <StudioStoryboardTextModal key={`${activeTextRow.id}:${textEditor.field}:${activeTextRow[textEditor.field]}`} open title={textEditor.title} value={activeTextRow[textEditor.field]} placeholder={textEditor.field === 'soundEffect' ? '例如：风声、雨声、脚步声、鼓点……' : '输入镜头内容'} onClose={() => setTextEditor(null)} onSave={(value) => { patchRow(activeTextRow.id, { [textEditor.field]: value }); setTextEditor(null) }} /> : null}
    {activeDialogueRow ? <StudioStoryboardDialogueModal key={`${activeDialogueRow.id}:${JSON.stringify(activeDialogueRow.dialogue)}`} row={activeDialogueRow} onClose={() => setDialogueRowId(null)} onSave={(dialogue) => { patchRow(activeDialogueRow.id, { dialogue }); setDialogueRowId(null) }} /> : null}
    {activeMovementRow ? <StudioStoryboardMovementModal key={`${activeMovementRow.id}:${activeMovementRow.cameraMovement}`} row={activeMovementRow} options={movementOptions} onClose={() => setMovementRowId(null)} onSave={(cameraMovement) => { patchRow(activeMovementRow.id, { cameraMovement }); setMovementRowId(null) }} /> : null}
    {activePromptRow ? <StudioStoryboardPromptModal key={`${activePromptRow.id}:${activePromptRow.finalPrompt}:${activePromptRow.motionPrompt}`} row={activePromptRow} promptMode={promptMode} smartDisabledReason={smartDisabledReason} loading={false} streamPreview="" onMode={chooseMode} onClose={() => setPromptRowId(null)} onPatch={(patch) => patchRow(activePromptRow.id, patch)} onSynthesize={() => synthesize([activePromptRow])} onStop={() => undefined} /> : null}
    <StudioStoryboardAssetPickerModal openKind={assetPickerKind} nodes={imageNodes} onClose={() => setAssetPickerKind(null)} onSelect={(source) => assetPickerKind && selectAsset(assetPickerKind, source)} />
  </Modal>
}

function StepHeader({ active, progress, disabled, onStep, onClose }: { active: 1 | 2 | 3; progress: ReturnType<typeof studioStoryboardProgress>; disabled: boolean; onStep: (step: 1 | 2 | 3) => void; onClose: () => void }) {
  const steps = [{ id: 1 as const, label: '确认镜头', detail: `${progress.confirmedShots}/${progress.totalShots} 已填写画面` }, { id: 2 as const, label: '准备资产', detail: progress.assetCount ? `${progress.assetCount}/3 已选择` : '暂无资产' }, { id: 3 as const, label: '合成提示词', detail: `${progress.promptCount}/${progress.totalShots} 已合成` }]
  return <header className="studio-storyboard-workflow__header">
    <div className="studio-storyboard-workflow__steps" role="tablist" aria-label="分镜脚本步骤">
      {steps.map((item, index) => (
        <div key={item.id} className="studio-storyboard-workflow__step-wrap">
          <button
            type="button"
            role="tab"
            aria-selected={active === item.id}
            disabled={disabled}
            className={active === item.id ? 'is-active' : ''}
            onClick={(event) => {
              event.preventDefault()
              event.stopPropagation()
              onStep(item.id)
            }}
          >
            <i>{item.id}</i>
            <span><strong>{item.label}</strong><small>{item.detail}</small></span>
          </button>
          {index < 2 ? <b /> : null}
        </div>
      ))}
    </div>
    <div className="studio-storyboard-workflow__summary">{progress.promptCount}/{progress.totalShots} 完成后可批量生成视频</div>
    <button type="button" className="studio-storyboard-workflow__close" aria-label="关闭" onClick={onClose}>×</button>
  </header>
}

function ShotTable({ rows, shotSizeOptions, onPatch, onAdd, onRemove, onOpenText, onOpenDialogue, onOpenMovement, onOpenPrompt }: { rows: StudioStoryboardTableRow[]; shotSizeOptions: Array<{ label: string; value: string }>; onPatch: (id: string, patch: Partial<StudioStoryboardTableRow>) => void; onAdd: () => void; onRemove: (id: string) => void; onOpenText: (id: string, field: 'description' | 'lighting' | 'soundEffect', title: string) => void; onOpenDialogue: (id: string) => void; onOpenMovement: (id: string) => void; onOpenPrompt: (id: string) => void }) {
  return <div className="studio-storyboard-shot-table"><div className="studio-storyboard-shot-table__scroll"><div className="studio-storyboard-shot-table__head">{['镜号', '时长', '画面描述', '景别', '光影氛围', '对白·旁白', '音效', '运镜', '最终提示词', '操作'].map((item) => <span key={item}>{item}</span>)}</div>{rows.map((row, index) => <div key={row.id} className="studio-storyboard-shot-table__row" style={{ background: row.rowColor || undefined }}><span>{index + 1}</span><DurationCell key={`${row.id}:${row.duration}`} value={row.duration} onSave={(duration) => onPatch(row.id, { duration })} /><Cell value={row.description} onClick={() => onOpenText(row.id, 'description', `第 ${index + 1} 镜 · 画面描述`)} /><Select variant="borderless" value={row.shotSize || undefined} placeholder="+" options={shotSizeOptions} onChange={(value) => onPatch(row.id, { shotSize: value })} /><Cell value={row.lighting} onClick={() => onOpenText(row.id, 'lighting', `第 ${index + 1} 镜 · 光影氛围`)} /><Cell value={dialogueSummary(row)} onClick={() => onOpenDialogue(row.id)} /><Cell value={row.soundEffect} onClick={() => onOpenText(row.id, 'soundEffect', `第 ${index + 1} 镜 · 音效`)} /><Cell value={row.cameraMovement} onClick={() => onOpenMovement(row.id)} /><button type="button" className="studio-storyboard-cell-button studio-storyboard-cell-button--prompt" onClick={() => onOpenPrompt(row.id)}>{row.finalPrompt && row.motionPrompt ? '查看提示词' : '待生成提示词'}</button><StudioCanvasPopover trigger="click" placement="bottomRight" arrow={false} content={<RowActions row={row} onPatch={onPatch} onRemove={onRemove} />}><button type="button" className="studio-storyboard-row-menu"><EllipsisOutlined /></button></StudioCanvasPopover></div>)}</div><button type="button" className="studio-storyboard-add-shot" disabled={rows.length >= 100} title={rows.length >= 100 ? '最多支持 100 个镜头' : undefined} onClick={onAdd}><PlusOutlined />{rows.length >= 100 ? '已达 100 个镜头上限' : '新增镜头'}</button></div>
}

function DurationCell({ value, onSave }: { value: number; onSave: (value: number) => void }) {
  const clampDuration = (next: number | null) => Math.min(10, Math.max(3, Number(next) || 5))
  return (
    <Space.Compact className="studio-storyboard-duration">
      <InputNumber
        min={3}
        max={10}
        precision={0}
        controls={false}
        value={value}
        onChange={(next) => onSave(clampDuration(next))}
      />
      <span className="studio-storyboard-duration__suffix">s</span>
    </Space.Compact>
  )
}

function Cell({ value, onClick }: { value: string; onClick: () => void }) { return <button type="button" className="studio-storyboard-cell-button" title={value} onClick={onClick}>{value || '+'}</button> }
function RowActions({ row, onPatch, onRemove }: { row: StudioStoryboardTableRow; onPatch: (id: string, patch: Partial<StudioStoryboardTableRow>) => void; onRemove: (id: string) => void }) { return <div className="studio-storyboard-row-actions"><span>请选择颜色</span><div>{STUDIO_STORYBOARD_ROW_COLORS.map((color) => <button key={color || 'none'} type="button" className={row.rowColor === color || (!row.rowColor && !color) ? 'is-active' : ''} style={{ background: color || 'transparent' }} aria-label={color ? `使用配色 ${color}` : '清除配色'} onClick={() => onPatch(row.id, { rowColor: color || undefined })} />)}</div><button type="button" onClick={() => onRemove(row.id)}><DeleteOutlined />删除该行</button></div> }

function AssetStep({ assets, imageNodes, savingAssetNodeId, onOpenPicker, onClear, onJump, onSavePersonal, onDelete }: { assets: StudioStoryboardAssetRef[]; imageNodes: StudioCanvasNode[]; savingAssetNodeId: string | null; onOpenPicker: (kind: StudioStoryboardAssetKind) => void; onClear: (kind: StudioStoryboardAssetKind) => void; onJump: (nodeId: string) => void; onSavePersonal: (node: StudioCanvasNode, kind: StudioStoryboardAssetKind) => void; onDelete: (node: StudioCanvasNode) => void }) {
  return <div className="studio-storyboard-assets">{ASSET_SLOTS.map((slot) => { const asset = assets.find((item) => item.kind === slot.kind); const source = asset ? imageNodes.find((item) => item.id === asset.nodeId) : undefined; return <section key={slot.kind}><header><strong>{slot.label}</strong><span>{slot.hint}</span></header>{asset && source ? <div className="studio-storyboard-asset-card"><img src={asset.imageUrl} alt={asset.title} /><span>{asset.title}</span><StudioCanvasPopover trigger="click" placement="bottomRight" arrow={false} content={<div className="studio-storyboard-asset-actions"><button type="button" onClick={() => openImagePreviewModal({ url: asset.imageUrl, title: asset.title })}><EyeOutlined />预览</button><button type="button" onClick={() => onOpenPicker(slot.kind)}><SwapOutlined />替换图片</button><button type="button" onClick={() => onJump(source.id)}>跳转至节点</button><button type="button" onClick={() => onClear(slot.kind)}>清除图片</button><button type="button" disabled={savingAssetNodeId === source.id} onClick={() => onSavePersonal(source, slot.kind)}>{savingAssetNodeId === source.id ? <LoadingOutlined /> : <SaveOutlined />}保存到个人资产</button><button type="button" className="is-danger" onClick={() => onDelete(source)}><DeleteOutlined />删除</button></div>}><button type="button" className="studio-storyboard-asset-card__menu"><EllipsisOutlined /></button></StudioCanvasPopover></div> : <button type="button" className="studio-storyboard-asset-empty" onClick={() => onOpenPicker(slot.kind)}><PlusOutlined /><span>从当前画布选择{slot.label}图片</span></button>}</section> })}</div>
}

function SynthesisStep({ rows, promptMode, smartDisabledReason, synthesizing, streamPreview, onMode, onOpenPrompt, onSynthesize, onStop }: { rows: StudioStoryboardTableRow[]; promptMode: 'smart' | 'balanced'; smartDisabledReason: string | null; synthesizing: boolean; streamPreview: string; onMode: (mode: 'smart' | 'balanced') => void; onOpenPrompt: (id: string) => void; onSynthesize: () => void; onStop: () => void }) { const disabled = promptMode === 'smart' && Boolean(smartDisabledReason); return <div className="studio-storyboard-synthesis"><div className="studio-storyboard-synthesis__toolbar"><StudioStoryboardPromptMode value={promptMode} disabled={synthesizing} onChange={onMode} />{synthesizing ? <Button danger onClick={onStop}>停止生成</Button> : <Button type="primary" title={disabled ? smartDisabledReason || undefined : undefined} disabled={disabled} onClick={onSynthesize}><ThunderboltOutlined />一键合成提示词</Button>}</div>{disabled ? <p className="studio-storyboard-synthesis__warning">{smartDisabledReason}</p> : null}{synthesizing ? <StudioStoryboardStream content={streamPreview} /> : null}<div className="studio-storyboard-synthesis__list">{rows.map((row, index) => <button key={row.id} type="button" disabled={synthesizing} onClick={() => onOpenPrompt(row.id)}><i>{index + 1}</i><span><strong>{row.shot} · {row.duration}s</strong><small>{row.description || '尚未填写画面描述'}</small></span><em className={row.finalPrompt && row.motionPrompt ? 'is-complete' : ''}>{row.finalPrompt && row.motionPrompt ? '查看提示词' : '待合成'}</em></button>)}</div></div> }
function dialogueSummary(row: StudioStoryboardTableRow): string { return row.dialogue.filter((item) => item.content.trim()).map((item) => item.kind === 'narration' ? `[旁白]${item.content}` : `${item.speaker || '未绑定'}：${item.content}`).join('\n') }
