import type { StudioCanvasNode, StudioMode, StudioTemplate } from '@/types/studio'

const FLOW_COLUMN_X = [405, 778, 1438, 1732, 2100, 2400, 2730]
const FREE_COLUMN_GAP = 344
const ROW_GAP = 226

function semanticRank(node: StudioTemplate['nodes'][number]): number {
  if (node.kind === 'storyboard_script' || node.kind === 'text') return 0
  if (node.kind === 'style_library' || node.kind === 'official_asset') return 1
  if (node.kind === 'image') {
    if (node.imagePurpose === 'storyboard') return 4
    if (node.imagePurpose === 'reference') return 3
    return 1
  }
  if (node.kind === 'director') return 2
  if (node.kind === 'video') return 5
  if (node.kind === 'voice') return 6
  return 1
}

/** 先按创作语义定层，再用有向依赖把后继推到前驱右侧，保证流程只向前推进。 */
export function layoutStudioPipelineTemplate(
  template: StudioTemplate,
  mode: StudioMode
): StudioTemplate {
  const ranks = template.nodes.map(semanticRank)
  for (let pass = 0; pass < template.nodes.length; pass += 1) {
    let changed = false
    for (const edge of template.edges) {
      const nextRank = ranks[edge.sourceIndex] + 1
      if (nextRank <= ranks[edge.targetIndex]) continue
      ranks[edge.targetIndex] = nextRank
      changed = true
    }
    if (!changed) break
  }

  const rowsByRank = new Map<number, number[]>()
  ranks.forEach((rank, index) => rowsByRank.set(rank, [...(rowsByRank.get(rank) ?? []), index]))
  const nodes = template.nodes.map((node, index) => {
    const rank = ranks[index]
    const peers = rowsByRank.get(rank) ?? [index]
    const row = peers.indexOf(index)
    const centeredY = 164 + row * ROW_GAP - ((peers.length - 1) * ROW_GAP) / 2
    return {
      ...node,
      position: {
        x: mode === 'flow'
          ? FLOW_COLUMN_X[Math.min(rank, FLOW_COLUMN_X.length - 1)] + Math.max(0, rank - FLOW_COLUMN_X.length + 1) * FREE_COLUMN_GAP
          : 80 + rank * FREE_COLUMN_GAP,
        y: Math.max(52, centeredY)
      }
    }
  })
  return { ...template, nodes }
}

export function studioPipelineAppendOffset(
  existing: StudioCanvasNode[],
  template: StudioTemplate
): { x: number; y: number } {
  if (!existing.length || !template.nodes.length) return { x: 0, y: 0 }
  const existingRight = Math.max(...existing.map((node) => node.position.x + 292))
  const templateLeft = Math.min(...template.nodes.map((node) => node.position.x))
  return { x: existingRight + 160 - templateLeft, y: 0 }
}
