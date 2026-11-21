'use client'

import { useEffect, useRef, useState } from 'react'
import { ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined } from '@ant-design/icons'
import type { AssistantInputRequest } from '@/types/assistant-api'
import type { AssistantSessionStore } from '@/utils/assistant/assistantSessionStore'
import { useAssistantQuestionDrafts } from '@/hooks/assistant/useAssistantQuestionDrafts'
import { assistantAnswerComplete, assistantAnswerRequestsPause, assistantAnswerValue, assistantQuestionEditable, emptyAssistantAnswer, toggleAssistantOption } from '@/utils/assistant/assistantQuestions'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioCanvasAssistantAction, StudioCanvasAssistantTurn } from './StudioCanvasAssistantTurn'

/** 对话只呈现当前问题；草稿、冲突、暂停和完整提交仍由统一会话控制器负责。 */
export function StudioCanvasAssistantQuestion({ input, store, busy }: { input: AssistantInputRequest; store: AssistantSessionStore; busy: boolean }) {
  const draft = useAssistantQuestionDrafts(input, store)
  const heading = useRef<HTMLHeadingElement>(null)
  const [expandedQuestion, setExpandedQuestion] = useState('')
  const index = Math.max(0, input.questions.findIndex(item => item.id === draft.cursor))
  const question = input.questions[index]
  const answer = question ? draft.answers[question.id] || emptyAssistantAnswer(question.id) : null
  const requestsPause = Boolean(question && answer && assistantAnswerRequestsPause(question, answer))
  const editable = draft.writable && assistantQuestionEditable(input) && !busy && !draft.acting && !draft.suspendPending
  const retryingPause = requestsPause && draft.suspendPending
  const pauseBlocked = retryingPause
    ? busy || draft.acting || draft.saving || !!draft.conflicts.length || !draft.writable || !draft.suspendRetryReady
    : !editable || draft.saving || !!draft.conflicts.length || !!store.getSnapshot().pendingCommand
  const suspended = input.status === 'SUSPENDED'
  const complete = (id: string) => {
    const item = input.questions.find(row => row.id === id)!
    return assistantAnswerComplete(item, draft.answers[id] || emptyAssistantAnswer(id))
  }
  const remaining = input.questions.find(item => item.required && !complete(item.id))
  const next = input.questions.slice(index + 1).find(item => !complete(item.id))
  const blocked = requestsPause ? pauseBlocked : !editable || draft.saving || !!draft.conflicts.length || !!(question?.required && answer && !assistantAnswerComplete(question, answer))
  const reason = draft.conflicts.length ? '请先处理答案冲突' : draft.saving ? '正在保存答案' : !editable ? '当前问题暂不可编辑' : blocked ? '请先完成本题必答内容' : undefined
  const advance = () => {
    if (blocked) return
    if (requestsPause) { void (retryingPause ? draft.retrySuspend() : draft.suspend()); return }
    if (next) void draft.move(next.id)
    else if (remaining) void draft.move(remaining.id)
    else void draft.submit()
  }
  useEffect(() => { heading.current?.focus({ preventScroll: true }) }, [draft.cursor])
  if (!question || !answer) return <p role="status">问题暂不可读取，请刷新会话。</p>
  const choice = Boolean(question.options?.length)
  const freeTextVisible = !choice || Boolean(answer.freeText) || expandedQuestion === question.id
  return <StudioCanvasAssistantTurn label="补充创作信息" className="canvas-assistant-question">
    <div className="canvas-assistant-question__meta"><span>先确定一点创作细节</span><span>{index + 1} / {input.questions.length}</span></div>
    <div className="canvas-assistant-question__progress" aria-label={`第 ${index + 1} 题，共 ${input.questions.length} 题`}>{input.questions.map(item => <span key={item.id} className={item.id === question.id ? 'is-current' : complete(item.id) ? 'is-done' : ''} />)}</div>
    <h3 ref={heading} tabIndex={-1}>{question.title}</h3>
    {question.description ? <p className="canvas-assistant-muted">{question.description}</p> : null}
    <fieldset disabled={!editable} className="canvas-assistant-question__answers"><legend className="sr-only">{question.title}</legend>
      {question.options?.map((option, optionIndex) => <label key={option.id} className={`canvas-assistant-choice${answer.selectedOptionIds.includes(option.id) ? ' is-selected' : ''}`}>
        <input type={question.kind === 'multi' ? 'checkbox' : 'radio'} name={`canvas-${input.requestId}-${question.id}`} checked={answer.selectedOptionIds.includes(option.id)} onChange={() => draft.edit(toggleAssistantOption(question, answer, option.id))} />
        <span className="canvas-assistant-choice__key" aria-hidden>{answer.selectedOptionIds.includes(option.id) ? <CheckOutlined /> : String.fromCharCode(65 + optionIndex)}</span>
        <span className="canvas-assistant-choice__text">{option.label}{option.description ? <small>{option.description}</small> : null}</span>
      </label>)}
      {question.allowFreeText || ['text', 'textarea', 'number'].includes(question.kind) ? freeTextVisible ? <label className="canvas-assistant-answer-text"><span>{choice ? '补充你的想法' : '你的回答'}{question.required && !choice ? ' · 必答' : ''}</span>
        {question.kind === 'number' ? <input type="number" value={answer.freeText} min={question.min} max={question.max} onChange={event => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))} />
          : <textarea rows={3} value={answer.freeText} maxLength={question.maxLength} placeholder={choice ? '也可以用自己的话描述…' : '写下你的想法…'} onChange={event => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))} />}
      </label> : <button type="button" className="canvas-assistant-text-link" onClick={() => setExpandedQuestion(question.id)}>输入其他想法</button> : null}
    </fieldset>
    {draft.conflicts.includes(question.id) ? <div className="canvas-assistant-feedback" role="alert"><p>答案已在其他页面更新，本地内容已保留。</p><p>已保存的答案：{draft.serverAnswers[question.id]?.freeText || question.options?.filter(option => draft.serverAnswers[question.id]?.selectedOptionIds.includes(option.id)).map(option => option.label).join('、') || '未回答'}</p><div className="canvas-assistant-actions"><StudioCanvasAssistantAction onClick={() => draft.resolveConflict(question.id, false)}>使用已保存答案</StudioCanvasAssistantAction><StudioCanvasAssistantAction onClick={() => draft.resolveConflict(question.id, true)}>保留我的答案</StudioCanvasAssistantAction></div></div> : null}
    {draft.error ? <div className="canvas-assistant-feedback" role="alert"><p>{draft.error}</p><StudioCanvasAssistantAction disabled={!editable || draft.saving || !draft.dirtyCount} onClick={() => void draft.flush()}>重试保存</StudioCanvasAssistantAction></div> : null}
    <div className="canvas-assistant-question__footer">
      <div className="canvas-assistant-question__secondary">
        {index > 0 ? <StudioCanvasTooltip title="上一题"><button type="button" aria-label="上一题" className="canvas-assistant-back" disabled={busy || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(input.questions[index - 1].id)}><ArrowLeftOutlined aria-hidden /></button></StudioCanvasTooltip> : null}
        <span role="status">{draft.suspendPending ? '暂停待同步' : draft.saving ? '保存中…' : draft.dirtyCount ? '已保留在本机' : !draft.writable ? '正在恢复草稿' : answer.revision > 0 ? '已保存' : '回答后自动保存'}{question.kind === 'multi' ? ' · 可多选' : !question.required ? ' · 可跳过' : ''}</span>
      </div>
      {suspended ? <StudioCanvasAssistantAction primary disabled={busy || !store.getSnapshot().snapshot?.activeRun?.allowedActions.includes('RESUME')} reason={busy ? '正在同步会话' : undefined} onClick={() => void store.control('resume')}>继续回答 <ArrowRightOutlined aria-hidden /></StudioCanvasAssistantAction>
        : <StudioCanvasAssistantAction primary disabled={blocked} reason={reason} onClick={advance}>{retryingPause ? '核实并重试暂停' : requestsPause ? '暂停并保留进度' : next || remaining && remaining.id !== question.id ? '下一步' : '提交答案'} {!requestsPause ? <ArrowRightOutlined aria-hidden /> : null}</StudioCanvasAssistantAction>}
    </div>
    {!suspended ? <details className="canvas-assistant-question__more"><summary>调整回答</summary><div className="canvas-assistant-actions"><StudioCanvasAssistantAction disabled={!editable} onClick={() => draft.edit(assistantAnswerValue(question, answer, { selectedOptionIds: [], freeText: '' }))}>清空本题</StudioCanvasAssistantAction>{!requestsPause ? <StudioCanvasAssistantAction disabled={busy || draft.saving || draft.acting} onClick={() => void draft.suspend()}>{draft.suspendPending ? '重试暂停' : '稍后回答'}</StudioCanvasAssistantAction> : null}</div><nav aria-label="问题导航">{input.questions.map((item, itemIndex) => <button type="button" key={item.id} aria-current={item.id === question.id ? 'step' : undefined} disabled={busy || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(item.id)}>{itemIndex + 1}. {item.title}{complete(item.id) ? ' · 已完成' : ''}</button>)}</nav></details> : null}
  </StudioCanvasAssistantTurn>
}
