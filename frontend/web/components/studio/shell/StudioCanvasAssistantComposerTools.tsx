'use client'

import { useState, type ReactNode } from 'react'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import type { AssistantModule, AssistantMode } from '@/types/assistant-api'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { StudioCanvasSkillPicker } from './StudioCanvasSkillPicker'
import type { useCanvasSkillCatalog } from '@/hooks/studio/useCanvasSkillCatalog'
import '../composer/studio-composer.css'

export function StudioCanvasAssistantComposerTools({ moduleCode, mode, onModuleChange, executionPreference, onExecutionChange, selectedSkillCode, onSkillChange, skillCatalog, defaultModelCode, disabled = false, skillLocked = false, skillPickerOpen, onSkillPickerOpenChange, modelControl, modePending = false, modeError = '', onModeRetry }: {
  modelControl?: ReactNode
  modePending?: boolean; modeError?: string; onModeRetry?: () => void
  moduleCode: AssistantModule; mode: AssistantMode
  onModuleChange: (moduleCode: Extract<AssistantModule, 'UNIVERSAL' | 'SCREENWRITER'>, mode: AssistantMode) => void
  executionPreference: 'MANUAL' | 'AUTO'; onExecutionChange: (value: 'MANUAL' | 'AUTO') => void
  selectedSkillCode: string; onSkillChange: (code: string) => void; skillCatalog: ReturnType<typeof useCanvasSkillCatalog>
  defaultModelCode: string
  disabled?: boolean
  skillLocked?: boolean
  skillPickerOpen?: boolean
  onSkillPickerOpenChange?: (open: boolean) => void
}) {
  const [moduleOpen, setModuleOpen] = useState(false)
  const [executionOpen, setExecutionOpen] = useState(false)
  const moduleKey = moduleCode === 'UNIVERSAL' ? 'UNIVERSAL' : mode
  const moduleLabel = moduleCode === 'UNIVERSAL' ? '全能助手' : mode === 'CRAFT' ? '原创编剧' : '编辑 / 改编'
  const auto = executionPreference === 'AUTO'
  return <div className="canvas-agent-composer__tools">
    <StudioCanvasDropdownPopover items={[
      { key: 'UNIVERSAL', label: '全能助手', icon: <StudioIconGlyph name="assistantUniversal" /> },
      { key: 'CRAFT', label: '原创编剧', icon: <StudioIconGlyph name="assistantCraft" /> },
      { key: 'ADAPT', label: '编辑 / 改编', icon: <StudioIconGlyph name="assistantAdapt" /> }
    ]} activeKey={moduleKey} onSelect={(key) => { if (!disabled) onModuleChange(key === 'UNIVERSAL' ? 'UNIVERSAL' : 'SCREENWRITER', key === 'ADAPT' ? 'ADAPT' : 'CRAFT') }}
      open={moduleOpen} onOpenChange={setModuleOpen} placement="topLeft" ariaLabel="选择助手" getPopupContainer={getStudioComposerPopupContainer}
      trigger={<StudioCanvasTooltip open={moduleOpen ? false : undefined} title={disabled ? '正在处理，请稍候' : moduleLabel}><StudioComposerChip disabled={disabled} label={moduleLabel} icon={<StudioIconGlyph name={moduleKey === 'UNIVERSAL' ? 'assistantUniversal' : moduleKey === 'CRAFT' ? 'assistantCraft' : 'assistantAdapt'} />} className="canvas-agent-tool-select" active={moduleOpen} aria-label={'选择助手，当前为' + moduleLabel} aria-haspopup="menu" aria-expanded={moduleOpen} /></StudioCanvasTooltip>} />
    {modelControl}
    {moduleCode === 'UNIVERSAL' ? <StudioCanvasSkillPicker executionReady open={skillPickerOpen} onOpenChange={onSkillPickerOpenChange} disabled={disabled || skillLocked} selectedCode={selectedSkillCode} defaultModelCode={defaultModelCode} catalog={skillCatalog} onSelect={onSkillChange} /> : null}
    <StudioCanvasDropdownPopover items={[
      { key: 'MANUAL', label: '手动生成', description: '生成收费内容前确认，创作问题正常询问', icon: <StudioIconGlyph name="manual" /> },
      { key: 'AUTO', label: '自动生成', description: '自动规划、纠错并连续生成，可随时停止', icon: <StudioIconGlyph name="auto" /> }
    ]} activeKey={executionPreference} onSelect={(value) => { if (!disabled) onExecutionChange(value as 'MANUAL' | 'AUTO') }}
      open={executionOpen && !modePending && !modeError} onOpenChange={value => { if (modeError) { onModeRetry?.(); return } if (!modePending) setExecutionOpen(value) }} placement="topLeft" ariaLabel="选择生成模式" getPopupContainer={getStudioComposerPopupContainer}
      trigger={<StudioCanvasTooltip open={executionOpen ? false : undefined} title={modePending ? '正在核实生成模式' : modeError || (auto ? '自动生成' : '手动生成')}><StudioComposerChip disabled={disabled || modePending} label={modePending ? '同步生成模式' : modeError ? '核对生成模式' : auto ? '自动生成' : '手动生成'} icon={auto ? <StudioIconGlyph name="auto" /> : <StudioIconGlyph name="manual" />} arrow={false} className="canvas-agent-tool-select is-icon-only" active={executionOpen} aria-label={modePending ? '正在核实生成模式' : modeError ? '重新读取生成模式' : '选择生成模式，当前为' + (auto ? '自动生成' : '手动生成')} aria-haspopup="menu" aria-expanded={executionOpen && !modePending && !modeError} /></StudioCanvasTooltip>} />
  </div>
}
