import type {
  StudioCanvasNode,
  StudioStoryboardAssetKind,
  StudioStoryboardAssetRef,
  StudioStoryboardDialogueItem,
  StudioStoryboardTableRow
} from '@/types/studio'

export const STUDIO_STORYBOARD_ROW_COLORS = ['', '#10251f', '#252014', '#132432', '#231b2d', '#25272b'] as const

let fallbackId = 0

function localId(prefix: string): string {
  const randomId = globalThis.crypto?.randomUUID?.()
  if (randomId) return `${prefix}-${randomId}`
  fallbackId += 1
  return `${prefix}-${Date.now()}-${fallbackId}`
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function duration(value: unknown): number {
  const next = Number(value)
  if (!Number.isFinite(next)) return 5
  return Math.min(10, Math.max(3, Math.round(next)))
}

export function createStudioStoryboardRow(index: number): StudioStoryboardTableRow {
  return {
    id: localId('shot'),
    shot: `镜头 ${index}`,
    duration: 5,
    description: '',
    shotSize: '',
    lighting: '',
    dialogue: [],
    soundEffect: '',
    cameraMovement: '',
    finalPrompt: '',
    motionPrompt: ''
  }
}

function normalizeDialogue(value: unknown): StudioStoryboardDialogueItem[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item, index) => {
    if (!item || typeof item !== 'object') return []
    const source = item as Partial<StudioStoryboardDialogueItem>
    const kind = source.kind === 'narration' ? 'narration' : 'dialogue'
    return [{
      id: text(source.id) || `line-${index + 1}`,
      kind,
      speaker: kind === 'dialogue' ? text(source.speaker) : undefined,
      content: text(source.content)
    }]
  })
}

export function normalizeStudioStoryboardRows(value: unknown): StudioStoryboardTableRow[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item, index) => {
    if (!item || typeof item !== 'object') return []
    const source = item as Partial<StudioStoryboardTableRow> & { title?: unknown }
    return [{
      id: text(source.id) || `shot-${index + 1}`,
      shot: text(source.shot) || `镜头 ${index + 1}`,
      duration: duration(source.duration),
      description: text(source.description) || text(source.title),
      shotSize: text(source.shotSize),
      lighting: text(source.lighting),
      dialogue: normalizeDialogue(source.dialogue),
      soundEffect: text(source.soundEffect),
      cameraMovement: text(source.cameraMovement),
      finalPrompt: text(source.finalPrompt),
      motionPrompt: text(source.motionPrompt),
      rowColor: STUDIO_STORYBOARD_ROW_COLORS.includes(source.rowColor as typeof STUDIO_STORYBOARD_ROW_COLORS[number])
        ? source.rowColor
        : undefined
    }]
  })
}

export function studioStoryboardRowsFromNode(node: StudioCanvasNode): StudioStoryboardTableRow[] {
  const stored = normalizeStudioStoryboardRows(node.data.storyboardRows)
  if (stored.length) return stored
  const localText = node.data.resultSummary || (node.data.status === 'success' ? node.data.prompt : '')
  const paragraphs = String(localText || '').split(/\r?\n+/).map((line) => line.trim()).filter(Boolean)
  if (paragraphs.length) {
    return paragraphs.map((description, index) => ({
      ...createStudioStoryboardRow(index + 1),
      description
    }))
  }
  return [createStudioStoryboardRow(1)]
}

export function normalizeStudioStoryboardAssets(
  value: unknown,
  availableNodeIds?: ReadonlySet<string>
): StudioStoryboardAssetRef[] {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  const order: Record<StudioStoryboardAssetKind, number> = { scene: 0, character: 1, prop: 2 }
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return []
    const source = item as Partial<StudioStoryboardAssetRef>
    if (source.kind !== 'scene' && source.kind !== 'character' && source.kind !== 'prop') return []
    const nodeId = text(source.nodeId)
    const imageUrl = text(source.imageUrl)
    if (!nodeId || !imageUrl || seen.has(source.kind)) return []
    if (availableNodeIds && !availableNodeIds.has(nodeId)) return []
    seen.add(source.kind)
    return [{ kind: source.kind, nodeId, title: text(source.title) || '未命名图片', imageUrl }]
  }).sort((left, right) => order[left.kind] - order[right.kind])
}

function dialogueText(items: StudioStoryboardDialogueItem[]): string {
  return items
    .filter((item) => item.content.trim())
    .map((item) => item.kind === 'narration'
      ? `旁白：${item.content.trim()}`
      : `${item.speaker?.trim() || '未绑定角色'}：${item.content.trim()}`)
    .join('；')
}

export function composeBalancedStoryboardPrompt(row: StudioStoryboardTableRow): Pick<StudioStoryboardTableRow, 'finalPrompt' | 'motionPrompt'> {
  const dialogue = dialogueText(row.dialogue)
  const visual = [
    `${row.shot}，${row.duration}秒`,
    row.shotSize && `景别：${row.shotSize}`,
    row.description && `画面：${row.description}`,
    row.lighting && `光影氛围：${row.lighting}`,
    dialogue && `对白与旁白：${dialogue}`,
    row.soundEffect && `音效：${row.soundEffect}`
  ].filter(Boolean).join('。')
  const motion = [
    `${row.duration}秒连续镜头`,
    row.cameraMovement && `运镜：${row.cameraMovement}`,
    row.description && `动作与画面保持：${row.description}`,
    dialogue && `表演节奏匹配：${dialogue}`,
    row.soundEffect && `声音节奏：${row.soundEffect}`
  ].filter(Boolean).join('。')
  return { finalPrompt: visual, motionPrompt: motion }
}

