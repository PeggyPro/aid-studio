'use client'

import { useEffect, useRef, type FocusEvent } from 'react'
import { Button } from 'antd'
import { CheckOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import type { AssistantInputRequest } from '~/types/assistant-api'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { useAssistantQuestionDrafts } from '~/hooks/assistant/useAssistantQuestionDrafts'
import { assistantAnswerComplete, assistantAnswerRequestsPause, assistantAnswerValue, assistantQuestionEditable, emptyAssistantAnswer, toggleAssistantOption } from '~/utils/assistant/assistantQuestions'

export function AssistantQuestionDeck({ input, store, busy, conversational = false, presentation, waitForAutosaveOnSubmit = false }: { input: AssistantInputRequest; store: AssistantSessionStore; busy: boolean; conversational?: boolean; presentation?: 'canvas'; waitForAutosaveOnSubmit?: boolean }) {
  const canvas = presentation === 'canvas'
  const ui = (part: string) => canvas ? `canvas-question-${part}` : `assistant-question-${part}`
  const revealAnswer = canvas ? (event: FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => event.currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest' }) : undefined
  const draft = useAssistantQuestionDrafts(input, store)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const index = Math.max(0, input.questions.findIndex((question) => question.id === draft.cursor))
  const question = input.questions[index]
  const answer = question ? draft.answers[question.id] || emptyAssistantAnswer(question.id) : null
  const requestsPause = Boolean(canvas && question && answer && assistantAnswerRequestsPause(question, answer))
  const editable = draft.writable && assistantQuestionEditable(input) && !busy && !draft.acting && !draft.suspendPending
  const retryingPause = requestsPause && draft.suspendPending
  const pauseBlocked = retryingPause
    ? busy || draft.acting || draft.saving || !!draft.conflicts.length || !draft.writable || !draft.suspendRetryReady
    : !editable || draft.saving || !!draft.conflicts.length || !!store.getSnapshot().pendingCommand
  const requiredUnanswered = input.questions.filter((item) => item.required && !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id))).length
  const unanswered = input.questions.filter((item) => !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id))).length

  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true })
    if (bodyRef.current) bodyRef.current.scrollTop = 0
  }, [draft.cursor])
  if (!question || !answer) return <p role="status">问题暂不可读取，请刷新会话。</p>

  const nextQuestionPending = input.questions.slice(index + 1).find((item) => !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id)))
    || input.questions.find((item) => item.id !== question.id && item.required && !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id)))
  const continueAnswer = () => {
    if (nextQuestionPending) void draft.move(nextQuestionPending.id)
    else void draft.submit()
  }
  if (conversational) return <section data-canvas-question-id={canvas ? question.id : undefined} className={ui('deck')} aria-label="回答创作问题" aria-busy={draft.acting || draft.saving}>
    <div className={ui('heading')}>
      {canvas ? <span className="canvas-question-eyebrow">{question.kind === 'multi' ? '可以选择多项' : question.required ? '补充创作细节' : '可选创作细节'}</span> : <h3 ref={titleRef} tabIndex={-1}>{question.title}</h3>}
      {input.questions.length > 1 ? <nav className={ui('navigation')} aria-label="问题导航">
        <Button type="text" aria-label="上一题" icon={<LeftOutlined />} disabled={busy || index === 0 || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(input.questions[index - 1].id)} />
        <span>{index + 1}/{input.questions.length}</span>
        <Button type="text" aria-label="下一题" icon={<RightOutlined />} disabled={busy || index === input.questions.length - 1 || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(input.questions[index + 1].id)} />
      </nav> : null}
    </div>
    {canvas ? <h3 ref={titleRef} tabIndex={-1}>{question.title}</h3> : null}
    <div ref={bodyRef} className={ui('body')} tabIndex={0} aria-label="问题选项与补充说明">
    {question.description ? <p className={ui('description')}>{question.description}</p> : null}
    <fieldset disabled={!editable} className={ui('choices')}>
      <legend className="sr-only">{question.title}{question.required ? '（必答）' : ''}</legend>
      {question.options?.map((option, optionIndex) => <label key={option.id} className={`${ui('option')}${answer.selectedOptionIds.includes(option.id) ? ' is-selected' : ''}`}>
        <input type={question.kind === 'multi' ? 'checkbox' : 'radio'} className={canvas ? 'canvas-question-input' : 'assistant-option-input'} name={`assistant-${input.requestId}-${question.id}`} checked={answer.selectedOptionIds.includes(option.id)} onChange={() => draft.edit(toggleAssistantOption(question, answer, option.id))} />
        <span className={canvas ? 'canvas-question-number' : 'assistant-option-number'} aria-hidden>{canvas && answer.selectedOptionIds.includes(option.id) ? <CheckOutlined /> : optionIndex + 1}</span>
        <span className={canvas ? 'canvas-question-copy' : 'assistant-option-copy'}>{option.label}{option.description ? <small>{option.description}</small> : null}</span>
      </label>)}
      {question.allowFreeText || ['text', 'textarea', 'number'].includes(question.kind) ? <label className={ui('free-text')}>
        <span>{question.options?.length ? '其他，请输入' : '你的回答'}</span>
        {question.kind === 'number' ? <input onFocus={revealAnswer} type="number" min={question.min} max={question.max} value={answer.freeText} className={canvas ? `canvas-question-answer${question.options?.length ? ' is-supplement' : ''}` : 'assistant-field'} onChange={(event) => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))} />
          : <textarea onFocus={revealAnswer} placeholder={canvas ? "写下你的想法…" : undefined} rows={canvas ? question.options?.length ? 1 : 3 : 2} maxLength={question.maxLength} value={answer.freeText} className={canvas ? `canvas-question-answer${question.options?.length ? ' is-supplement' : ''}` : 'assistant-field'} onChange={(event) => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))} />}
      </label> : null}
    </fieldset>
    {draft.conflicts.includes(question.id) ? <div className={ui('conflict')} role="alert">
      <p>这道问题已在另一页面更新，请选择保留的回答。</p>
      <p>已保存：{draft.serverAnswers[question.id]?.freeText || question.options?.filter((option) => draft.serverAnswers[question.id]?.selectedOptionIds.includes(option.id)).map((option) => option.label).join('、') || '尚未回答'}</p>
      <div><Button size="small" onClick={() => draft.resolveConflict(question.id, false)}>使用已保存回答</Button><Button size="small" onClick={() => draft.resolveConflict(question.id, true)}>保留当前回答</Button></div>
    </div> : null}
    {draft.error ? <p className="assistant-inline-error" role="alert">{draft.error}</p> : null}
    </div>
    <div className={ui('footer')}>
      <span className={ui('sync')} role="status">{draft.saving ? <span className={canvas ? 'canvas-assistant-status-shimmer' : undefined}>正在保存…</span> : draft.suspendPending ? '暂停待同步' : canvas ? question.kind === 'multi' ? '支持多选' : '回答自动保存' : ''}</span>
      {input.status === 'SUSPENDED' ? <Button disabled={busy || !store.getSnapshot().snapshot?.activeRun?.allowedActions.includes('RESUME')} onClick={() => void store.control('resume')}>继续回答</Button> : assistantQuestionEditable(input) ? <>
        {!requestsPause ? <Button type="text" disabled={busy || draft.saving || draft.acting || !draft.writable || !draft.suspendPending && !!draft.conflicts.length} onClick={() => { if (draft.suspendPending || question.required) void draft.suspend(); else { draft.edit(assistantAnswerValue(question, answer, { selectedOptionIds: [], freeText: '' })); continueAnswer() } }}>{draft.suspendPending ? '重试暂停' : question.required ? '暂不回答' : '忽略'}</Button> : null}
        <Button type="primary" loading={waitForAutosaveOnSubmit && draft.acting} aria-busy={waitForAutosaveOnSubmit ? draft.acting : undefined} disabled={requestsPause ? pauseBlocked : !editable || (!waitForAutosaveOnSubmit && draft.saving) || !!draft.conflicts.length || question.required && !assistantAnswerComplete(question, answer)} onClick={requestsPause ? () => void (retryingPause ? draft.retrySuspend() : draft.suspend()) : continueAnswer}>{retryingPause ? '核实并重试暂停' : requestsPause ? '暂停并保留进度' : nextQuestionPending ? '下一题' : '提交'}</Button>
      </> : <span>{['SUBMITTED', 'CONSUMED'].includes(input.status) ? '已回答' : '问题已更新'}</span>}
    </div>
  </section>

  return (
    <section className="assistant-card flex min-w-0 flex-col gap-3 rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] bg-[var(--studio-ui-surface,rgba(255,255,255,0.05))] p-4" aria-label="补充创作信息">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">
        <span>询问中 · {input.questions.length - unanswered} 项已完成 · {unanswered} 项待完善</span>
        <span role="status">{draft.suspendPending ? '暂停待同步' : draft.saving ? '保存中…' : draft.conflicts.length ? '答案冲突' : draft.dirtyCount ? '待同步' : '已保存'}</span>
      </div>
      <div className="assistant-question-heading">
        <h3 ref={titleRef} tabIndex={-1} className="m-0 text-sm font-medium leading-6 outline-none">{question.title}{question.required ? ' *' : ''}</h3>
        <nav className="assistant-question-navigation" aria-label="问题导航">
          <Button type="text" aria-label="上一题" icon={<LeftOutlined />} disabled={busy || index === 0 || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(input.questions[index - 1].id)} />
          <span>{index + 1}/{input.questions.length}</span>
          <Button type="text" aria-label="下一题" icon={<RightOutlined />} disabled={busy || index === input.questions.length - 1 || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(input.questions[index + 1].id)} />
        </nav>
      </div>
      {input.questions.length > 1 ? <nav className="assistant-question-progress" aria-label="问题完成进度">{input.questions.map((item, itemIndex) => {
        const complete = assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id))
        return <Button key={item.id} size="small" type={item.id === question.id ? 'primary' : 'text'} aria-current={item.id === question.id ? 'step' : undefined} aria-label={`第 ${itemIndex + 1} 题，${complete ? '已完成' : '待完善'}`} disabled={busy || draft.acting || draft.saving || draft.suspendPending} onClick={() => void draft.move(item.id)}>{itemIndex + 1} · {complete ? '已完成' : '待完善'}</Button>
      })}</nav> : null}
      {requiredUnanswered > 0 ? <p className="m-0 text-xs">其中 {requiredUnanswered} 项必答尚未完成；部分草稿可以保存。</p> : null}
      {(answer.selectedOptionIds.length > 0 || !!answer.freeText.trim()) && !assistantAnswerComplete(question, answer) ? <p role="status" className="m-0 text-xs">本题已填写部分内容，尚未完成；可以继续保存草稿。</p> : null}
      {question.description ? <p className="m-0 text-xs leading-5 text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">{question.description}</p> : null}
      <fieldset disabled={!editable} className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
        <legend className="sr-only">{question.title}</legend>
        {question.options?.map((option, optionIndex) => (
          <label key={option.id} className="assistant-question-option flex min-h-10 cursor-pointer items-start gap-2 rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.15))] px-3 py-2 focus-within:ring-2 focus-within:ring-[var(--home-cyan)]">
            <span className="assistant-option-number" aria-hidden>{optionIndex + 1}</span><input type={question.kind === 'multi' ? 'checkbox' : 'radio'} className="assistant-option-input mt-1 shrink-0"
              name={`assistant-${input.requestId}-${question.id}`} checked={answer.selectedOptionIds.includes(option.id)}
              onChange={() => draft.edit(toggleAssistantOption(question, answer, option.id))} />
            <span className="min-w-0 break-words text-sm leading-5">{option.label}{option.description ? <small className="assistant-option-description mt-1 block text-xs text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">{option.description}</small> : null}</span>
          </label>
        ))}
        {question.allowFreeText || question.kind === 'text' || question.kind === 'textarea' || question.kind === 'number' ? (
          <label className="flex min-w-0 flex-col gap-1 text-xs text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">
            {question.options?.length ? '自由补充' : '你的回答'}
            {question.kind === 'number' ? (
              <input type="number" value={answer.freeText} min={question.min} max={question.max}
                onChange={(event) => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))}
                className="assistant-field min-h-10 w-full min-w-0 rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.2))] bg-transparent px-3 text-sm text-[var(--studio-ui-text,#fff)] focus:outline-none focus:ring-2 focus:ring-[var(--home-cyan)]" />
            ) : (
              <textarea rows={3} value={answer.freeText} maxLength={question.maxLength}
                onChange={(event) => draft.edit(assistantAnswerValue(question, answer, { freeText: event.target.value }))}
                className="assistant-field w-full min-w-0 resize-y rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.2))] bg-transparent px-3 py-2 text-sm leading-6 text-[var(--studio-ui-text,#fff)] focus:outline-none focus:ring-2 focus:ring-[var(--home-cyan)]" />
            )}
          </label>
        ) : null}
      </fieldset>
      {editable && (answer.freeText || answer.selectedOptionIds.length) ? <Button size="small" className="self-start" onClick={() => draft.edit(assistantAnswerValue(question, answer, { selectedOptionIds: [], freeText: '' }))}>清空本题</Button> : null}
      {draft.conflicts.includes(question.id) ? (
        <div className="assistant-card assistant-card-compact flex flex-col gap-2 rounded-lg border border-amber-300/40 p-3 text-xs" role="alert">
          <span>此答案已在其他页面更新。本地内容仍保留，请选择使用哪一份。</span>
          <p className="m-0 whitespace-pre-wrap break-words">服务器答案：{draft.serverAnswers[question.id]?.freeText || question.options?.filter((option) => draft.serverAnswers[question.id]?.selectedOptionIds.includes(option.id)).map((option) => option.label).join('、') || '未回答'}</p>
          <div className="flex flex-wrap gap-2">
            <Button size="small" onClick={() => draft.resolveConflict(question.id, false)}>使用服务器答案</Button>
            <Button size="small" onClick={() => draft.resolveConflict(question.id, true)}>保留本地并重新保存</Button>
          </div>
        </div>
      ) : null}
      {draft.error ? <p className="m-0 text-xs leading-5 text-[var(--studio-danger,#fde68a)]" role="alert">{draft.error}</p> : null}
      {input.status === 'SUSPENDED' ? (
        <Button disabled={busy || !store.getSnapshot().snapshot?.activeRun?.allowedActions.includes('RESUME')} onClick={() => void store.control('resume')}>继续回答</Button>
      ) : assistantQuestionEditable(input) ? (
        <div className="assistant-card-actions flex flex-wrap gap-2 border-t border-[var(--studio-ui-border,rgba(255,255,255,0.1))] pt-3">
          {!requestsPause ? <Button disabled={busy || draft.saving || draft.acting} onClick={() => void draft.suspend()}>{draft.suspendPending ? '重试暂停' : '取消提问'}</Button> : null}
          <Button disabled={!editable || draft.saving || !draft.dirtyCount} onClick={() => void draft.flush()}>保存草稿</Button>
          <Button type="primary" loading={waitForAutosaveOnSubmit && draft.acting} aria-busy={waitForAutosaveOnSubmit ? draft.acting : undefined} disabled={requestsPause ? pauseBlocked : !editable || (!waitForAutosaveOnSubmit && draft.saving) || !!draft.conflicts.length || question.required && !assistantAnswerComplete(question, answer)} onClick={() => {
            if (requestsPause) { void (retryingPause ? draft.retrySuspend() : draft.suspend()); return }
            const nextQuestion = input.questions.slice(index + 1).find((item) => !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id)))
            if (nextQuestion) void draft.move(nextQuestion.id)
            else if (requiredUnanswered > 0) {
              const remainingQuestion = input.questions.find((item) => item.required && !assistantAnswerComplete(item, draft.answers[item.id] || emptyAssistantAnswer(item.id)))
              if (remainingQuestion) void draft.move(remainingQuestion.id)
            } else void draft.submit()
          }}>{retryingPause ? '核实并重试暂停' : requestsPause ? '暂停并保留进度' : requiredUnanswered > (question.required ? 1 : 0) || index < input.questions.length - 1 ? '继续' : '提交答案'}</Button>
        </div>
      ) : <p className="m-0 text-xs text-[var(--studio-ui-muted,rgba(255,255,255,0.7))]">{input.status === 'SUBMITTED' || input.status === 'CONSUMED' ? '答案已提交' : '此问题已失效，请查看最新问题'}</p>}
    </section>
  )
}
