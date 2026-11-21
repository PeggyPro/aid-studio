'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import type { AssistantTargetEffect, AssistantEffectHandler } from '~/types/assistant-effects'
import { canLocateAssistantEffect } from '~/utils/assistant/assistantEffects'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'

export function AssistantEffectAction({ effect, onLocateTarget }: { effect?: AssistantTargetEffect; onLocateTarget?: AssistantEffectHandler }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null)
  const active = useRef(true), pending = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  if (!effect || !onLocateTarget || !canLocateAssistantEffect(effect)) return null
  const locate = async () => {
    if (pending.current) return
    pending.current = true; setBusy(true); setError(null)
    try { await onLocateTarget(effect, () => active.current) }
    catch (cause) { if (active.current) setError(assistantErrorMessage(cause)) }
    finally { pending.current = false; if (active.current) setBusy(false) }
  }
  return <div className="flex flex-col items-start gap-1">
    <Button size="small" loading={busy} disabled={busy} onClick={() => void locate()}>定位关联对象</Button>
    {error ? <p role="alert" className="m-0 text-xs">{error}</p> : null}
  </div>
}
