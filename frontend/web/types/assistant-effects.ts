/** 服务端回执证明的对象身份；与编辑器本地 ContextRef 分开，版本不得经过 number。 */
export type AssistantTargetRef =
  | { kind: 'FREE_CANVAS'; projectId: string; workspaceId: string; objectId: string; version: string }
  | { kind: 'FLOW_ENTITY'; projectId: string; episodeId: string; entityType: string; entityId: string; version: string }
  | { kind: 'SCRIPT'; scriptId: string; version: string; contentHash: string; projectId?: string; episodeId?: string; selection?: { start: number; end: number; selectedTextHash: string } }

export interface AssistantTargetEffect {
  effectId: string
  sourceKind: 'RUNTIME_STEP' | 'ARTIFACT_ADOPTION'
  sourceId: string
  effectKind: 'OBSERVED' | 'CREATED' | 'UPDATED' | 'ADOPTED'
  toolCode?: string
  artifactId?: string
  resultTargetRef?: AssistantTargetRef
  locateState: 'AVAILABLE' | 'UNAVAILABLE'
  reasonCode?: string
  allowedActions: ('LOCATE')[]
}

export interface AssistantEffectResolved {
  sessionId: string
  effectId: string
  locateState: 'AVAILABLE' | 'UNAVAILABLE'
  currentTargetRef?: AssistantTargetRef
  reasonCode?: string
  allowedActions: ('LOCATE')[]
  observedSessionRevision: number
  lastEventSeq: number
}

export type AssistantEffectHandler = (effect: AssistantTargetEffect, isCurrent?: () => boolean) => Promise<void>
