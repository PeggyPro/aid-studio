'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button, message, Modal } from 'antd'
import {
  CloseOutlined,
  CopyOutlined,
  EditOutlined,
  HighlightOutlined
} from '@ant-design/icons'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { StudioCanvasScriptAgentPanel } from './StudioCanvasScriptAgentPanel'
import StoryScriptAgentPanel from '@/components/steps/story-script-agent/StoryScriptAgentPanel'
import { useStoryScriptAgent } from '@/hooks/useStoryScriptAgent'
import { useCreationStore } from '@/stores/creation'
import { useStudioStoryScriptAgent } from '@/components/studio/StudioStoryScriptAgentProvider'
import { useStudioScriptNodeSave } from '@/hooks/studio/useStudioScriptNodeSave'
import type { StudioFlowBinding } from '@/types/studio'
import type { EditorTextSelection } from '@/utils/quill/editorTextSelection'
import {
  htmlToPlainText,
  htmlPureTextCharCount,
  isHtmlContentEmpty,
  scriptApiTextToEditorHtml,
  STORY_SCRIPT_MAX_CHARS_MOVIE,
  STORY_SCRIPT_MAX_CHARS_SERIES
} from '@/utils/htmlPlain'
import {
  editorTextSelectionKey,
  normalizeStoryScriptAgentReferences,
  parseStoryScriptAgentReplacements,
  storyScriptAgentReferencesOverlap
} from '@/utils/storyScriptAgentReference'
import { formatEditorSelectionLocation } from '@/utils/quill/editorTextSelection'
import {
  applyPlainTextReplacements,
  readPlainTextSelectionFromElement
} from '@/utils/studio/plainTextSelection'
import { resolveStudioScriptScopeFromBinding } from '@/utils/studio/studioScriptNode'
import { copyStudioScriptText } from './StudioScriptNodePreview'
import '@/components/steps/story-script-agent/story-script-agent.css'

type WorkspaceMode = 'view' | 'edit'