const PROMPT_SOURCE_FIELDS: ReadonlySet<keyof StudioStoryboardTableRow> = new Set([
  'duration',
  'description',
  'shotSize',
  'lighting',
  'dialogue',
  'soundEffect',
  'cameraMovement'
])

export function patchStudioStoryboardRow(
  row: StudioStoryboardTableRow,
  patch: Partial<StudioStoryboardTableRow>
): StudioStoryboardTableRow {
  const sourceChanged = Object.keys(patch).some((key) => PROMPT_SOURCE_FIELDS.has(key as keyof StudioStoryboardTableRow))
  return sourceChanged
    ? { ...row, ...patch, finalPrompt: '', motionPrompt: '' }
    : { ...row, ...patch }
}

export function storyboardRowsToPrompt(rows: StudioStoryboardTableRow[]): string {
  return rows.map((row, index) => {
    const dialogue = dialogueText(row.dialogue)
    const details = [
      `${index + 1}. ${row.shot}（${row.duration}秒）`,
      row.description && `画面：${row.description}`,
      row.shotSize && `景别：${row.shotSize}`,
      row.lighting && `光影：${row.lighting}`,
      dialogue && `对白旁白：${dialogue}`,
      row.soundEffect && `音效：${row.soundEffect}`,
      row.cameraMovement && `运镜：${row.cameraMovement}`,
      row.finalPrompt && `分镜提示词：${row.finalPrompt}`,
      row.motionPrompt && `视频运动提示词：${row.motionPrompt}`
    ].filter(Boolean)
    return details.join('\n')
  }).join('\n\n')
}

export interface StudioStoryboardProgress {
  confirmedShots: number
  assetCount: number
  promptCount: number
  totalShots: number
  complete: boolean
}

export function studioStoryboardProgress(
  rows: StudioStoryboardTableRow[],
  assets: StudioStoryboardAssetRef[]
): StudioStoryboardProgress {
  const totalShots = rows.length
  const confirmedShots = rows.filter((row) => row.description.trim()).length
  const promptCount = rows.filter((row) => row.finalPrompt.trim() && row.motionPrompt.trim()).length
  return {
    confirmedShots,
    assetCount: new Set(assets.map((asset) => asset.kind)).size,
    promptCount,
    totalShots,
    complete: totalShots > 0 && promptCount === totalShots
  }
}

export function buildStoryboardSynthesisInstruction(
  rows: StudioStoryboardTableRow[],
  assets: StudioStoryboardAssetRef[]
): string {
  const input = rows.map((row) => ({
    id: row.id,
    shot: row.shot,
    duration: row.duration,
    description: row.description,
    shotSize: row.shotSize,
    lighting: row.lighting,
    dialogue: row.dialogue,
    soundEffect: row.soundEffect,
    cameraMovement: row.cameraMovement
  }))
  return [
    '请为以下分镜镜头合成可直接用于分镜图和视频运动生成的专业提示词。',
    '只输出 JSON 对象，不要 Markdown、解释或额外字段；顶层格式必须为 {"shots":[...]}。',
    'shots 每项格式必须为 {"id":"原镜头id","finalPrompt":"分镜画面提示词","motionPrompt":"视频运动提示词"}。',
    `可用资产：${JSON.stringify(assets.map((asset) => ({ kind: asset.kind, title: asset.title })))}`,
    `镜头：${JSON.stringify(input)}`
  ].join('\n')
}

function jsonPayload(raw: string): unknown {
  const trimmed = raw.trim()
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i)?.[1]?.trim()
  const candidate = fenced || trimmed
  const start = candidate.indexOf('{')
  const end = candidate.lastIndexOf('}')
  if (start < 0 || end <= start) throw new Error('模型未返回镜头对象')
  try {
    return JSON.parse(candidate.slice(start, end + 1))
  } catch {
    throw new Error('模型返回格式异常')
  }
}

export function applyStoryboardSynthesisResult(
  rows: StudioStoryboardTableRow[],
  raw: string
): StudioStoryboardTableRow[] {
  const value = jsonPayload(raw)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('模型返回格式异常')
  const root = value as Record<string, unknown>
  if (Object.keys(root).length !== 1 || !Array.isArray(root.shots)) throw new Error('模型返回格式异常')
  const shots = root.shots
  if (shots.length !== rows.length) throw new Error('模型返回的镜头数量不完整')
  const expectedIds = new Set(rows.map((row) => row.id))
  const byId = new Map<string, Pick<StudioStoryboardTableRow, 'finalPrompt' | 'motionPrompt'>>()
  shots.forEach((item) => {
    if (!item || typeof item !== 'object') throw new Error('模型返回格式异常')
    const source = item as Record<string, unknown>
    if (Object.keys(source).length !== 3) throw new Error('模型返回格式异常')
    const id = text(source.id)
    const finalPrompt = text(source.finalPrompt)
    const motionPrompt = text(source.motionPrompt)
    if (!expectedIds.has(id) || byId.has(id) || !finalPrompt || !motionPrompt) {
      throw new Error('模型返回的镜头数据不完整')
    }
    byId.set(id, { finalPrompt, motionPrompt })
  })
  if (byId.size !== rows.length) {
    throw new Error('模型返回的镜头数量不完整')
  }
  return rows.map((row) => ({ ...row, ...byId.get(row.id)! }))
}
