/** 业务目录返回的官方音频输入定义；不包含上游地址或密钥。 */
export interface AudioSchema {
  $ref?: string
  $defs?: Record<string, AudioSchema>
  type?: string | string[]
  title?: string
  description?: string
  format?: string
  properties?: Record<string, AudioSchema>
  required?: string[]
  items?: AudioSchema
  enum?: unknown[]
  const?: unknown
  default?: unknown
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
  exclusiveMaximum?: number
  minLength?: number
  maxLength?: number
  minItems?: number
  maxItems?: number
  pattern?: string
  anyOf?: AudioSchema[]
  oneOf?: AudioSchema[]
  allOf?: AudioSchema[]
  additionalProperties?: boolean | AudioSchema
}

export interface AudioFileContract {
  mediaType: 'IMAGE' | 'VIDEO' | 'AUDIO'
  formats?: string[]
  maxFileSizeMb?: number
  minDurationSeconds?: number
  maxDurationSeconds?: number
  maxTotalDurationSeconds?: number
  maxItems?: number
  clipDurationSeconds?: number
  prepareUpload?: boolean
  purpose?: string
}

export interface AudioClientContract {
  schema: AudioSchema
  promptPath: string
  resultKind: string
  managedFields: string[]
  files: Record<string, AudioFileContract>
  sharedChoices: Record<string, string[]>
  choiceLabels?: Record<string, Record<string, string>>
  initialParameters?: Record<string, unknown>
  rules?: import('./modelParameters').ModelParameterRule[]
  minimumBillableSeconds?: number
}

export interface AudioCapabilityOption {
  code: string
  label?: string | null
  generateMode?: string | null
  audio?: AudioClientContract | null
  billing?: import('./business-api').ModelBillingDetailVO | null
}

export interface AudioFileReference { field: string; resourceId: string; referenceId?: string }
