import type {
  CanvasPersonalSkill,
  CanvasSkillDraft,
  CanvasSkillValidation,
  CanvasSkillVersion
} from '@/types/canvas-skill-api'
import { canvasCreatorList, canvasCreatorPost, canvasCreatorVersionList } from './canvasRuntime'
import { canvasApiPost } from '@/utils/canvas/canvasApiClient'
import { normalizeCanvasPayload } from './canvas'
import { unwrap } from './shared'

export const personalSkillList = (pageNum = 1, keyword = '') =>
  canvasCreatorList<CanvasPersonalSkill>('skill', {
    pageNum,
    pageSize: 50,
    keyword
  })
export const personalSkillDraft = (skillId: string, baseVersionId?: string) =>
  canvasCreatorPost<CanvasSkillDraft>('skill', 'draft/detail', {
    skillId,
    ...(baseVersionId ? { baseVersionId } : {})
  })
export const personalSkillVersions = (skillId: string, pageNum = 1) =>
  canvasCreatorVersionList<CanvasSkillVersion>('skill', skillId, pageNum)
export const personalSkillSave = (data: CanvasSkillDraft) =>
  canvasCreatorPost<CanvasSkillDraft>('skill', 'draft/save', { ...data })
export const personalSkillValidate = (data: CanvasSkillDraft) =>
  canvasCreatorPost<CanvasSkillValidation>('skill', 'draft/validate', {
    ...data
  })
export const personalSkillPublish = (draftId: string, draftDigest: string, versionCode: string) =>
  canvasCreatorPost<CanvasSkillVersion>('skill', 'draft/publish', {
    draftId,
    draftDigest,
    versionCode
  })
export const personalSkillActivate = (
  skillId: string,
  versionId: string,
  expectedCurrentVersionId: string | null
) =>
  canvasCreatorPost('skill', 'version/activate', {
    skillId,
    versionId,
    expectedCurrentVersionId
  })
export const personalSkillPublicationSettings = () =>
  canvasCreatorPost<{ allowUserPublicSkills: boolean }>('skill', 'publication/settings')
export const personalSkillIdentity = (data: {
  id: string
  name: string
  description?: string
  status: '0' | '1'
  visibility?: 'PRIVATE' | 'PUBLIC'
  iconUrl?: string
  cacheToDisk?: boolean
}) => canvasCreatorPost('skill', 'identity/edit', data)

export async function personalSkillCover(skillId: string, file: File) {
  const data = new FormData()
  data.set('skillId', skillId)
  data.set('file', file)
  return canvasApiPost<{
    url: string
    fileName: string
    originalFileName: string
    fileSize: number
  }>('/api/user/runtime/creator/skill/cover/upload', data)
    .then(unwrap)
    .then(normalizeCanvasPayload)
}
