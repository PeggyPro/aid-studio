import { STUDIO_NODE_META } from '@/config/studioMeta'
import type { StudioCanvasNode, StudioMediaKind } from '@/types/studio'
import {
  studioNodeToComposerRef,
  studioReferencePickDisabledReason
} from '@/utils/studio/studioReferencePicker'

const TOKEN_END = /[\s，。、；：！？,.;:!?(\[{'"\u3000@]/

const DEFAULT_TITLES = new Set([
  STUDIO_NODE_META.image.label,
  STUDIO_NODE_META.video.label
])

export function isStudioAutoLinkEnabled(
  options: { autoLink?: boolean } | undefined
): boolean {
  return options?.autoLink !== false
}

export type StudioAutoLinkCandidate = {
  nodeId: string
  title: string
  mediaKind: 'image' | 'video'
  previewUrl?: string
  x: number
  y: number
  width: number
  height: number
  updatedAt: string
}

export type StudioAutoLinkApplyInput = {
  prompt: string
  targetNodeId: string
  targetKind: 'image' | 'video'
  targetCenter: { x: number; y: number }
  candidates: StudioAutoLinkCandidate[]
  /** 仅用于判断哪些 ref 已存在；改写不删旧 ref */
  existingRefIds?: ReadonlySet<string>
  /** 已有同源引用则不再产出该 node 的 ref（防 AutoLink 狂刷） */
  existingSourceKeys?: ReadonlySet<string>
}

export type StudioAutoLinkApplyResult = {
  prompt: string
  refs: Array<{
    id: string
    kind: 'node'
    nodeId: string
    title: string
    mediaKind: 'image' | 'video'
    previewUrl?: string
    sourceKey: string
  }>
  changed: boolean
}

type HitRange = {
  start: number
  end: number
  title: string
  nodeId: string
  mediaKind: 'image' | 'video'
  previewUrl?: string
}

function nodeSize(node: StudioCanvasNode): { width: number; height: number } {
  return {
    width: node.measured?.width ?? node.width ?? 280,
    height: node.measured?.height ?? node.height ?? 196
  }
}

function centerOf(c: { x: number; y: number; width: number; height: number }) {
  return { x: c.x + c.width / 2, y: c.y + c.height / 2 }
}

function distance(a: { x: number; y: number }, b: { x: number; y: number }) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function rangesOverlap(
  a: { start: number; end: number },
  b: { start: number; end: number }
) {
  return a.start < b.end && b.start < a.end
}

/** Existing explicit references are protected at every position, including CJK text. */
export function findStudioAutoLinkProtectedRanges(
  prompt: string
): Array<{ start: number; end: number }> {
  const ranges: Array<{ start: number; end: number }> = []
  for (let i = 0; i < prompt.length; i += 1) {
    if (prompt[i] !== '@') continue
    let end = i + 1
    while (end < prompt.length) {
      const ch = prompt[end]!
      if (TOKEN_END.test(ch) || ch === '\n') break
      end += 1
    }
    if (end > i + 1) ranges.push({ start: i, end })
    i = Math.max(i, end - 1)
  }
  return ranges
}

function resolveUniqueCandidates(
  candidates: StudioAutoLinkCandidate[],
  targetCenter: { x: number; y: number }
): StudioAutoLinkCandidate[] {
  const byTitle = new Map<string, StudioAutoLinkCandidate[]>()
  for (const c of candidates) {
    const title = c.title.trim()
    if (title.length < 2 || DEFAULT_TITLES.has(title)) continue
    if (!c.previewUrl?.trim()) continue
    const list = byTitle.get(title) ?? []
    list.push({ ...c, title })
    byTitle.set(title, list)
  }
  const resolved: StudioAutoLinkCandidate[] = []
  for (const list of byTitle.values()) {
    list.sort((a, b) => {
      const da = distance(targetCenter, centerOf(a))
      const db = distance(targetCenter, centerOf(b))
      if (da !== db) return da - db
      return b.updatedAt.localeCompare(a.updatedAt)
    })
    resolved.push(list[0]!)
  }
  return resolved
}

function toRef(hit: HitRange): StudioAutoLinkApplyResult['refs'][number] {
  return {
    id: `node:${hit.nodeId}`,
    kind: 'node',
    nodeId: hit.nodeId,
    title: hit.title,
    mediaKind: hit.mediaKind,
    previewUrl: hit.previewUrl,
    sourceKey: `node:${hit.nodeId}`
  }
}

export function applyStudioAutoLink(
  input: StudioAutoLinkApplyInput
): StudioAutoLinkApplyResult {
  const unique = resolveUniqueCandidates(input.candidates, input.targetCenter)
  const protectedRanges = findStudioAutoLinkProtectedRanges(input.prompt)
  const titles = [...unique].sort((a, b) => b.title.length - a.title.length)
  const taken: HitRange[] = []

  for (const c of titles) {
    const { title } = c
    let from = 0
    while (from <= input.prompt.length - title.length) {
      const start = input.prompt.indexOf(title, from)
      if (start < 0) break
      const end = start + title.length
      const range = { start, end }
      const blocked = protectedRanges.some((p) => rangesOverlap(range, p))
        || taken.some((t) => rangesOverlap(range, t))
      if (!blocked) {
        taken.push({
          start,
          end,
          title,
          nodeId: c.nodeId,
          mediaKind: c.mediaKind,
          previewUrl: c.previewUrl
        })
      }
      from = start + 1
    }
  }

  taken.sort((a, b) => a.start - b.start)
  let cursor = 0
  let out = ''
  for (const hit of taken) {
    out += input.prompt.slice(cursor, hit.start)
    const afterChar = input.prompt[hit.end]
    const token = `@${hit.title}`
    out += afterChar && !/\s/.test(afterChar) ? `${token} ` : token
    cursor = hit.end
  }
  out += input.prompt.slice(cursor)

  const refsByNode = new Map<string, ReturnType<typeof toRef>>()
  for (const hit of taken) {
    if (!refsByNode.has(hit.nodeId)) refsByNode.set(hit.nodeId, toRef(hit))
  }
  let refs = [...refsByNode.values()]
  if (input.existingSourceKeys?.size) {
    refs = refs.filter((item) => !input.existingSourceKeys!.has(item.sourceKey))
  }

  if (out === input.prompt) {
    if (!input.existingRefIds && !input.existingSourceKeys) {
      return { prompt: out, refs: [], changed: false }
    }
    const missing = refs.filter((r) => {
      if (input.existingSourceKeys?.has(r.sourceKey)) return false
      if (input.existingRefIds?.has(r.id)) return false
      // 兼容旧单例 id 与实例 id：只要同源已有就不补
      if (input.existingSourceKeys) return true
      return !input.existingRefIds?.has(r.id)
    })
    return { prompt: out, refs: missing, changed: missing.length > 0 }
  }

  return { prompt: out, refs, changed: true }
}

function asImageOrVideoMediaKind(kind: StudioMediaKind): 'image' | 'video' {
  return kind === 'video' ? 'video' : 'image'
}

export function collectStudioAutoLinkCandidates(args: {
  target: Pick<StudioCanvasNode, 'id' | 'data'>
  nodes: StudioCanvasNode[]
}): StudioAutoLinkCandidate[] {
  const out: StudioAutoLinkCandidate[] = []
  for (const node of args.nodes) {
    if (node.id === args.target.id) continue
    if (node.data.kind !== 'image' && node.data.kind !== 'video') continue
    if (studioReferencePickDisabledReason(args.target as StudioCanvasNode, node)) continue
    if (!node.data.mediaUrl?.trim()) continue
    const title = node.data.title?.trim() ?? ''
    if (title.length < 2 || DEFAULT_TITLES.has(title)) continue
    const size = nodeSize(node)
    const ref = studioNodeToComposerRef(node)
    out.push({
      nodeId: node.id,
      title,
      mediaKind: asImageOrVideoMediaKind(ref.mediaKind),
      previewUrl: ref.previewUrl,
      x: node.position.x,
      y: node.position.y,
      width: size.width,
      height: size.height,
      updatedAt: node.data.updatedAt
    })
  }
  return out
}
