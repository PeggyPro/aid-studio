'use client'

import { useEffect } from 'react'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'

/** 智能体对话面板打开时声明需要画布连接。恢复由工作区的同一条连接入口完成。 */
export function useCanvasAgentCollaborationDemand() {
  const runtime = useCanvasProjectRuntime()
  const canEdit = runtime?.canEdit === true
  const retain = runtime?.retainCanvasCollaboration
  useEffect(() => {
    if (!canEdit || !retain) return
    return retain()
  }, [canEdit, retain])
}
