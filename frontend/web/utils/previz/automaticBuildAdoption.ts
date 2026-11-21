import type { PrevizBuildOperation } from './buildAdoption'
import type { AssistantSessionSnapshot } from '@/types/assistant-api'

export const previzBuildToolCodes = ['aid.previz.build', 'aid.previz.build-scoped']

/** Session detail only projects steps for the active run. Archived results survive a new turn. */
export function previzArchivedBuildSteps(snapshot: AssistantSessionSnapshot): string[] {
  const previous = (snapshot.conversationArtifacts ?? []).filter(artifact =>
    artifact.type === 'PREVIZ_SCENE' && artifact.status === 'FINALIZED'
    && artifact.producerStepId && artifact.producerRunId
    && artifact.producerRunId !== snapshot.activeRun?.runId,
  )
  const latestRun = previous.reduce<string | undefined>((latest, artifact) =>
    !latest || BigInt(artifact.producerRunId!) > BigInt(latest) ? artifact.producerRunId : latest, undefined)
  return [...new Set(previous.filter(artifact => artifact.producerRunId === latestRun)
    .map(artifact => artifact.producerStepId!))].sort((left, right) => BigInt(left) < BigInt(right) ? -1 : 1)
}

export interface PrevizAutomaticAdoptionCandidate {
  stepId: string
  kind: 'build' | 'edit'
  operation?: PrevizBuildOperation
}

function sceneBuildBaseline(candidate: PrevizAutomaticAdoptionCandidate): string | undefined {
  const operation = candidate.kind === 'build' ? candidate.operation : undefined
  if (operation?.kind === 'ADD' || operation?.kind === 'REBUILD_SCENE') {
    return JSON.stringify([operation.editorVersion, operation.sceneDigest])
  }
}

/** A run can publish an ADD draft for preview before it produces its whole-scene rebuild. */
export function previzAutomaticAdoptionSteps(candidates: PrevizAutomaticAdoptionCandidate[], settled: boolean): string[] {
  if (!settled) {
    const firstDraft = candidates.findIndex(candidate => sceneBuildBaseline(candidate) !== undefined)
    // Hold the draft and later mutations together to preserve their execution order.
    const ready = firstDraft === -1 ? candidates : candidates.slice(0, firstDraft)
    return ready.map(candidate => candidate.stepId)
  }

  const rebuilds = new Set<string>()
  const selected: string[] = []
  for (const candidate of [...candidates].reverse()) {
    const baseline = sceneBuildBaseline(candidate)
    if (baseline === undefined) {
      // Object edits and explicit scoped replacements preserve their order.
      rebuilds.clear()
    } else {
      if (rebuilds.has(baseline)) continue
      if (candidate.operation?.kind === 'REBUILD_SCENE') rebuilds.add(baseline)
    }
    selected.push(candidate.stepId)
  }
  return selected.reverse()
}