export function StudioScriptNodeWorkspaceModal({
  open,
  nodeId,
  title,
  initialContent,
  flowBinding,
  onClose
}: {
  open: boolean
  nodeId: string
  title: string
  initialContent: string
  flowBinding?: StudioFlowBinding
  onClose: () => void
}) {
  const [mounted, setMounted] = useState(false)
  const [mode, setMode] = useState<WorkspaceMode>('view')
  const [baseline, setBaseline] = useState(initialContent)
  const [draft, setDraft] = useState(initialContent)
  const [references, setReferences] = useState<EditorTextSelection[]>([])
  const [selectionHint, setSelectionHint] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const scriptPaneRef = useRef<HTMLDivElement>(null)
  const openedNode = useRef<string | null>(null)
  const lastExternalContent = useRef(initialContent)
  const [externalConflict, setExternalConflict] = useState(false)
  const canvasRuntime = useCanvasProjectRuntime()
  const saveScript = useStudioScriptNodeSave(nodeId, flowBinding)

  const globalSetting = useCreationStore((state) => state.formData.globalSetting)
  const workTitle = useCreationStore((state) => state.workTitle)
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const currentProjectType = useCreationStore((state) => state.currentProjectType)

  const bindingScope = useMemo(
    () => resolveStudioScriptScopeFromBinding(flowBinding),
    [flowBinding]
  )
  const projectId = bindingScope.projectId ?? (currentProjectId && currentProjectId > 0 ? currentProjectId : null)
  const episodeId =
    bindingScope.episodeId ??
    (currentProjectType === 'movie' ? 0 : currentEpisodeId ?? null)
  const sharedAgentScope = useStudioStoryScriptAgent()
  const matchingSharedAgentScope =
    sharedAgentScope &&
    sharedAgentScope.projectId === projectId &&
    sharedAgentScope.episodeId === episodeId
      ? sharedAgentScope
      : null

  const projectStyle = useMemo(() => {
    const selectedName = String(globalSetting.selectedStyle?.name || '').trim()
    return [selectedName || globalSetting.style, globalSetting.selectedStyle?.promptText]
      .map((value) => String(value || '').trim())
      .filter(Boolean)
      .join('\n')
      .slice(0, 2000)
  }, [globalSetting.selectedStyle?.name, globalSetting.selectedStyle?.promptText, globalSetting.style])

  const localAgent = useStoryScriptAgent({
    projectId,
    episodeId,
    projectTitle: workTitle || globalSetting.title || title || '未命名作品',
    projectStyle,
    enabled: matchingSharedAgentScope == null && !(canvasRuntime?.project && !flowBinding)
  })
  const agent = matchingSharedAgentScope?.agent ?? localAgent

  const dirty = draft !== baseline
  const agentReady = Boolean(projectId)

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    if (!open) { openedNode.current = null; return }
    if (openedNode.current !== nodeId) {
      openedNode.current = nodeId; lastExternalContent.current = initialContent
      setBaseline(initialContent); setDraft(initialContent); setMode('view')
      setReferences([]); setSelectionHint(null); setExternalConflict(false)
      return
    }
    if (lastExternalContent.current === initialContent) return
    lastExternalContent.current = initialContent
    if (initialContent === draft || initialContent === draft.trim()) return
    if (draft !== baseline) { setExternalConflict(true); return }
    setBaseline(initialContent); setDraft(initialContent)
    setReferences([]); setSelectionHint(null); setExternalConflict(false)
  }, [initialContent, open, nodeId, baseline, draft])

  const requestClose = useCallback(() => {
    if (dirty) {
      Modal.confirm({
        title: '放弃未保存的修改？',
        content: '当前剧本有未保存改动，关闭后将丢失。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        className: 'create-flow-modal',
        onOk: onClose
      })
      return
    }
    onClose()
  }, [dirty, onClose])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') requestClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, requestClose])

  useEffect(() => {
    if (!open) return
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [open])

  const addReferenceFromSelection = useCallback(() => {
    const root = scriptPaneRef.current
    if (!root) return
    const selection = readPlainTextSelectionFromElement(root, draft)
    if (!selection) {
      message.info('请先在左侧选中要批注的剧本段落')
      return
    }
    if (references.some((item) => editorTextSelectionKey(item) === editorTextSelectionKey(selection))) {
      message.info('该选段已在批注列表中')
      return
    }
    if (references.some((item) => storyScriptAgentReferencesOverlap(item, selection))) {
      message.warning('该选段与已有批注重叠，请重新选择')
      return
    }
    setReferences((current) => normalizeStoryScriptAgentReferences([...current, selection]))
    setSelectionHint(formatEditorSelectionLocation(selection))
    window.getSelection()?.removeAllRanges()
  }, [draft, references])

  const applyAgentScript = useCallback(
    (raw: string, agentReferences?: EditorTextSelection[]) => {
      const normalizedReferences = normalizeStoryScriptAgentReferences(agentReferences ?? references)
      if (normalizedReferences.length) {
        let replacements
        if (normalizedReferences.length === 1) {
          replacements = [
            {
              selection: normalizedReferences[0],
              replacement: htmlToPlainText(scriptApiTextToEditorHtml(raw))
            }
          ]
        } else {
          const parsed = parseStoryScriptAgentReplacements(raw, normalizedReferences.length)
          if (!parsed) {
            message.warning('Agent 未返回完整的多批注修改结果，请补充要求后重新生成')
            return
          }
          replacements = parsed.map((item) => ({
            selection: normalizedReferences[item.referenceIndex],
            replacement: htmlToPlainText(scriptApiTextToEditorHtml(item.replacement))
          }))
        }
        const applySelections = () => {
          const { result, text } = applyPlainTextReplacements(draft, replacements)
          if (result === 'applied') {
            setDraft(text)
            setMode('edit')
            setReferences([])
            setSelectionHint(null)
            message.success(
              normalizedReferences.length > 1
                ? `已应用 ${normalizedReferences.length} 个批注到草稿`
                : '已替换所选段落'
            )
            return
          }
          if (result === 'stale') {
            message.warning('原选段已发生变化，请重新选择后再带入')
            return
          }
          message.warning('Agent 暂未返回可替换内容')
        }
        Modal.confirm({
          title:
            normalizedReferences.length > 1
              ? `应用 ${normalizedReferences.length} 个批注？`
              : '替换选中的剧本段落？',
          content: '确认后将更新左侧草稿，记得点击「保存」同步到节点。',
          okText: '确认带入',
          cancelText: '取消',
          className: 'create-flow-modal',
          onOk: applySelections
        })
        return
      }

      const nextHtml = scriptApiTextToEditorHtml(raw)
      if (!nextHtml) {
        message.warning('Agent 文档暂无可带入内容')
        return
      }
      const maxLength =
        currentProjectType === 'series'
          ? STORY_SCRIPT_MAX_CHARS_SERIES
          : STORY_SCRIPT_MAX_CHARS_MOVIE
      if (htmlPureTextCharCount(nextHtml) > maxLength) {
        message.warning(`生成内容超过当前剧本 ${maxLength.toLocaleString('zh-CN')} 字上限，请先精简`)
        return
      }
      const nextText = htmlToPlainText(nextHtml)
      const apply = () => {
        setDraft(nextText)
        setMode('edit')
        setReferences([])
        setSelectionHint(null)
        message.success('已带入草稿，确认无误后点击「保存」')
      }
      if (isHtmlContentEmpty(draft)) {
        apply()
        return
      }
      Modal.confirm({
        title: '替换当前草稿？',
        content: '带入 Agent 文档会覆盖左侧草稿内容；保存后才会写入节点。',
        okText: '确认带入',
        cancelText: '取消',
        className: 'create-flow-modal',
        onOk: apply
      })
    },
    [currentProjectType, draft, references]
  )

  const handleSave = useCallback(async () => {
    if (!dirty || saving || externalConflict) return
    setSaving(true)
    try {
      const ok = await saveScript(draft)
      if (!ok) return
      setBaseline(draft.trim())
      setDraft(draft.trim())
      setMode('view')
    } finally {
      setSaving(false)
    }
  }, [dirty, draft, externalConflict, saveScript, saving])

  if (!open || !mounted) return null

  return createPortal(
    <div className="studio-script-workspace" role="dialog" aria-modal="true" aria-label={`${title} · 剧本工作区`}>
      <div className="studio-script-workspace__toolbar">
        {mode === 'view' ? (
          <>
            <Button
              type="text"
              icon={<CopyOutlined />}
              aria-label="复制剧本"
              onClick={() => void copyStudioScriptText(draft, title)}
            />
            <Button
              type="text"
              icon={<EditOutlined />}
              aria-label="编辑剧本"
              onClick={() => setMode('edit')}
            />
            <Button type="text" icon={<CloseOutlined />} aria-label="关闭" onClick={requestClose} />
          </>
        ) : (
          <>
            <Button
              type="text"
              onClick={() => {
                setDraft(baseline)
                setMode('view')
                setReferences([])
                setSelectionHint(null)
              }}
            >
              取消编辑
            </Button>
            <Button type="primary" disabled={!dirty || saving || externalConflict} loading={saving} onClick={() => void handleSave()}>
              保存
            </Button>
          </>
        )}
      </div>

      <div className="studio-script-workspace__body">
        <section className="studio-script-workspace__script-pane">
          <header className="studio-script-workspace__script-head">
            <strong>{title}</strong>
            {mode === 'view' ? (
              <button type="button" className="studio-script-workspace__annotate" onClick={addReferenceFromSelection}>
                <HighlightOutlined />
                选段批注
              </button>
            ) : (
              <span className="studio-script-workspace__edit-hint">可直接修改正文，保存后写入节点</span>
            )}
          </header>
          {externalConflict ? <div role="alert" className="studio-script-workspace__selection-hint">
            <p>节点正文已更新。你的未保存草稿已保留，请核对后处理冲突。</p>
            <Button size="small" onClick={() => Modal.confirm({ title: '载入最新节点正文？', content: '当前未保存草稿将被替换，请先复制需要保留的内容。', okText: '载入最新正文', cancelText: '保留草稿', className: 'create-flow-modal', onOk: () => { setBaseline(initialContent); setDraft(initialContent); setExternalConflict(false); setReferences([]); setSelectionHint(null); setMode('view') } })}>载入最新正文</Button>
          </div> : null}
          {selectionHint ? (
            <p className="studio-script-workspace__selection-hint">已选：{selectionHint}</p>
          ) : null}
          {references.length ? (
            <div className="studio-script-workspace__reference-chips">
              {references.map((reference, index) => (
                <button
                  key={editorTextSelectionKey(reference)}
                  type="button"
                  onClick={() =>
                    setReferences((current) => current.filter((item) => item !== reference))
                  }
                >
                  批注 {index + 1} · {formatEditorSelectionLocation(reference)}
                </button>
              ))}
            </div>
          ) : null}
          {mode === 'view' ? (
            <div ref={scriptPaneRef} className="studio-script-workspace__script-view">
              {draft}
            </div>
          ) : (
            <textarea
              className="studio-script-workspace__script-editor"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              spellCheck={false}
            />
          )}
        </section>

        <section className="studio-script-workspace__agent-pane">
          {canvasRuntime?.project && !flowBinding ? (
            <StudioCanvasScriptAgentPanel key={nodeId} nodeId={nodeId} title={title} draft={draft} baseline={baseline}
              references={references} draftBlocked={dirty || saving || externalConflict} onTargetLocated={requestClose} />
          ) : agentReady ? (
            <StoryScriptAgentPanel
              runtimeFeedbackEnabled
              open
              onClose={() => undefined}
              skills={agent.skills}
              selectedSkillCode={agent.selectedSkillCode}
              onSkillChange={agent.selectSkill}
              selectedModelCode={agent.selectedModelCode}
              onModelChange={agent.selectModel}
              skillsLoading={agent.skillsLoading}
              skillsError={agent.skillsError}
              onSkillsRequest={() => { void agent.loadSkills() }}
              messages={agent.messages}
              conversationScopeKey={`script-node:${nodeId}:${agent.selectedSkillCode || 'default'}`}
              sending={agent.sending}
              paused={agent.paused}
              statusText={agent.statusText}
              lastError={agent.lastError}
              canRetry={agent.canRetry}
              canStop={agent.canStop}
              stopping={agent.stopping}
              onRetry={agent.retry}
              onStop={() => void agent.stop()}
              onResumeReceiving={agent.resumeReceiving}
              onSend={agent.send}
              onSubmitInputRequest={agent.submitInputRequest}
              references={references}
              onReferencesChange={setReferences}
              onApplyScript={applyAgentScript}
              emptyHint="描述你想如何修改剧本，或在左侧选段后在此提出批注要求。"
            />
          ) : (
            <div className="studio-script-workspace__agent-empty">
              <strong>Agent 暂不可用</strong>
              <p>当前为本地剧本节点。关联项目后，可在右侧使用 Agent 修改、选段批注与重新生成。</p>
              <p>你仍可在左侧直接编辑并保存到节点。</p>
            </div>
          )}
        </section>
      </div>
    </div>,
    document.body
  )
}
