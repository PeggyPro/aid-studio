import { useCreationStore } from '@/stores/creation'
import type { GlobalSettingData } from '@/types'
import type { UserProjectCreateRequest, UserProjectRow, UserProjectType } from '@/types/business-api'
import { buildProjectVideoStyleFields } from '@/utils/buildProjectVideoStyleFields'
import { userProjectCreate } from '@/utils/businessApi'

export class StudioFlowProjectValidationError extends Error {}

export interface CreateStudioFlowProjectInput {
  title: string
  projectType: UserProjectType
  globalSetting: GlobalSettingData
}

export interface CreatedStudioFlowProject {
  projectId: number
  episodeId: number | null
  projectRow: UserProjectRow
  globalSetting: GlobalSettingData
  message: string
}

export async function createStudioFlowProject(
  input: CreateStudioFlowProjectInput
): Promise<CreatedStudioFlowProject> {
  const styleFields = buildProjectVideoStyleFields(
    input.globalSetting.selectedStyle,
    input.globalSetting.style
  )
  if (!styleFields) throw new StudioFlowProjectValidationError('请选择主题风格')
  if (!styleFields.styleSource || !styleFields.styleAssetId) {
    throw new StudioFlowProjectValidationError('请选择有效风格')
  }

  const title = input.title.trim() || '未命名作品'
  const globalSetting: GlobalSettingData = {
    ...input.globalSetting,
    title,
    style: input.globalSetting.selectedStyle?.name || input.globalSetting.style || ''
  }
  const payload: UserProjectCreateRequest = {
    projectName: title,
    projectDesc: String(globalSetting.description || '').slice(0, 500),
    projectType: input.projectType,
    aspectRatio: globalSetting.aspectRatio,
    scriptType: globalSetting.scriptType,
    ...styleFields,
    styleSource: styleFields.styleSource,
    styleAssetId: styleFields.styleAssetId,
    defaultGenMode: globalSetting.modelStrategy,
    defaultCreationMode: globalSetting.creationMode
  }
  const response = await userProjectCreate(payload)
  const episodeId = input.projectType === 'movie' ? 0 : null
  const savedGlobalSetting: GlobalSettingData = {
    ...globalSetting,
    styleSelectionTouched: false,
    styleLocked: response.data.styleLocked === true
  }

  const creation = useCreationStore.getState()
  creation.setWorkTitle(title)
  creation.updateFormData({ globalSetting: savedGlobalSetting })
  creation.setCurrentProjectContext({ projectId: response.data.id, episodeId })
  creation.setCurrentProjectType(input.projectType)

  return {
    projectId: response.data.id,
    episodeId,
    projectRow: response.data,
    globalSetting: savedGlobalSetting,
    message: response.msg
  }
}
