'use client'

import { AssistantRunBudgetEditor } from '@/components/assistant/AssistantRunBudgetEditor'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import type { AssistantRunBudgetInput, AssistantRunBudgetPolicy, AssistantRunBudgetScope } from '@/types/assistant-budget'

/** Compatibility entry; all assistant hosts share budget drafts and validation. */
export function StudioCanvasAssistantBudget(props: {
  renewal?: boolean; settings?: boolean
  scope: AssistantRunBudgetScope; policy?: AssistantRunBudgetPolicy; preferredMode?: 'MANUAL' | 'AUTO'; quoteUpperBound?: string
  disabled: boolean; onSubmit: (input: AssistantRunBudgetInput) => Promise<unknown>; onCancel?: () => void
}) {
  return <AssistantRunBudgetEditor {...props} submitLabel={props.renewal ? '更新费用确认' : props.settings ? '保存生成预算' : props.policy ? '确认修改授权' : '确认额度并继续'}
    renderDisabledHint={(control, reason) => reason ? <StudioCanvasTooltip title={reason}>{control}</StudioCanvasTooltip> : control} />
}
