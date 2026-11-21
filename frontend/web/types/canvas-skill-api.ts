/** Personal Skill contracts. Package bodies are kept only in the mounted editor. */
export interface CanvasPersonalSkill {
  id: string
  skillCode: string
  name: string
  description?: string
  iconUrl?: string
  visibility: 'PRIVATE' | 'PUBLIC'
  invocationScope?: 'ENTRYPOINT' | 'INTERNAL'
  executorType?: string
  currentVersionId?: string | null
  status: '0' | '1'
}

export interface CanvasSkillResource {
  resourceKey: string
  filePath: string
  resourceType?: 'REFERENCE' | 'INSTRUCTION'
  mimeType?: 'text/markdown' | 'text/plain'
  content: string
  routeJson?: string
}

export interface CanvasSkillDraft {
  skillId: string
  baseVersionId?: string | null
  draftId?: string
  draftDigest?: string
  skillMarkdown: string
  systemPrompt: string
  modelCode?: string
  defaultModelCode?: string
  selectableModelCodes?: string[]
  modelConfigJson?: string
  inputSchemaJson?: string
  outputSchemaJson?: string
  definitionJson?: string
  maxOutputTokens?: number
  contextWindowTokens?: number
  safetyMarginTokens?: number
  resources: CanvasSkillResource[]
  relations?: Array<{
    relationKey: string
    childSkillId: string
    childVersionId: string
    requiredFlag?: boolean
  }>
}

export interface CanvasSkillVersion {
  id: string
  versionCode: string
  publishStatus?: string
  packageDigest?: string
  current?: boolean
  isCurrent?: boolean
  status?: string
}

export interface CanvasSkillValidation {
  valid: boolean
  draftDigest?: string
  errors?: Array<{ field: string; message: string }>
  warnings?: Array<{ field: string; message: string }>
}
