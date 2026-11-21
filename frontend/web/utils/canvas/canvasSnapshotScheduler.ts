/** One authoritative read at a time; an event arriving mid-read requests one trailing reconciliation. */
export function createCanvasSnapshotScheduler<T>(run: (workspaceId: string, payload: T) => Promise<void>) {
  let active: Promise<void> | null = null
  let activeWorkspace = ''
  let trailing: { workspaceId: string; payload: T } | null = null

  return {
    request(workspaceId: string, payload: T, invalidatesInFlight: boolean): Promise<void> {
      if (active) {
        if (invalidatesInFlight || workspaceId !== activeWorkspace) trailing = { workspaceId, payload }
        return active
      }
      activeWorkspace = workspaceId
      const task = (async () => {
        let next: { workspaceId: string; payload: T } | null = { workspaceId, payload }
        let lastError: unknown = null
        while (next) {
          const current = next
          trailing = null
          activeWorkspace = current.workspaceId
          try {
            await run(current.workspaceId, current.payload)
            lastError = null
          } catch (error) {
            lastError = error
          }
          next = trailing
        }
        if (lastError) throw lastError
      })()
      active = task.finally(() => { active = null })
      return active
    }
  }
}
