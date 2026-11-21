'use client'

import { FileTextOutlined, LoadingOutlined, PictureOutlined, VideoCameraOutlined } from '@ant-design/icons'
import type { AssistantSessionSnapshot } from '~/types/assistant-api'
import type { AssistantEffectHandler } from '~/types/assistant-effects'
import { AssistantEffectAction } from './AssistantEffectAction'
import { assistantConversationStepDisplay } from '~/utils/assistant/assistantConversationPresentation'

/** Public execution status, never model-private reasoning or inferred success. */
export function AssistantRunActivity({ snapshot, onLocateTarget, showThinking = true }: { snapshot: AssistantSessionSnapshot; onLocateTarget?: AssistantEffectHandler; showThinking?: boolean }) {
  const run = snapshot.activeRun
  const thinking = showThinking && !!run && ['READY', 'RUNNING'].includes(run.status) && ['ADVANCE_AGENT', 'WAIT_AGENT_TASK', 'APPLY_AGENT_DECISION'].includes(run.nextAction || '')
  const nodeSteps = snapshot.steps.filter(step => /^canvas\.node\.create_(text|image|video|audio|music)$/.test(step.toolCode || ''))
  if (!thinking && !nodeSteps.length) return null
  return <div className="assistant-run-activity">
    {nodeSteps.map(step => {
      const kind = step.toolCode?.split('_').at(-1)
      const label = ({ text: '文本', image: '图片', video: '视频', audio: '音频', music: '音乐' } as Record<string, string>)[kind || ''] || ''
      const completed = step.status === 'SUCCEEDED' || step.status === 'COMPLETED'
      const pending = ['PREPARING', 'READY', 'SUBMITTING', 'INFLIGHT', 'RUNNING'].includes(step.status)
      const stopped = ['STOPPED', 'CANCELLED', 'SKIPPED'].includes(step.status)
      const display = assistantConversationStepDisplay(step, snapshot.tasks, run)
      const title = label + '节点' + (completed ? '已创建' : display.state === 'failed' ? '创建失败' : stopped ? '创建已停止' : pending && display.spinning ? '创建中' : pending ? '尚未创建' : '状态待核实')
      return <div key={step.stepId} className="assistant-node-activity">
        {kind === 'image' ? <PictureOutlined /> : kind === 'video' ? <VideoCameraOutlined /> : <FileTextOutlined />}
        <span role="status">{title}</span>
        {completed ? step.effects.map(effect => <AssistantEffectAction key={effect.effectId} effect={effect} onLocateTarget={onLocateTarget} />) : null}
      </div>
    })}
    {thinking ? <p role="status" className="assistant-thinking-status"><LoadingOutlined spin /> 思考中…</p> : null}
  </div>
}
