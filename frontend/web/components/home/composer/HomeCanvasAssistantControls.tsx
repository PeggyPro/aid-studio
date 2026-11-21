'use client'

import { CloseOutlined, PaperClipOutlined } from '@ant-design/icons'
import '@/components/studio/theme/studio-free-themes.css'
import { StudioCanvasAssistantAttachmentPicker } from '@/components/studio/shell/StudioCanvasAssistantAttachmentPicker'
import { StudioCanvasAssistantComposerTools } from '@/components/studio/shell/StudioCanvasAssistantComposerTools'
import { StudioCanvasAssistantChoices, StudioCanvasAssistantModels } from '@/components/studio/shell/StudioCanvasAssistantModels'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { useAssistantSkillDirectory } from '@/hooks/studio/useAssistantSkillDirectory'
import type { CanvasAssistantAttachment } from '@/utils/canvas/canvasAssistantAttachment'
import type { CanvasHomeAssistantChoices } from '@/utils/canvas/canvasHomeAssistantDraft'

const NEW_CANVAS_ASSETS = { projectId: 'new-canvas', projectName: '新建画布', assets: [] }

export interface HomeCanvasAssistantControlsProps {
  choices: CanvasHomeAssistantChoices
  skillName: string
  attachments: CanvasAssistantAttachment[]
  disabled: boolean
  loggedIn: boolean
  onChoicesChange: (changes: Partial<CanvasHomeAssistantChoices>) => void
  onSkillChange: (code: string, name: string) => void
  onAdd: (items: CanvasAssistantAttachment[]) => boolean
  onRemove: (index: number) => void
}

export function HomeCanvasAssistantControls({ choices, disabled, loggedIn, onChoicesChange, onSkillChange, onAdd }: HomeCanvasAssistantControlsProps) {
  const skillCatalog = useAssistantSkillDirectory()
  return <div className="home-canvas-assistant-tools">
    <StudioCanvasAssistantAttachmentPicker disabled={disabled || !loggedIn} currentAssets={NEW_CANVAS_ASSETS} onAdd={onAdd} />
    <StudioCanvasAssistantComposerTools
      disabled={disabled}
      skillLocked={!loggedIn}
      moduleCode={choices.moduleCode}
      mode={choices.mode}
      onModuleChange={(moduleCode, mode) => onChoicesChange({ moduleCode, mode })}
      executionPreference={choices.executionPreference}
      onExecutionChange={executionPreference => onChoicesChange({ executionPreference })}
      selectedSkillCode={choices.selectedSkillCode}
      onSkillChange={code => onSkillChange(code, skillCatalog.items.find(item => item.code === code)?.name || code)}
      skillCatalog={skillCatalog}
      defaultModelCode=""
      modelControl={choices.moduleCode === 'UNIVERSAL' ? <StudioCanvasAssistantModels selected={choices.selectedModels} disabled={disabled || !loggedIn}
        onChange={selectedModels => onChoicesChange({ selectedModels })} /> : null}
    />
  </div>
}

export function HomeCanvasAssistantSelections({ choices, skillName, attachments, disabled, onChoicesChange, onSkillChange, onRemove }: HomeCanvasAssistantControlsProps) {
  const universal = choices.moduleCode === 'UNIVERSAL'
  if (!attachments.length && (!universal || (!choices.selectedModels.length && !choices.selectedSkillCode))) return null
  return <div className="home-canvas-assistant-selections">
    {attachments.length ? <ul className="canvas-assistant-choice-chips" aria-label="待导入素材">
      {attachments.map((item, index) => {
        const name = item instanceof File ? item.name : item.name || '素材'
        return <li key={index} title={name}><PaperClipOutlined aria-hidden /><span>{name}</span>
          <StudioCanvasTooltip title="移除素材"><button type="button" disabled={disabled} aria-label={`移除素材：${name}`} onClick={() => onRemove(index)}><CloseOutlined aria-hidden /></button></StudioCanvasTooltip>
        </li>
      })}
    </ul> : null}
    {universal ? <StudioCanvasAssistantChoices models={choices.selectedModels} disabled={disabled}
      skill={choices.selectedSkillCode ? { code: choices.selectedSkillCode, name: skillName || choices.selectedSkillCode } : undefined}
      onRemoveModel={type => onChoicesChange({ selectedModels: choices.selectedModels.filter(model => model.modelType !== type) })}
      onRemoveSkill={() => onSkillChange('', '')} /> : null}
  </div>
}
