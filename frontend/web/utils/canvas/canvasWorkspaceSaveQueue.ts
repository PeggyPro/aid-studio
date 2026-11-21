/** Serialize workspace writes. A post-save reservation runs before the next queued write. */
export function createCanvasWorkspaceSaveQueue() {
  let tail: Promise<void> = Promise.resolve()

  return {
    run(save: () => Promise<void>, afterSave?: () => void): Promise<void> {
      const current = tail.then(async () => {
        await save()
        afterSave?.()
      })
      tail = current.catch(() => undefined)
      return current
    }
  }
}
