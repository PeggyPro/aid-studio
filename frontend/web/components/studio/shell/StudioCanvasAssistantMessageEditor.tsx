'use client'

import { useEffect, useId, useRef, useState } from 'react'
import { DownOutlined, FileTextOutlined, PictureOutlined, SoundOutlined, VideoCameraOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioCanvasDropdownMenu, type StudioCanvasDropdownItem } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasCollapse } from '@/components/studio/canvas/StudioCanvasCollapse'
import { useCanvasAssistantModels } from '@/hooks/studio/useCanvasAssistantModels'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { detectStudioMention, removeStudioMentionToken } from '@/utils/studio/studioMention'
import type { StudioMentionState } from '@/utils/studio/studioMention'
import type { AssistantMode, AssistantModule, AssistantModelSelection } from '@/types/assistant-api'
import { shouldPlayCanvasAgentTypewriter } from '@/utils/studio/canvasAgentTypewriter'
import { useCanvasAgentTypewriter } from './useCanvasAgentTypewriter'
import { studioSkillSlashQuery, type CanvasSkillItem } from '@/utils/studio/canvasSkillCatalog'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

type InlineCommand = StudioMentionState & { kind: 'node' | 'skill'; source: string }
export type CanvasAssistantSkillCommands = {
  items: CanvasSkillItem[]; favorites: string[]
  loading: boolean; error: string; reload: () => Promise<void>; onSelect: (code: string) => void
  search: (keyword: string, scope?: 'OFFICIAL' | 'OWNED' | 'FAVORITES') => Promise<void>
  hasMore: boolean; loadMore: () => Promise<void>; locked: boolean
}
type ReferenceGroup = 'nodes' | 'models' | 'skills'
const COLLAPSE_THRESHOLD = 6

const PROMPTS = {
  UNIVERSAL: ['聊聊你的创意，我来帮你梳理', '输入 @，带上画布里的素材一起创作', '选择 Skill，探索下一步创作方向'],
  CRAFT: ['从一句话开始，构思你的原创故事', '一起设计人物、冲突与故事世界', '写下故事想法，展开第一幕'],
  ADAPT: ['粘贴原文，聊聊你想改编的方向', '输入 @，引用需要编辑的画布文本', '一起润色对白、节奏与故事结构']
} as const

