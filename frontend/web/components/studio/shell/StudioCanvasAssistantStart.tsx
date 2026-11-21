'use client'

import { ArrowRightOutlined } from '@ant-design/icons'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import type { ReactNode } from 'react'
import type { AssistantMode, AssistantModule } from '@/types/assistant-api'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioCanvasEmptyOrbAnchor } from './StudioCanvasEmptyOrb'
import './studio-canvas-assistant-start.css'

type StartAction = {
  id: string
  title: string
  description: string
  icon: ReactNode
} & ({ kind: 'prompt'; prompt: string } | { kind: 'skill' | 'upload' | 'library' })

const STARTS: Record<'UNIVERSAL' | AssistantMode, { label: string; title: string; description: string; actions: StartAction[] }> = {
  UNIVERSAL: {
    label: '全能助手', title: '从一个想法开始', description: '聊聊你的创意，或带上素材一起探索。',
    actions: [
      { id: 'skill', kind: 'skill', title: '选择 Skill 开始创作', description: '用已有的创作方法辅助你的想法', icon: <StudioIconGlyph name="skill" /> },
      { id: 'idea', kind: 'prompt', title: '帮我梳理想法', description: '理清目标，找到下一步创作方向', icon: <StudioIconGlyph name="idea" />, prompt: '请帮我梳理下面的创作想法，先讨论目标和方向，需要补充的信息请向我提问。\n\n我的想法：' },
      { id: 'upload', kind: 'upload', title: '添加参考素材', description: '上传文本、图片或视频，带入对话', icon: <StudioIconGlyph name="upload" /> },
      { id: 'summary', kind: 'prompt', title: '整理原文要点', description: '提炼故事、人物与值得保留的内容', icon: <StudioIconGlyph name="summary" />, prompt: '请整理我提供或引用的原文，提炼故事要点、人物关系和关键信息，先给出文字分析。\n\n补充说明或原文：' }
    ]
  },
  CRAFT: {
    label: '原创编剧', title: '写下故事的第一幕', description: '从灵感到大纲，一起把故事写清楚。',
    actions: [
      { id: 'idea', kind: 'prompt', title: '从一句话构思故事', description: '展开主角、目标与核心冲突', icon: <StudioIconGlyph name="idea" />, prompt: '请根据下面的一句话构思原创故事，先讨论主角、目标和核心冲突，需要补充的信息请向我提问。\n\n故事想法：' },
      { id: 'outline', kind: 'prompt', title: '梳理故事大纲', description: '串起开场、转折与结局', icon: <StudioIconGlyph name="outline" />, prompt: '请帮我创作故事大纲，梳理开场、主要转折和结局，先确认必要的故事要求。\n\n故事想法与要求：' },
      { id: 'characters', kind: 'prompt', title: '设计人物与世界观', description: '明确人物动机、关系与故事规则', icon: <StudioIconGlyph name="characters" />, prompt: '请帮我设计原创故事的人物与世界观，重点讨论人物动机、人物关系和故事规则。\n\n故事背景与想法：' },
      { id: 'scene', kind: 'prompt', title: '创作一场戏', description: '用场景、行动与对白呈现故事', icon: <StudioIconGlyph name="scene" />, prompt: '请根据下面的要求创作一场原创剧本场景，写清场景、人物行动和对白，必要时先向我提问。\n\n场景想法与要求：' }
    ]
  },
  ADAPT: {
    label: '编辑 / 改编', title: '让故事有新的表达', description: '带上原文，保留你喜欢的部分，再一起打磨。',
    actions: [
      { id: 'upload', kind: 'upload', title: '导入故事原文', description: '支持 TXT、MD、DOCX 与 PDF', icon: <StudioIconGlyph name="upload" /> },
      { id: 'library', kind: 'library', title: '从素材库选择原文', description: '引用当前画布或已有的故事素材', icon: <StudioIconGlyph name="library" /> },
      { id: 'polish', kind: 'prompt', title: '润色文字与对白', description: '保留原意，调整表达与人物语气', icon: <StudioIconGlyph name="polish" />, prompt: '请润色我提供或引用的原文与对白，保留原意、人物设定和情节事实，必要时先确认语气要求。\n\n润色要求或原文：' },
      { id: 'rewrite', kind: 'prompt', title: '调整篇幅与表达', description: '按你的要求精简、扩写或改写', icon: <StudioIconGlyph name="rewrite" />, prompt: '请按我的要求精简、扩写或改写提供或引用的原文，先确认目标篇幅与需要保留的内容。\n\n修改要求或原文：' }
    ]
  }
}

export function StudioCanvasAssistantStart({ moduleCode, mode, onPrompt, onSkill, onUpload, onLibrary, disabledReason, attachmentDisabledReason, skillDisabledReason }: {
  moduleCode: AssistantModule; mode: AssistantMode
  onPrompt: (prompt: string) => void; onSkill: () => void; onUpload: () => void; onLibrary: () => void
  disabledReason?: string; attachmentDisabledReason?: string; skillDisabledReason?: string
}) {
  const key = moduleCode === 'UNIVERSAL' ? moduleCode : mode
  const start = STARTS[key]
  return <section className="canvas-assistant-start" aria-label={`${start.label}创作入口`}>
    <div className="canvas-assistant-start__intro">
      <StudioCanvasEmptyOrbAnchor />
      <span className="canvas-assistant-start__mode">{start.label}</span>
      <h2>{start.title}</h2>
      <p>{start.description}</p>
    </div>
    <div className="canvas-assistant-start__actions" aria-label="快捷操作">
      {start.actions.map((action) => {
        const reason = disabledReason || (action.kind === 'skill' ? skillDisabledReason : action.kind === 'upload' || action.kind === 'library' ? attachmentDisabledReason : undefined)
        const activate = () => {
          if (reason) return
          if (action.kind === 'prompt') onPrompt(action.prompt)
          else if (action.kind === 'skill') onSkill()
          else if (action.kind === 'upload') onUpload()
          else onLibrary()
        }
        return <StudioCanvasTooltip key={action.id} title={reason || `${action.description}${action.kind === 'prompt' ? '；填入要求，编辑后发送' : ''}`}>
          <button type="button" className="canvas-assistant-start__action" disabled={Boolean(reason)} onClick={activate}>
            <span className="canvas-assistant-start__icon" aria-hidden>{action.icon}</span>
            <span className="canvas-assistant-start__copy">{action.title}</span>
            <ArrowRightOutlined className="canvas-assistant-start__arrow" aria-hidden />
          </button>
        </StudioCanvasTooltip>
      })}
    </div>
  </section>
}
