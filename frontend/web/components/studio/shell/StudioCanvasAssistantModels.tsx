'use client'

import { useState } from 'react'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { StudioModelTypeTabs } from '@/components/studio/composer/StudioModelTypeTabs'
import { CloseOutlined, PictureOutlined, ToolOutlined, VideoCameraOutlined } from '@ant-design/icons'
import type { AssistantModelSelection } from '@/types/assistant-api'
import { useCanvasAssistantModels } from '@/hooks/studio/useCanvasAssistantModels'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioModelMenuOption } from '@/components/studio/composer/StudioModelMenuOption'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'

const MODEL_TYPES = [{ value: 'image', label: '图片' }, { value: 'video', label: '视频' }] as const

export function StudioCanvasAssistantModels({ selected, disabled, onChange }: {
  selected: AssistantModelSelection[]; disabled: boolean; onChange: (models: AssistantModelSelection[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [kind, setKind] = useState<'image' | 'video'>('image')
  const [search, setSearch] = useState('')
  const imageCatalog = useCanvasAssistantModels('image', true)
  const videoCatalog = useCanvasAssistantModels('video', true)
  const catalog = kind === 'image' ? imageCatalog : videoCatalog
  const query = search.trim().toLocaleLowerCase()
  const options = catalog.data.filter(item => [item.modelName, item.modelCode, item.providerName].some(value => value?.toLocaleLowerCase().includes(query)))
  const active = selected.find(model => model.modelType === kind)?.modelCode
  return <StudioCanvasPopover open={open && !disabled} onOpenChange={value => { if (!disabled) setOpen(value) }}
    trigger="click" placement="topLeft" arrow={false} autoAdjustOverflow
    align={{ htmlRegion: 'visible', overflow: { adjustX: true, adjustY: true, shiftX: true, shiftY: true } }} classNames={{ root: STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS }}
    getPopupContainer={getStudioComposerPopupContainer} content={<div className="studio-model-menu studio-model-menu--tabbed" role="dialog" aria-label="选择图片或视频模型">
      <StudioModelTypeTabs options={MODEL_TYPES} value={kind} onChange={type => { setKind(type); setSearch('') }} />
      <div className="studio-model-menu__filters">
        <input aria-label="搜索模型或供应商" placeholder="搜索模型或供应商" value={search} onChange={event => setSearch(event.target.value)} />
        <button type="button" disabled={catalog.loading} onClick={catalog.reload}>重新读取</button>
      </div>
      <div className="studio-model-menu__list studio-canvas-popover-scroll" role="listbox" aria-busy={catalog.loading} aria-label={kind === 'image' ? '图片模型' : '视频模型'}>
        {catalog.loading && !catalog.data.length ? <p className="studio-model-menu__state" role="status">正在读取可用模型…</p> : null}
        {catalog.error ? <p className="studio-model-menu__state is-error" role="alert">{catalog.error}</p> : null}
        {!catalog.loading && !catalog.error && !options.length ? <p className="studio-model-menu__state">没有匹配的可用模型</p> : null}
        {options.map(item => <StudioModelMenuOption key={item.modelCode} item={item} selected={active === item.modelCode} onSelect={() => {
          onChange([...selected.filter(model => model.modelType !== kind), { modelType: kind, modelCode: item.modelCode, modelName: item.modelName, providerName: item.providerName || undefined }])
          setOpen(false)
        }} />)}
      </div>
    </div>}>
    <StudioComposerChip label="模型" icon={<StudioIconGlyph name="model" />} className="canvas-agent-tool-select is-icon-only" arrow={false} disabled={disabled} active={open}
      aria-label="选择图片或视频模型" aria-haspopup="dialog" aria-expanded={open && !disabled} />
  </StudioCanvasPopover>
}

export function StudioCanvasAssistantChoices({ models, skill, disabled, onRemoveModel, onRemoveSkill }: {
  models: AssistantModelSelection[]; skill?: { code: string; name: string }; disabled: boolean
  onRemoveModel: (type: 'image' | 'video') => void; onRemoveSkill: () => void
}) {
  if (!models.length && !skill) return null
  return <ul className="canvas-assistant-choice-chips" aria-label="本次选择的模型和Skill">
    {models.map(model => <li key={model.modelType} title={[model.modelName, model.providerName].filter(Boolean).join(' · ')}>
      {model.modelType === 'image' ? <PictureOutlined aria-hidden /> : <VideoCameraOutlined aria-hidden />}<span>{model.modelName}</span>
      <button type="button" disabled={disabled} aria-label={`移除${model.modelType === 'image' ? '图片' : '视频'}模型：${model.modelName}`} onClick={() => onRemoveModel(model.modelType)}><CloseOutlined aria-hidden /></button>
    </li>)}
    {skill ? <li title={skill.name}><ToolOutlined aria-hidden /><span>{skill.name}</span><button type="button" disabled={disabled} aria-label={`移除Skill：${skill.name}`} onClick={onRemoveSkill}><CloseOutlined aria-hidden /></button></li> : null}
  </ul>
}
