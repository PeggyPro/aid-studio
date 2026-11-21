import { modelParameterConstraints } from '@/utils/modelParameterConstraints'
import type { AudioClientContract, AudioSchema, AudioFileReference } from '@/types/audio-model'

export function audioSchema(schema: AudioSchema, root: AudioSchema, depth = 0): AudioSchema {
  if (depth > 16) return {}
  if (schema.$ref?.startsWith('#/$defs/')) {
    const key = decodeURIComponent(schema.$ref.slice(8)).replace(/~1/g, '/').replace(/~0/g, '~')
    return audioSchema({ ...root.$defs?.[key], ...schema, $ref: undefined }, root, depth + 1)
  }
  const nonNull = (schema.anyOf ?? schema.oneOf)?.filter(item => item.type !== 'null')
  if (nonNull?.length === 1) return audioSchema({ ...schema, ...nonNull[0], anyOf: undefined, oneOf: undefined }, root, depth + 1)
  return schema
}

export function audioSchemaType(schema: AudioSchema): string {
  return (Array.isArray(schema.type) ? schema.type.find(type => type !== 'null') : schema.type)
    ?? (schema.properties ? 'object' : schema.items ? 'array' : 'string')
}

export function audioGet(value: unknown, path: string): unknown {
  return path.split('.').filter(Boolean).reduce<unknown>((current, key) =>
    current != null && typeof current === 'object' ? (current as Record<string, unknown>)[key] : undefined, value)
}

/** 所有写入都复制整条路径；清除可选字段时真正省略字段，不发送空字符串代替省略。 */
export function audioSet(value: Record<string, unknown>, path: string, next: unknown): Record<string, unknown> {
  const result = structuredClone(value)
  if (next === undefined && audioGet(value, path) === undefined) return result
  const parts = path.split('.')
  let cursor: Record<string, unknown> = result
  parts.forEach((part, index) => {
    if (['__proto__', 'prototype', 'constructor'].includes(part)) throw new Error('参数路径无效')
    if (index === parts.length - 1) {
      if (next === undefined) delete cursor[part]
      else cursor[part] = next
    } else {
      if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = /^\d+$/.test(parts[index + 1]) ? [] : {}
      cursor = cursor[part] as Record<string, unknown>
    }
  })
  return result
}

export function audioDefault(schemaInput: AudioSchema, root: AudioSchema, depth = 0): unknown {
  if (depth > 12) return undefined
  const schema = audioSchema(schemaInput, root)
  if (schema.default != null) return structuredClone(schema.default)
  if (schema.const != null) return schema.const
  const alternatives = schema.oneOf ?? schema.anyOf
  if (alternatives?.length) return audioDefault(alternatives[0], root, depth + 1)
  const type = audioSchemaType(schema)
  if (type === 'object') {
    const result: Record<string, unknown> = {}
    for (const [name, field] of Object.entries(schema.properties ?? {})) {
      if (!schema.required?.includes(name)) continue
      const v = audioDefault(field, root, depth + 1)
      if (v !== undefined) result[name] = v
    }
    return result
  }
  if (type === 'array') return Array.from({ length: Math.max(1, schema.minItems ?? 0) }, () => audioDefault(schema.items ?? {}, root, depth + 1))
  if (schema.enum?.length) return schema.enum[0]
  if (type === 'boolean') return false
  if (type === 'number' || type === 'integer') {
    // 跨过 0 的调节量，默认是不变，而不是滑到最低。
    if (typeof schema.minimum === 'number' && typeof schema.maximum === 'number' && schema.minimum < 0 && schema.maximum > 0) return 0
    return schema.minimum ?? 0
  }
  return ''
}

export function audioInitialParameters(contract: AudioClientContract): Record<string, unknown> {
  let result = contract.initialParameters ? structuredClone(contract.initialParameters) : audioDefault(contract.schema, contract.schema) as Record<string, unknown>
  const defaults = (value: unknown) => {
    if (Array.isArray(value)) { value.forEach(defaults); return }
    if (!value || typeof value !== 'object') return
    for (const [key, child] of Object.entries(value)) {
      if (child === '' && contract.sharedChoices[key]?.length) (value as Record<string, unknown>)[key] = contract.sharedChoices[key][0]
      else defaults(child)
    }
  }
  defaults(result)
  if (contract.schema.properties?.mode?.enum?.includes('async')) result.mode = 'async'
  for (const managed of contract.managedFields) result = audioSet(result, managed, undefined)
  return result
}

