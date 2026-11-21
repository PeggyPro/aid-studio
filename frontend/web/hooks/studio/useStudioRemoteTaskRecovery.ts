'use client'

import { useEffect, useRef } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { recoverStudioRemoteTask } from '@/utils/studio/studioRemoteTaskRecovery'

export function useStudioRemoteTaskRecovery(enabled = true): void {
  const hydrated = useStudioUiStore((state) => state.hydrated)
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const recoveredScopeRef = useRef('')

  useEffect(() => {
    if (!enabled || useStudioUiStore.getState().readOnly || !hydrated || studioMode !== 'free' || recoveredScopeRef.current === scopeKey) return
    recoveredScopeRef.current = scopeKey
    const tasks = useStudioUiStore.getState().tasks.filter((task) =>
      task.resultKind !== 'director_space' && Boolean(task.remoteTaskId) && (task.status === 'running' || task.status === 'queued' || task.status === 'waiting')
    )
    for (const task of tasks) {
      void recoverStudioRemoteTask(task).catch(() => {
        if (useStudioUiStore.getState().scopeKey === scopeKey) {
          useStudioUiStore.getState().patchTask(task.id, {
            status: 'waiting',
            stage: '恢复连接失败，可稍后返回重试'
          })
        }
      })
    }
  }, [enabled, hydrated, scopeKey, studioMode])
}
