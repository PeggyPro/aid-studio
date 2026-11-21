import type { CanvasRuntimeGroup, CanvasRuntimeMember, CanvasRuntimeOutputKind } from '@/types/canvas-runtime-api'
import type { AssistantCapabilities } from '@/types/assistant-api'

export type CanvasSkillItem = {
  code: string
  name: string
  description: string
  group: string
  groupCode: string
  own: boolean
  available: boolean
  outputKind: CanvasRuntimeOutputKind
  iconUrl: string
  unavailableReason?: string
  visibility?: 'PRIVATE' | 'PUBLIC'
}

export type CanvasOwnSkill = {
  id: string
  skillCode: string
  name: string
  description?: string
  status: '0' | '1'
  currentVersionId?: string | null
  iconUrl?: string
  visibility?: 'PRIVATE' | 'PUBLIC'
}

const GENERAL_SKILL: CanvasSkillItem = {
  code: '',
  name: '通用创作',
  description: '使用当前画布对话能力，不附加 Skill',
  group: '推荐',
  groupCode: '',
  own: false,
  available: true,
  outputKind: 'TEXT',
  iconUrl: ''
}

/** The main Agent decides callability; the runtime directory supplies presentation metadata. */
export function reconcileCanvasAssistantSkills(
  directory: CanvasSkillItem[],
  authorized: NonNullable<AssistantCapabilities['universalSkills']>
): CanvasSkillItem[] {
  const metadata = new Map(directory.map(item => [item.code, item]))
  const callable = new Set(authorized.map(item => item.code))
  return [
    metadata.get('') || GENERAL_SKILL,
    ...authorized.map(item => {
      const existing = metadata.get(item.code)
      return {
        ...existing,
        code: item.code,
        name: item.name,
        description: item.description || existing?.description || '',
        group: existing?.group || '全能助手',
        groupCode: existing?.groupCode || 'assistant-universal',
        own: existing?.own || false,
        available: true,
        outputKind: existing?.outputKind || 'TEXT',
        iconUrl: existing?.iconUrl || '',
        unavailableReason: undefined
      } satisfies CanvasSkillItem
    }),
    ...directory.filter(item => item.code && item.own && !callable.has(item.code)).map(item => ({
      ...item,
      available: false,
      unavailableReason: item.available ? '当前助手未开放此 Skill' : item.unavailableReason
    }))
  ]
}

export function buildCanvasSkillItems(input: {
  groups: CanvasRuntimeGroup[]
  members: CanvasRuntimeMember[]
  mine: CanvasOwnSkill[]
}): CanvasSkillItem[] {
  const groupNames = new Map(input.groups.map((group) => [group.groupCode, group.name]))
  const ownCodes = new Set(input.mine.map((skill) => skill.skillCode))
  const known = new Set<string>()
  const published = input.members.filter((member) => member.memberType === 'SKILL').flatMap((member) => {
    if (known.has(member.memberCode)) return []
    known.add(member.memberCode)
    return [{
      code: member.memberCode,
      name: member.name,
      description: member.description || '',
      group: groupNames.get(member.groupCode) || '通用 Skill',
      groupCode: member.groupCode,
      own: ownCodes.has(member.memberCode),
      available: true,
      outputKind: member.outputKind,
      iconUrl: member.iconUrl || ''
    } satisfies CanvasSkillItem]
  })
  const drafts = input.mine.filter((skill) => !known.has(skill.skillCode)).map((skill) => ({
    code: skill.skillCode,
    name: skill.name,
    description: skill.description || '',
    group: '我的 Skill',
    groupCode: 'mine',
    own: true,
    available: false,
    outputKind: 'TEXT' as const,
    iconUrl: skill.iconUrl || '',
    visibility: skill.visibility,
    unavailableReason: skill.status === '1' ? 'Skill 已停用' : skill.currentVersionId ? '当前目录未开放此 Skill' : '请先发布正式版本并启用'
  }))
  return [GENERAL_SKILL, ...published, ...drafts]
}

export function filterCanvasSkillItems(
  items: CanvasSkillItem[],
  input: { tab: 'general' | 'favorites' | 'mine'; query: string; favorites: string[] }
): CanvasSkillItem[] {
  const needle = input.query.trim().toLocaleLowerCase()
  return items.filter((item) => {
    if (input.tab === 'general' && !item.available) return false
    if (input.tab === 'favorites' && !input.favorites.includes(item.code)) return false
    if (input.tab === 'mine' && !item.own) return false
    if (!needle) return true
    return `${item.name} ${item.code} ${item.description} ${item.group}`.toLocaleLowerCase().includes(needle)
  })
}

export function canvasSkillOutputLabel(kind: CanvasRuntimeOutputKind): string {
  if (kind === 'VIDEO') return '视频'
  if (kind === 'IMAGE') return '图片'
  return '文本'
}

export type StudioSkillSlashToken = { start: number; query: string }

/** 光标前最近的 /命令。命令中间不能再有空格或第二个斜杠。 */
export function studioSkillSlashQuery(value: string, cursor: number): StudioSkillSlashToken | null {
  const head = value.slice(0, Math.max(0, cursor))
  const match = /(?:^|\s)\/([^\s/]*)$/.exec(head)
  if (!match) return null
  const query = match[1]
  return { start: head.length - query.length - 1, query }
}

export function listStudioSkillSlashChoices(
  items: CanvasSkillItem[],
  favorites: string[],
  query: string
): CanvasSkillItem[] {
  const needle = query.trim().toLocaleLowerCase()
  const usable = items.filter((item) => item.available)
  if (needle) {
    return usable
      .filter((item) => `${item.name} ${item.code} ${item.description}`.toLocaleLowerCase().includes(needle))
      .slice(0, 12)
  }
  const favoriteSet = new Set(favorites)
  const general = usable.filter((item) => !item.code)
  const favorite = usable.filter((item) => item.code && favoriteSet.has(item.code))
  const rest = usable.filter((item) => item.code && !favoriteSet.has(item.code))
  return [...general, ...favorite, ...rest].slice(0, 8)
}

export function applyStudioSkillSlashChoice(value: string, token: StudioSkillSlashToken): string {
  const end = token.start + 1 + token.query.length
  return `${value.slice(0, token.start)}${value.slice(end)}`.replace(/[ \t]{2,}/g, ' ')
}
