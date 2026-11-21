'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioChromeIcon } from '@/components/studio/theme/StudioChromeIcon'
import { useEffect, useMemo, useState } from 'react'
import {
  AppstoreOutlined,
  BgColorsOutlined,
  EditOutlined,
  GlobalOutlined,
  PictureOutlined,
  ReloadOutlined,
  RotateRightOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { Button, Input, Modal, Select, Tooltip } from 'antd'
import { BillingQuoteHint } from '@/components/common/BillingQuoteHint'
import { useBillingQuote } from '@/hooks/useBillingQuote'
import type { StudioImageActions } from '@/hooks/studio/useStudioImageActions'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import type { BillingQuoteRequest } from '@/types/business-api'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { StudioComposerChip } from './StudioComposerChip'

type DialogKind = 'edit' | 'multiView' | 'setting' | null
type StudioSettingSubject = 'scene' | 'character' | 'prop'
type StudioSettingLayout = 'single' | 'grid4'

export function StudioImageActionPanel({ node, actions }: { node: StudioCanvasNode; actions: StudioImageActions }) {
  const openImageLighting = useStudioUiStore((state) => state.openImageLighting)
  const [dialog, setDialog] = useState<DialogKind>(null)
  const [input, setInput] = useState('')
  const [gridSize, setGridSize] = useState<2 | 3>(3)
  const [submitting, setSubmitting] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const [settingSubject, setSettingSubject] = useState<StudioSettingSubject>('character')
  const [settingLayout, setSettingLayout] = useState<StudioSettingLayout>('single')

  useEffect(() => {
    const handleHeaderRequest = (event: Event) => {
      const requestedNodeId = (event as CustomEvent<{ nodeId?: string }>).detail?.nodeId
      if (requestedNodeId === node.id) setPanelOpen(true)
    }
    window.addEventListener('studio:open-image-tools', handleHeaderRequest)
    return () => window.removeEventListener('studio:open-image-tools', handleHeaderRequest)
  }, [node.id])

  const openDialog = (kind: Exclude<DialogKind, null>, initial: string) => {
    setInput(initial)
    setDialog(kind)
  }

  const submitDialog = async () => {
    setSubmitting(true)
    try {
      const ok = dialog === 'multiView'
        ? await actions.multiView(input)
        : dialog === 'setting'
          ? await actions.edit(buildStudioSettingPrompt(input, settingSubject, settingLayout), '设定图', node.data.imagePurpose)
          : await actions.edit(input, '图片编辑', node.data.imagePurpose)
      if (ok) setDialog(null)
    } finally {
      setSubmitting(false)
    }
  }
  const reason = dialog === 'multiView' ? actions.reasonFor('multiView') : actions.reasonFor('edit')
  const taskPrompt = useMemo(() => dialog === 'setting'
    ? buildStudioSettingPrompt(input, settingSubject, settingLayout)
    : input.trim(), [dialog, input, settingLayout, settingSubject])
  const quoteRequest = useMemo<BillingQuoteRequest | null>(() => {
    const prompt = taskPrompt
    if (!dialog || !prompt || reason || !node.data.mediaUrl) return null
    if (dialog === 'multiView') {
      const model = actions.pools.multiView[0]
      if (!model || !actions.binding.storyboardId) return null
      return {
        quoteType: 'STORYBOARD_MULTI_VIEW_IMAGE',
        payload: {
          storyboardId: actions.binding.storyboardId,
          imageUrl: node.data.mediaUrl,
          angles: [prompt],
          modelCode: model.modelCode,
          aspectRatio: node.data.generationOptions?.aspectRatio || model.defaultAspectRatio || '1:1'
        }
      }
    }
    const model = actions.pools.edit[0]
    if (!model || !actions.binding.storyboardId) return null
    return {
      quoteType: 'STORYBOARD_EDIT_IMAGE',
      payload: {
        storyboardId: actions.binding.storyboardId,
        referenceImage: node.data.mediaUrl,
        prompt,
        modelCode: model.modelCode,
        aspectRatio: node.data.generationOptions?.aspectRatio || model.defaultAspectRatio || '1:1',
        size: model.defaultSizeCode || node.data.generationOptions?.quality || '2K',
        imageCount: 1
      }
    }
  }, [actions.binding.storyboardId, actions.pools.edit, actions.pools.multiView, dialog, node.data.generationOptions?.aspectRatio, node.data.generationOptions?.quality, node.data.mediaUrl, reason, taskPrompt])
  const quote = useBillingQuote(quoteRequest)

  const panelContent = (
    <div className="studio-image-actions">
      <header><strong>图片工具</strong><span>真实任务按功能模型池提交</span></header>
      <ActionButton icon={<StudioChromeIcon name="imageEdit" fallback={<EditOutlined />} />} label="编辑" description="输入修改要求并生成新节点" reason={actions.reasonFor('edit')} onClick={() => openDialog('edit', node.data.prompt)} />
      <ActionButton icon={<StudioChromeIcon name="sun" fallback={<BgColorsOutlined />} />} label="打光" description="本地预览亮度、色温、方向和轮廓光" reason={node.data.mediaUrl ? null : '请先上传或生成源图片'} onClick={() => openImageLighting(node.id)} />
      <GridActionRow
        gridSize={gridSize}
        busy={actions.localGridBusy}
        reason={actions.reasonFor('grid')}
        onGridSizeChange={setGridSize}
        onRun={() => { void actions.splitGrid(gridSize) }}
      />
      <ActionButton icon={<StudioChromeIcon name="angle" fallback={<RotateRightOutlined />} />} label="多机位" description="通过真实多机位模型生成新观察角度" reason={actions.reasonFor('multiView')} onClick={() => openDialog('multiView', '保持主体一致，切换为右前方四分之三机位')} />
      <ActionButton icon={<StudioChromeIcon name="imageUpscale" fallback={<ThunderboltOutlined />} />} label="图片高清" description="基于真实生成记录提交高清任务" reason={actions.reasonFor('upscale')} onClick={() => { void actions.upscale() }} />
      <ActionButton icon={<StudioChromeIcon name="imageGlobalLight" fallback={<GlobalOutlined />} />} label="全局光影" description="调整全局补光并形成编辑提示词" reason={node.data.mediaUrl ? null : '请先上传或生成源图片'} onClick={() => openImageLighting(node.id)} />
      <ActionButton icon={<StudioChromeIcon name="imageSetting" fallback={<PictureOutlined />} />} label="设定图" description="保持主体一致，生成标准化设定图" reason={actions.reasonFor('edit')} onClick={() => openDialog('setting', '保持主体身份与外观一致，生成标准化设定图，背景干净，比例准确，细节清晰')} />
      <ActionButton icon={<StudioChromeIcon name="imageRegenerate" fallback={<ReloadOutlined />} />} label="重新生成" description="复用真实分镜生图任务与统一 SSE" reason={actions.reasonFor('storyboard')} onClick={() => { void actions.regenerate() }} />
    </div>
  )

  return (
    <>
      <StudioCanvasPopover
        open={panelOpen}
        onOpenChange={setPanelOpen}
        trigger="click"
        nodePlacement
        arrow={false}
        getPopupContainer={getStudioComposerPopupContainer}
        classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
        content={panelContent}
      >
        <StudioComposerChip icon={<StudioChromeIcon name="sparkle" fallback={<EditOutlined />} />} label="图片工具" />
      </StudioCanvasPopover>
      <Modal
        open={Boolean(dialog)}
        title={dialog === 'multiView' ? '多机位生成' : dialog === 'setting' ? '设定图' : '图片编辑'}
        footer={null}
        className="create-flow-modal studio-image-action-modal"
        onCancel={() => setDialog(null)}
      >
        <div className="studio-image-action-dialog">
          {dialog === 'setting' ? (
            <div className="studio-image-setting-options">
              <label><span>设定图类型</span><Select<StudioSettingSubject> value={settingSubject} onChange={setSettingSubject} options={[
                { value: 'scene', label: '场景设定图' },
                { value: 'character', label: '角色设定图' },
                { value: 'prop', label: '道具设定图' }
              ]} /></label>
              <label><span>版式</span><Select<StudioSettingLayout> value={settingLayout} onChange={setSettingLayout} options={[
                { value: 'single', label: '单图' },
                { value: 'grid4', label: '四宫格整图' }
              ]} /></label>
            </div>
          ) : null}
          <Input.TextArea value={input} rows={6} maxLength={1800} showCount onChange={(event) => setInput(event.target.value)} />
          <p>{reason || `将使用 ${dialog === 'multiView' ? actions.pools.multiView[0]?.modelName : actions.pools.edit[0]?.modelName} 提交真实任务。`}</p>
          {dialog === 'setting' && settingLayout === 'grid4' ? <p>本次真实编辑任务只生成一张四宫格整图。任务完成后如需拆成多个节点，请由用户在图片工具中显式执行“宫格切分”。</p> : null}
          <BillingQuoteHint quote={quote.quote} loading={quote.loading} error={quote.error} active={Boolean(quoteRequest)} idleText="参数完整后显示服务端报价" />
          <div><Button onClick={() => setDialog(null)}>取消</Button><Button type="primary" disabled={Boolean(reason) || !input.trim()} loading={submitting} onClick={() => { void submitDialog() }}>{dialog === 'setting' && settingLayout === 'grid4' ? '生成四宫格整图节点' : '生成新节点'}</Button></div>
        </div>
      </Modal>
    </>
  )
}

export function buildStudioSettingPrompt(input: string, subject: StudioSettingSubject, layout: StudioSettingLayout): string {
  const subjectInstruction: Record<StudioSettingSubject, string> = {
    scene: '场景设定图，保持空间结构、时代环境、材质、光线和视觉风格统一，不新增无关角色',
    character: '角色设定图，保持角色身份、五官、发型、服装、体型和配色严格一致',
    prop: '道具设定图，保持道具结构、材质、比例、功能细节和磨损特征严格一致'
  }
  const layoutInstruction = layout === 'grid4'
    ? '四宫格整图版式，在同一张成品图中排布四个清晰画格，从正面、侧面、背面与细节视角展示；仅生成一张整图，不拆分或伪造多个结果'
    : '单图版式，主体完整、构图清晰、留白规范，适合作为标准设定参考'
  return `${subjectInstruction[subject]}。${layoutInstruction}。编辑要求：${input.trim()}`
}

function ActionButton({ icon, label, description, reason, onClick }: { icon: React.ReactNode; label: string; description: string; reason: string | null; onClick: () => void }) {
  return (
    <Tooltip title={reason || ''} placement="right">
      <span className="studio-image-actions__button-wrap">
        <button type="button" className="studio-image-actions__row studio-image-actions__row--button" disabled={Boolean(reason)} onClick={onClick}>
          <i>{icon}</i><span className="studio-image-actions__copy"><strong>{label}</strong><small>{reason || description}</small></span>
        </button>
      </span>
    </Tooltip>
  )
}

function GridActionRow({
  gridSize,
  busy,
  reason,
  onGridSizeChange,
  onRun
}: {
  gridSize: 2 | 3
  busy: boolean
  reason: string | null
  onGridSizeChange: (value: 2 | 3) => void
  onRun: () => void
}) {
  return (
    <div className="studio-image-actions__row studio-image-actions__grid-row">
      <i><StudioChromeIcon name="gridSplit" fallback={<AppstoreOutlined />} /></i>
      <span className="studio-image-actions__copy">
        <strong>宫格切分</strong>
        <small>{reason || '浏览器确定性切成 4 或 9 张，不创建 SSE'}</small>
      </span>
      <Select
        value={gridSize}
        disabled={busy}
        onChange={onGridSizeChange}
        options={[{ value: 2, label: '四宫格' }, { value: 3, label: '九宫格' }]}
      />
      <Tooltip title={reason || ''} placement="right">
        <span className="studio-image-actions__disabled-trigger">
          <Button type="primary" size="small" disabled={Boolean(reason)} loading={busy} onClick={onRun}>切分</Button>
        </span>
      </Tooltip>
    </div>
  )
}
