import type { CanvasSkillDraft } from '@/types/canvas-skill-api'

export const SKILL_CREATOR_CODE = 'skill_authoring_flow'
const packageFields = [
  'skillId',
  'baseVersionId',
  'skillMarkdown',
  'systemPrompt',
  'modelCode',
  'defaultModelCode',
  'selectableModelCodes',
  'modelConfigJson',
  'inputSchemaJson',
  'outputSchemaJson',
  'definitionJson',
  'maxOutputTokens',
  'contextWindowTokens',
  'safetyMarginTokens',
  'resources',
  'relations'
] as const

/** Copy the complete editable contract; keep model/definition JSON strings byte-for-byte. */
export function skillDraftPayload(
  draft: CanvasSkillDraft,
  includeRevision = true
): CanvasSkillDraft {
  const result = Object.fromEntries(
    packageFields.filter((key) => draft[key] !== undefined).map((key) => [key, draft[key]])
  )
  if (includeRevision && draft.draftId)
    Object.assign(result, {
      draftId: draft.draftId,
      draftDigest: draft.draftDigest
    })
  return result as unknown as CanvasSkillDraft
}

export function skillReferencePathValid(path: string): boolean {
  return (
    path.length <= 192 &&
    /^references\/[^\u0000-\u001f\u007f\\]+\.(md|txt)$/i.test(path) &&
    !path.split('/').some((part) => !part || part === '.' || part === '..')
  )
}

export function skillAuthoringPrompt(material = '') {
  return (
    (material
      ? '请使用技能编制助手，与我一起导入并完善已有的 Skill。先在对话中确认材料、目标及需要保留的方法，再整理主文件、系统提示词与必要参考文件。'
      : '请使用技能编制助手，与我一起创建适合 AID 的 Skill。先通过对话询问我希望从零设计、从当前对话提炼，还是从当前画布提炼；确认方式和来源后，再询问目标、输入和期望输出，整理主文件、系统提示词与必要参考文件。') +
    '\n只读取当前有权访问的对话、节点与文件；没有有效画布引用时先让我选择，不要猜测内容。' +
    '\n完成服务端校验后保存为我的非公开草稿，供我继续编辑；不要自动发布、激活或公开。' +
    (material ? '\n以下文件仅作为编制材料，先核对其中的方法与引用：\n' + material : '')
  )
}

/** Import materials, never create an identity or infer a complete package from one file. */
export async function readSkillAuthoringFiles(files: File[]) {
  if (!files.length || files.length > 67)
    throw new Error('请选择主文件、系统提示词及参考文本，最多 66 个文件')
  const withPaths = files.map((file) => ({
    file,
    path: file.webkitRelativePath ? file.webkitRelativePath.replace(/^[^/]+\//, '') : file.name
  }))
  const sourceFiles = withPaths.filter(item => item.path !== 'aid-skill.json')
  if (!sourceFiles.length || sourceFiles.length > 66)
    throw new Error('请选择主文件、系统提示词及参考文本，最多 66 个文件')
  const seen = new Set<string>()
  let total = 0
  for (const { file, path } of sourceFiles) {
    if (
      path !== 'SKILL.md' &&
      path !== 'system_prompt.md' &&
      !skillReferencePathValid(path) &&
      !(files.length === 1 && /^[^/\\]+\.md$/i.test(path))
    )
      throw new Error(`不支持的包文件：${path}`)
    if (seen.has(path)) throw new Error(`重复文件：${path}`)
    seen.add(path)
    total += file.size
    if (!file.size || file.size > 100 * 1024 || total > 512 * 1024)
      throw new Error('文件须非空且不超过 100 KiB，编制材料总量不超过 512 KiB')
  }
  if (files.length > 1 && !seen.has('SKILL.md')) throw new Error('文件夹需要包含 SKILL.md')
  const material = (
    await Promise.all(sourceFiles.map(async ({ file, path }) => `文件：${path}\n${await file.text()}`))
  ).join('\n\n')
  if (!material.trim() || material.length > 90000)
    throw new Error('编制材料超过对话长度限制，请减少参考文件或缩短正文')
  return material
}