const HIDDEN_AUDIO_FIELDS = new Set(['content_producer', 'produce_id', 'content_propagator', 'propagate_id'])
const CONTROL_FIELDS = new Set(['speech_rate', 'loudness_rate', 'speed', 'vol', 'pitch', 'pitch_rate'])

/** 内容标识不交给用户填写。未标必填的说话人也不进入请求。 */
export function isImplicitAudioField(name: string, required: boolean) {
  return HIDDEN_AUDIO_FIELDS.has(name) || (name === 'speaker' && !required)
}

function controlDefault(schema: AudioSchema) {
  if (typeof schema.default === 'number') return schema.default
  if (typeof schema.minimum === 'number' && typeof schema.maximum === 'number' && schema.minimum <= 0 && schema.maximum >= 0) return 0
  return schema.minimum ?? 0
}

function sealHiddenAudioParameters(contract: AudioClientContract, parameters: Record<string, unknown>) {
  let result = parameters
  const walk = (raw: AudioSchema, path: string, required: boolean, depth: number) => {
    if (depth > 16) return
    const schema = audioSchema(raw, contract.schema)
    const type = audioSchemaType(schema)
    if (type === 'array') {
      const items = path ? audioGet(result, path) : undefined
      if (Array.isArray(items)) items.forEach((_, index) => walk(schema.items ?? {}, `${path}.${index}`, true, depth + 1))
      return
    }
    if (type === 'object' && schema.properties && !audioFieldContract(contract, path)) {
      for (const [key, child] of Object.entries(schema.properties)) walk(child, path ? `${path}.${key}` : key, schema.required?.includes(key) ?? false, depth + 1)
      return
    }
    const name = path.split('.').at(-1) ?? ''
    if (!path) return
    if (isImplicitAudioField(name, required)) {
      if (schema.default !== undefined) result = audioSet(result, path, structuredClone(schema.default))
      else if (audioGet(result, path) !== undefined) result = audioSet(result, path, undefined)
      return
    }
    if (!CONTROL_FIELDS.has(name) || typeof audioGet(result, path) === 'number') return
    result = audioSet(result, path, controlDefault(schema))
  }
  walk(contract.schema, '', true, 0)
  return result
}

export function audioParametersWithPrompt(contract: AudioClientContract, parameters: Record<string, unknown>, prompt: string) {
  let result = structuredClone(parameters)
  if (contract.promptPath && (prompt.trim() || audioGet(result, contract.promptPath) !== undefined)) {
    result = audioSet(result, contract.promptPath, prompt)
  }
  for (const managed of contract.managedFields) result = audioSet(result, managed, undefined)
  return sealHiddenAudioParameters(contract, result)
}

export function audioFieldContract(contract: AudioClientContract, path: string) {
  return contract.files[path.replace(/\.\d+(?=\.|$)/g, '.*')]
}

/** 前端负责即时字段提示，最终契约与组合规则始终由服务端再次校验。 */
export function audioConstraints(contract: AudioClientContract, values: Record<string, unknown>) {
  return modelParameterConstraints({ id: 0, modelCode: '', modelName: '', modelType: 'audio', parameterRules: contract.rules }, { options: values })
}

