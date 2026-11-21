'use client'

import { useCallback, useEffect, useState } from 'react'
import { useCanvasGatewayIdentity } from './useCanvasGatewayIdentity'
import { canvasAssistantTransport } from '@/utils/canvas/canvasAssistantTransport'
import { assistantErrorMessage } from '@/utils/assistant/assistantRequestCoordinator'

import { readAssistantActorIdentity } from '@/utils/assistant/assistantActorIdentity'

/** 通过当前后端读取权威账号ID，不用客户端缓存的用户编号代替权限校验。 */
export function useCanvasAssistantIdentity() {
  const { identity, connected } = useCanvasGatewayIdentity()
  const [revision, setRevision] = useState(0)
  const retry = useCallback(() => setRevision(value => value + 1), [])
  const [profile, setProfile] = useState<{ identity: string; actorId: string; error?: string } | null>(null)
  useEffect(() => {
    let active = true
    if (!connected) return
    void readAssistantActorIdentity(canvasAssistantTransport, identity, revision > 0).then((actorId) => {
      if (!active || identity !== canvasAssistantTransport.identityKey()) return
      setProfile({ identity, actorId })
    }).catch((error) => { if (active && identity === canvasAssistantTransport.identityKey()) setProfile({ identity, actorId: '', error: assistantErrorMessage(error) }) })
    return () => { active = false }
  }, [identity, revision, connected])
  return { identity, connected, retry, actorId: connected && profile?.identity === identity ? profile.actorId : '', error: !connected ? '请先登录账号，再使用助手' : profile?.identity === identity ? profile.error : undefined }
}
