import type { UserSkillDefinition } from '~/types/user-skill'

export interface SkillPickerCard {
  skillCode: string
  name: string
  description: string
  iconUrl: string
}

/** Skill 列表接口里适合在选择面板展示的字段：名称、简介、图标。 */
export function toSkillPickerCard(skill: UserSkillDefinition): SkillPickerCard {
  return {
    skillCode: String(skill.skillCode || '').trim(),
    name: String(skill.name || skill.skillCode || '未命名 Skill').trim(),
    description: String(skill.description || '').trim(),
    iconUrl: String(skill.iconUrl || '').trim()
  }
}