/** @ selects the same saved canvas identities as a node click; no content or URL becomes write authority. */
export function StudioCanvasAssistantMessageEditor({ id, value, disabled, active = false, idle = false, placeholder, onChange, onSubmit, nodes, onReference, unavailable, introDone = true, reducedMotion = true, moduleCode = 'UNIVERSAL', mode = 'CRAFT', skillCommands, modelCommands }: {
  id: string; value: string; disabled: boolean; placeholder: string; onChange: (value: string) => void; onSubmit: () => void
  active?: boolean; idle?: boolean; introDone?: boolean; reducedMotion?: boolean; moduleCode?: AssistantModule; mode?: AssistantMode
  nodes: StudioCanvasNode[]; onReference: (id: string) => boolean; unavailable: (node: StudioCanvasNode) => string | undefined
  skillCommands?: CanvasAssistantSkillCommands
  modelCommands?: { enabled: boolean; locked: boolean; onSelect: (model: AssistantModelSelection) => void }
}) {
  const input = useRef<HTMLTextAreaElement>(null), menu = useRef<HTMLDivElement>(null)
  const menuId = useId(), dismissed = useRef<{ value: string; caret: number } | null>(null)
  const [command, setCommand] = useState<InlineCommand | null>(null)
  const [activeIndex, setActiveIndex] = useState(0)
  const [focused, setFocused] = useState(false)
  const [expanded, setExpanded] = useState<Partial<Record<ReferenceGroup, boolean>>>({})
  const [skillScope, setSkillScope] = useState<'OFFICIAL' | 'OWNED'>('OFFICIAL')
  const typewriterActive = shouldPlayCanvasAgentTypewriter({ introDone, value, focused, enabled: idle && !disabled && !active, reducedMotion })
  const typewriterText = useCanvasAgentTypewriter(typewriterActive, PROMPTS[moduleCode === 'UNIVERSAL' ? 'UNIVERSAL' : mode])
  const detectCommand = (text: string, caret: number): InlineCommand | null => {
    const mention = detectStudioMention(text, caret)
    if (mention) return { ...mention, kind: 'node', source: text }
    const slash = moduleCode === 'UNIVERSAL' && skillCommands ? studioSkillSlashQuery(text, caret) : null
    return slash ? { ...slash, kind: 'skill', source: text } : null
  }
  const updateCommand = (target: HTMLTextAreaElement) => {
    if (dismissed.current?.value === target.value && dismissed.current.caret === target.selectionStart) return
    dismissed.current = null
    const next = target.selectionStart === target.selectionEnd ? detectCommand(target.value, target.selectionStart) : null
    if (next && (!command || next.kind !== command.kind || next.start !== command.start)) {
      setExpanded({}); setSkillScope('OFFICIAL')
    }
    if (next?.kind !== command?.kind || next?.start !== command?.start || next?.query !== command?.query) setActiveIndex(0)
    setCommand(next)
  }
  const dismiss = (restoreFocus = false) => {
    const target = input.current
    if (target) dismissed.current = { value: target.value, caret: target.selectionStart }
    setCommand(null)
    if (restoreFocus && target) target.focus({ preventScroll: true })
  }
  const currentCommand = command?.source === value
    && (command.kind === 'node' || moduleCode === 'UNIVERSAL' && skillCommands) ? command : null
  const [lastCommand, setLastCommand] = useState<InlineCommand | null>(null)
  if (currentCommand && currentCommand !== lastCommand) setLastCommand(currentCommand)
  const popupCommand = currentCommand || lastCommand
  const query = popupCommand?.query.toLocaleLowerCase() || ''
  const commandOpen = !disabled && Boolean(currentCommand)
  const mentionsOpen = commandOpen && currentCommand?.kind === 'node'
  const imageModels = useCanvasAssistantModels('image', Boolean(mentionsOpen && modelCommands?.enabled))
  const videoModels = useCanvasAssistantModels('video', Boolean(mentionsOpen && modelCommands?.enabled))
  const searchSkills = skillCommands?.search
  useEffect(() => {
    if (!commandOpen || !searchSkills || moduleCode !== 'UNIVERSAL') return
    // Both / and @ are explicit discovery actions. Never depend on opening the Skill button first.
    const timer = window.setTimeout(() => { void searchSkills(query, skillScope) }, query ? 200 : 0)
    return () => window.clearTimeout(timer)
  }, [commandOpen, query, searchSkills, skillScope, moduleCode])
  const items: StudioCanvasDropdownItem[] = nodes
    .filter(node => !query || String(node.data.title || node.data.label || '').toLocaleLowerCase().includes(query))
    .map(node => {
      const reason = unavailable(node)
      const image = node.data.kind === 'image' && typeof node.data.mediaUrl === 'string' && /^https?:\/\//.test(node.data.mediaUrl) ? node.data.mediaUrl : undefined
      return { key: `node:${node.id}`, label: String(node.data.title || node.data.label || '未命名节点'), disabled: Boolean(reason), disabledHint: reason,
        icon: image ? (
          // eslint-disable-next-line @next/next/no-img-element -- Existing authorized canvas preview, not an inferred archive URL.
          <img src={image} alt="" loading="lazy" referrerPolicy="no-referrer" />
        ) : node.data.kind === 'image' ? <PictureOutlined /> : node.data.kind === 'video' ? <VideoCameraOutlined /> : ['voice', 'audio', 'music'].includes(node.data.kind || '') ? <SoundOutlined /> : <FileTextOutlined /> }
    })
  const skillItems = moduleCode === 'UNIVERSAL' ? (skillCommands?.items || []).filter(item => item.available
    && (popupCommand?.kind === 'skill' || item.code)
    && (!query || `${item.name} ${item.code} ${item.description}`.toLocaleLowerCase().includes(query))) : []
  const skillChoices: StudioCanvasDropdownItem[] = skillItems.map(item => ({
    key: `skill:${item.code}`, label: item.name, icon: <StudioIconGlyph name="skill" />,
    disabled: skillCommands?.locked, disabledHint: '本轮已开始，结束后可选择下一轮 Skill'
  }))
  const modelSelections = [
    ...imageModels.data.map(model => ({ modelType: 'image' as const, modelCode: model.modelCode, modelName: model.modelName, providerName: model.providerName || undefined })),
    ...videoModels.data.map(model => ({ modelType: 'video' as const, modelCode: model.modelCode, modelName: model.modelName, providerName: model.providerName || undefined }))
  ]
  const modelChoices: StudioCanvasDropdownItem[] = modelSelections.filter(model => !query || `${model.modelName} ${model.modelCode} ${model.providerName || ''}`.toLocaleLowerCase().includes(query)).map(model => ({
    key: `model:${model.modelType}:${model.modelCode}`, label: model.modelName,
    description: `${model.modelType === 'image' ? '图片' : '视频'} · ${model.providerName || model.modelCode}`,
    icon: model.modelType === 'image' ? <PictureOutlined /> : <VideoCameraOutlined />,
    disabled: modelCommands?.locked, disabledHint: '本轮模型已固定，结束后可选择下一轮模型'
  }))
  const groups: { key: ReferenceGroup; label: string; items: StudioCanvasDropdownItem[]; empty: string; loading?: boolean }[] = [
    { key: 'nodes', label: '节点', items, empty: '没有匹配的节点' },
    { key: 'models', label: '模型', items: modelChoices, loading: imageModels.loading || videoModels.loading, empty: !modelCommands?.enabled ? '当前助手未开放模型选择' : imageModels.loading || videoModels.loading ? '正在读取模型…' : '没有匹配的可用模型' },
    { key: 'skills', label: 'skill', items: skillChoices, loading: skillCommands?.loading, empty: moduleCode !== 'UNIVERSAL' ? '请切换全能助手选择 Skill' : skillCommands?.loading ? '正在读取 Skill…' : '没有匹配的 Skill' }
  ]
  const groupOpen = (group: typeof groups[number]) => expanded[group.key] ?? (Boolean(query) || !group.loading && group.items.length <= COLLAPSE_THRESHOLD)
  const choices = popupCommand?.kind === 'skill' ? skillChoices : groups.flatMap(group => groupOpen(group) ? group.items : [])
  const usable = choices.filter(item => !item.disabled)
  const activeChoice = usable[Math.min(activeIndex, Math.max(0, usable.length - 1))]
  const select = (key: string) => {
    const target = input.current
    if (disabled || !target || target.selectionStart !== target.selectionEnd || !currentCommand) return
    const current = detectCommand(target.value, target.selectionStart)
    if (!current || current.kind !== currentCommand.kind || current.start !== currentCommand.start || current.query !== currentCommand.query) return
    if (!usable.some(item => item.key === key)) return
    if (key.startsWith('node:')) { if (!onReference(key.slice(5))) return }
    else if (key.startsWith('model:')) {
      const selected = modelSelections.find(model => `model:${model.modelType}:${model.modelCode}` === key)
      if (!selected || !modelCommands?.enabled || modelCommands.locked) return
      modelCommands.onSelect(selected)
    } else {
      const selected = skillItems.find(item => `skill:${item.code}` === key)
      if (!selected || !skillCommands || skillCommands.locked) return
      skillCommands.onSelect(selected.code)
    }
    // Replace only the active token; surrounding whitespace and prose belong to the draft.
    const next = removeStudioMentionToken(target.value, current)
    onChange(next); dismiss()
    requestAnimationFrame(() => { if (input.current === target && !target.disabled) { target.focus({ preventScroll: true }); target.setSelectionRange(current.start, current.start) } })
  }
  useEffect(() => {
    if (!commandOpen) return
    menu.current?.querySelector('.is-active')?.scrollIntoView?.({ block: 'nearest' })
  }, [commandOpen, activeChoice?.key])
  return <div className={`canvas-assistant-message-editor${typewriterActive ? ' is-typewriting' : ''}`}>
    <StudioCanvasPopover fresh open={!disabled && Boolean(currentCommand)} onOpenChange={open => { if (!open) dismiss() }} placement="topLeft" arrow={false} trigger="click" getPopupContainer={getStudioComposerPopupContainer}
      align={{ overflow: { adjustX: true, adjustY: true, shiftX: true, shiftY: true } }}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
      content={<div ref={menu} id={menuId} inert={!commandOpen} aria-hidden={!commandOpen} className={`canvas-assistant-mention-picker${popupCommand?.kind === 'skill' ? ' canvas-assistant-skill-command' : ''}`} onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); dismiss(true); return }
        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
        const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || []).filter(button => !button.closest('[inert]'))
        if (!buttons.length) return
        event.preventDefault()
        const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
        buttons[event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length]?.focus()
      }} onMouseDown={event => event.preventDefault()}>
        {popupCommand?.kind === 'skill' ? <><div className="canvas-assistant-mention-picker__heading"><span>Skill</span><small>{query ? `/${query}` : '输入名称搜索'}</small></div>
          <StudioCanvasDropdownMenu items={skillChoices} activeKey={activeChoice?.key} onSelect={select} ariaLabel="选择 Skill 命令" />
          {!skillChoices.length ? <p role="status">{skillCommands?.loading ? '正在读取 Skill…' : '没有匹配的 Skill'}</p> : null}
        </> : groups.map(group => <section key={group.key}>
          <button type="button" className="canvas-assistant-reference-heading" aria-expanded={groupOpen(group)} aria-controls={`${menuId}-${group.key}`}
            onClick={() => { setExpanded(previous => ({ ...previous, [group.key]: !groupOpen(group) })); setActiveIndex(0) }}>
            <span>{group.label}<small>{group.loading ? '…' : group.items.length}</small></span><DownOutlined className={groupOpen(group) ? 'is-expanded' : ''} />
          </button>
          <StudioCanvasCollapse id={`${menuId}-${group.key}`} open={groupOpen(group)}>
            {group.items.length ? <StudioCanvasDropdownMenu items={group.items} activeKey={activeChoice?.key} onSelect={select} ariaLabel={`选择${group.label}`} /> : <p role="status">{group.empty}</p>}
            {group.key === 'models' && (imageModels.error || videoModels.error) ? <button type="button" className="canvas-assistant-skill-command__retry" onClick={() => { imageModels.reload(); videoModels.reload() }}>模型读取失败，重新读取</button> : null}
          </StudioCanvasCollapse>
        </section>)}
        {moduleCode === 'UNIVERSAL' ? <div className="canvas-assistant-reference-skills">
          <div role="group" aria-label="Skill 来源">{(['OFFICIAL', 'OWNED'] as const).map(scope => <button key={scope} type="button" aria-pressed={skillScope === scope} onClick={() => { setSkillScope(scope); setExpanded(previous => ({ ...previous, skills: true })); setActiveIndex(0) }}>{scope === 'OFFICIAL' ? '官方 Skill' : '我的 Skill'}</button>)}</div>
          {skillCommands?.hasMore ? <button type="button" disabled={skillCommands.loading} onClick={() => { setExpanded(previous => ({ ...previous, skills: true })); void skillCommands.loadMore() }}>{skillCommands.loading ? '正在读取…' : '加载更多 Skill'}</button> : null}
          {skillCommands?.error ? <StudioCanvasTooltip title={skillCommands.error}><button type="button" onClick={() => { void skillCommands.reload() }}>重新读取 Skill</button></StudioCanvasTooltip> : null}
        </div> : null}
      </div>}>
      <span className="canvas-assistant-mention-anchor" aria-hidden />
    </StudioCanvasPopover>
    <textarea ref={input} id={id} value={value} disabled={disabled} placeholder={placeholder} maxLength={100000} rows={2} className="assistant-field"
      aria-haspopup="menu" aria-controls={commandOpen ? menuId : undefined}
      onFocus={event => { setFocused(true); updateCommand(event.target) }}
      onBlur={() => setFocused(false)}
      onChange={event => { onChange(event.target.value); updateCommand(event.target) }}
      onSelect={event => { if (document.activeElement === event.currentTarget) updateCommand(event.currentTarget) }}
      onKeyDown={event => {
        if (event.nativeEvent.isComposing) return
        if (currentCommand && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); dismiss(); return }
        if (currentCommand && ['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); if (usable.length) setActiveIndex(index => (index + (event.key === 'ArrowUp' ? -1 : 1) + usable.length) % usable.length); else menu.current?.querySelector<HTMLButtonElement>('.canvas-assistant-reference-heading')?.focus(); return }
        if (currentCommand && event.key === 'Tab') { dismiss(); return }
        if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); if (!currentCommand) onSubmit(); else if (activeChoice) select(activeChoice.key) }
      }} />
    {typewriterActive ? <span className="canvas-agent-composer__typewriter" aria-hidden="true">{typewriterText}<i /></span> : null}
  </div>
}
