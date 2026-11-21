'use client'

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { AssistantModule } from '@/types/assistant-api'
import { SKILL_CREATOR_CODE, skillAuthoringPrompt } from '@/utils/studio/canvasSkillAuthoring'
import { useStudioSkillWorkspace } from './StudioSkillWorkspaceProvider'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import './studio-skill-authoring.css'

/** Prepares the existing composer; only the user's Send starts a billable run. */
export function StudioSkillAuthoringBridge({
  moduleCode,
  ready,
  locked,
  sessionId,
  checkAvailability,
  prepareSwitch,
  onUniversal,
  onSelect,
  insertPrompt,
  currentText
}: {
  moduleCode: AssistantModule
  ready: boolean
  locked: boolean
  sessionId: string
  checkAvailability: () => Promise<boolean>
  prepareSwitch: () => Promise<boolean>
  onUniversal: () => void
  onSelect: (code: string) => void
  insertPrompt: (text: string) => void
  currentText: string
}) {
  const workspace = useStudioSkillWorkspace()
  const request = workspace?.request
  const [result, setResult] = useState<{ request: typeof request; status: 'checking' | 'unavailable' | 'failed'; error?: string } | null>(null)
  const epoch = useRef(0)
  const [retry, setRetry] = useState(0)
  // Callback identities change with the composer. Read their latest values after
  // every await without restarting preparation on typing or unrelated renders.
  const latest = useRef({ workspace, prepareSwitch, checkAvailability, onUniversal, onSelect, insertPrompt, currentText, ready, locked, sessionId, moduleCode })
  useLayoutEffect(() => {
    latest.current = { workspace, prepareSwitch, checkAvailability, onUniversal, onSelect, insertPrompt, currentText, ready, locked, sessionId, moduleCode }
  })
  useEffect(() => {
    if (!request || !ready || locked) return
    const token = ++epoch.current
    const active = () => token === epoch.current && latest.current.workspace?.request === request
      && latest.current.sessionId === sessionId && latest.current.moduleCode === moduleCode && latest.current.ready && !latest.current.locked
    setResult({ request, status: 'checking' })
    void (async () => {
      if (!(await latest.current.prepareSwitch())) {
        if (active()) setResult({ request, status: 'failed', error: '问题草稿尚未保存，请处理后重试。' })
        return
      }
      if (!active()) return
      if (moduleCode !== 'UNIVERSAL') {
        latest.current.onUniversal()
        return
      }
      // Creation must use a fresh, authorized main-Agent catalog, not a cached
      // boolean from before the entry was clicked. Never synthesize a Skill.
      const available = await latest.current.checkAvailability()
      if (!active()) return
      if (!available) {
        setResult({ request, status: 'unavailable' })
        return
      }
      const prompt = skillAuthoringPrompt(request.material)
      if (latest.current.currentText.length + prompt.length + 2 > 100000) {
        setResult({ request, status: 'failed', error: '现有输入与编制材料超过长度上限，请缩短输入后重试。' })
        return
      }
      latest.current.onSelect(SKILL_CREATOR_CODE)
      latest.current.insertPrompt(prompt)
      latest.current.workspace?.consume(request.id)
      setResult(null)
    })().catch((cause) => {
      if (active()) setResult({ request, status: 'failed', error: cause instanceof Error ? cause.message : '准备编制输入失败，请重试' })
    })
    return () => {
      if (epoch.current === token) epoch.current = token + 1
    }
  }, [
    request,
    ready,
    locked,
    moduleCode,
    retry,
    sessionId
  ])
  if (!request) return null
  const state = result?.request === request ? result : null
  const blocked = !locked && ready && (state?.status === 'unavailable' || state?.status === 'failed')
  const title = locked ? '当前创作尚未结束' : state?.status === 'unavailable' ? '编制助手暂不可用' : state?.status === 'failed' ? '编制准备未完成' : '正在准备技能编制'
  const description = locked ? '本次创作结束后继续，已有输入与导入材料会保留。'
    : state?.status === 'unavailable' ? '当前全能助手未提供此能力，请刷新重试。'
    : state?.error || '正在检查可用能力，准备好后由你发送开始。'
  return (
    <section className="canvas-skill-authoring" aria-label="技能编制准备">
      <span className="canvas-skill-authoring__icon" aria-hidden="true"><StudioIconGlyph name="skill" /></span>
      <div className="canvas-skill-authoring__copy" role="status" aria-live="polite" aria-atomic="true">
        <p className={!locked && !blocked ? 'canvas-assistant-status-shimmer' : undefined}>{title}</p>
        <small>{description}</small>
      </div>
      <div className="canvas-skill-authoring__actions">
        {blocked ? <StudioCanvasTooltip title={state?.status === 'unavailable' ? '重新检查正式能力目录；若仍不可用，请管理员核对主助手正式版本及 Skill 绑定' : '保留材料并重新准备'}>
          <button type="button" className="canvas-skill-authoring__retry" onClick={() => setRetry(value => value + 1)}><StudioIconGlyph name="reconnect" />刷新重试</button>
        </StudioCanvasTooltip> : null}
        <StudioCanvasTooltip title="取消本次编制准备，保留对话输入">
          <button type="button" className="canvas-skill-authoring__cancel" aria-label="取消编制准备" onClick={() => workspace?.consume(request.id)}><StudioIconGlyph name="close" /></button>
        </StudioCanvasTooltip>
      </div>
    </section>
  )
}