export function audioParameterErrors(contract: AudioClientContract, values: Record<string, unknown>): Record<string, string> {
  const errors: Record<string, string> = {}
  const constraints = audioConstraints(contract, values)
  const walk = (raw: AudioSchema, value: unknown, path: string, required: boolean, depth: number) => {
    if (depth > 16 || contract.managedFields.includes(path)) return
    const name = path.split('.').at(-1) ?? ''
    required ||= constraints.get(`options.${path}`)?.required === true
    if (path && isImplicitAudioField(name, required)) return
    const constraint = constraints.get(`options.${path}`)
    if (constraint?.forbidden && value != null && value !== '') { errors[path] = '当前参数组合不支持此项'; return }
    required ||= constraint?.required === true
    if (constraint?.choices && value != null && !constraint.choices.some(option => option === value)) errors[path] = '请按当前参数组合重新选择'
    let schema = audioSchema(raw, contract.schema)
    const variants = schema.anyOf ?? schema.oneOf
    if (variants?.length) {
      const variant = variants.find(candidate => {
        const item = audioSchema(candidate, contract.schema), type = audioSchemaType(item)
        if (type === 'object') return !!value && typeof value === 'object' && !Array.isArray(value)
          && (item.required ?? []).every(key => Object.hasOwn(value, key))
        if (type === 'array') return Array.isArray(value)
        return typeof value === (type === 'integer' ? 'number' : type)
      })
      if (variant) schema = audioSchema(variant, contract.schema)
    }
    if (value == null || value === '' || Array.isArray(value) && value.length === 0) {
      if (required) errors[path] = '请填写此项'
      return
    }
    const type = audioSchemaType(schema)
    if (type === 'object') {
      if (typeof value !== 'object' || Array.isArray(value)) { errors[path] = '参数类型不正确'; return }
      for (const [key, field] of Object.entries(schema.properties ?? {})) walk(field, (value as Record<string, unknown>)[key], path ? `${path}.${key}` : key, schema.required?.includes(key) ?? false, depth + 1)
    } else if (type === 'array') {
      if (!Array.isArray(value)) { errors[path] = '需要列表'; return }
      if (schema.minItems != null && value.length < schema.minItems) errors[path] = `至少 ${schema.minItems} 项`
      if (schema.maxItems != null && value.length > schema.maxItems) errors[path] = `最多 ${schema.maxItems} 项`
      value.forEach((item, index) => walk(schema.items ?? {}, item, `${path}.${index}`, true, depth + 1))
    } else if (type === 'string') {
      if (typeof value !== 'string') { errors[path] = '需要文本'; return }
      if (schema.minLength != null && [...value].length < schema.minLength) errors[path] = `至少 ${schema.minLength} 个字符`
      if (schema.maxLength != null && [...value].length > schema.maxLength) errors[path] = `最多 ${schema.maxLength} 个字符`
      if (schema.pattern) { try { if (!new RegExp(schema.pattern).test(value)) errors[path] = '格式不符合要求' } catch { /* 服务端校验原始正则。 */ } }
    } else if (type === 'integer' || type === 'number') {
      if (typeof value !== 'number' || !Number.isFinite(value) || type === 'integer' && !Number.isInteger(value)) errors[path] = type === 'integer' ? '需要整数' : '需要数字'
      else if (schema.minimum != null && value < schema.minimum) errors[path] = `不能小于 ${schema.minimum}`
      else if (schema.maximum != null && value > schema.maximum) errors[path] = `不能大于 ${schema.maximum}`
    } else if (type === 'boolean' && typeof value !== 'boolean') errors[path] = '需要开关值'
    if (schema.enum && !schema.enum.some(item => item === value)) errors[path] = '请重新选择有效选项'
    const shared = contract.sharedChoices[path.split('.').at(-1) ?? '']
    if (shared?.length && typeof value === 'string' && !shared.includes(value)) errors[path] = '请选择当前开放的音色或资源'
  }
  walk(contract.schema, values, '', true, 0)
  return errors
}

/** Resolve only at the request boundary. Nodes retain resource IDs, not raw media addresses. */
export function audioParametersWithFiles(parameters: Record<string, unknown>, files: AudioFileReference[] = [], resolve: (id: string) => string | undefined) {
  let result = structuredClone(parameters)
  for (const file of files) result = audioSet(result, file.field, file.referenceId || resolve(file.resourceId))
  return result
}

/** Array editing may move a file to another index; retain its identity at the new path. */
export function audioPersistParameters(contract: AudioClientContract, values: Record<string, unknown>, files: AudioFileReference[], resolve: (id: string) => string | undefined) {
  let parameters = structuredClone(values)
  const references: AudioFileReference[] = []
  const walk = (value: unknown, path: string) => {
    if (audioFieldContract(contract, path) && typeof value === 'string') {
      const reference = files.find(file => (file.referenceId || resolve(file.resourceId)) === value)
      if (reference) references.push({ ...reference, field: path })
      parameters = audioSet(parameters, path, undefined)
      return
    }
    if (value && typeof value === 'object') for (const [key, child] of Object.entries(value)) walk(child, path ? `${path}.${key}` : key)
  }
  walk(values, '')
  return { parameters, files: references }
}
