export function shouldLockSmartEdit(input: { hasCanvasRuntime: boolean; isTeamProject: boolean }): boolean {
  return input.hasCanvasRuntime && input.isTeamProject
}
