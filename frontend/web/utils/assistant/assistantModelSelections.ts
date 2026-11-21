import type { AssistantModelSelection } from '~/types/assistant-api'

export function assistantModelSelections(value: unknown): AssistantModelSelection[] {
  if (value == null) return []
  if (!Array.isArray(value) || value.length > 2) throw new Error('助手返回的模型选择无效')
  const types = new Set<string>()
  return value.map(item => {
    if (!item || !['image', 'video'].includes(item.modelType) || types.has(item.modelType)
      || typeof item.modelCode !== 'string' || !item.modelCode.trim() || item.modelCode.length > 100) throw new Error('助手返回的模型选择无效')
    types.add(item.modelType)
    return { modelType: item.modelType, modelCode: item.modelCode, modelName: typeof item.modelName === 'string' ? item.modelName.slice(0, 200) : item.modelCode,
      providerName: typeof item.providerName === 'string' ? item.providerName.slice(0, 200) : undefined,
      providerCode: typeof item.providerCode === 'string' ? item.providerCode.slice(0, 100) : undefined }
  })
}

export function assistantModelSelectionWire(value: AssistantModelSelection[]) {
  return assistantModelSelections(value).map(({ modelType, modelCode }) => ({ modelType, modelCode }))
}
