import type {
  StudioCanvasNode,
  StudioGridCellCoord,
  StudioGridStoryboard,
  StudioGridStoryboardCell,
  StudioNodeKind
} from '@/types/studio'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { cropStudioImageGridCells, stitchStudioGridCellUrls } from '@/utils/studio/studioImageGrid'
import {
  cellKey,
  createEmptyGridStoryboard,
  countFilledGridCells
} from '@/utils/studio/studioImageGridSession'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { parseGenerationAspectRatio } from '@/utils/studio/studioImageNode'

export interface StudioGridCellUpload {
  row: number
  col: number
  mediaUrl: string
  resourceId?: string
  mediaAspectRatio: number
  title: string
}

const VIDEO_MEDIA_URL_PATTERN = /\.(?:mp4|webm|mov|m4v|avi|mkv)(?:$|[?#])/i

export function resolveGridStoryboardCellMediaKind(
  cell: Pick<StudioGridStoryboardCell, 'mediaKind' | 'mediaUrl'>
): 'image' | 'video' {
  if (cell.mediaKind === 'video') return 'video'
  return VIDEO_MEDIA_URL_PATTERN.test(cell.mediaUrl?.trim() ?? '') ? 'video' : 'image'
}

export function resolveGridStoryboardNodePosition(source: StudioCanvasNode): { x: number; y: number } {
  const size = studioNodeDimensions(source)
  const desired = {
    x: source.position.x + size.width + 48,
    y: source.position.y
  }
  const occupied = [toStudioNodeRect(source)]
  return placeStudioNodeWithoutOverlap(desired, { width: 320, height: 280 }, occupied)
}

export async function uploadSelectedGridCells(
  sourceUrl: string,
  rows: number,
  columns: number,
  selected: StudioGridCellCoord[],
  fileBaseName: string
): Promise<StudioGridCellUpload[]> {
  const parts = await cropStudioImageGridCells(sourceUrl, {
    rows,
    columns,
    selected,
    fileBaseName
  })
  const uploads: StudioGridCellUpload[] = []
  for (const part of parts) {
    const mediaUrl = await uploadImageToOssWithToast(part.file, () => undefined)
    if (!mediaUrl) continue
    uploads.push({
      row: part.row,
      col: part.col,
      mediaUrl,
      mediaAspectRatio: part.width / part.height,
      title: `${part.row + 1}-${part.col + 1}`
    })
  }
  if (!uploads.length) throw new Error('宫格切片上传失败')
  return uploads
}

export function buildGridStoryboardPayload(input: {
  rows: number
  columns: number
  sourceNode: StudioCanvasNode
  filled: StudioGridCellUpload[]
}): {
  title: string
  subtitle: string
  gridStoryboard: StudioGridStoryboard
} {
  const sourceSize = studioNodeDimensions(input.sourceNode)
  const gridStoryboard = createEmptyGridStoryboard({
    rows: input.rows,
    columns: input.columns,
    aspectRatio: input.sourceNode.data.generationOptions?.aspectRatio ?? 'original',
    sourceNodeId: input.sourceNode.id,
    filled: input.filled.map((item) => ({
      row: item.row,
      col: item.col,
      mediaUrl: item.mediaUrl,
      resourceId: item.resourceId,
      mediaKind: 'image',
      mediaAspectRatio: item.mediaAspectRatio,
      canvasWidth: Math.max(96, Math.round(sourceSize.width / Math.max(1, input.columns))),
      canvasHeight: Math.max(72, Math.round(sourceSize.height / Math.max(1, input.rows))),
      title: item.title
    }))
  })
  const filledCount = input.filled.length
  return {
    title: `宫格分镜组 (${filledCount}张)`,
    subtitle: `${input.rows}×${input.columns} 宫格 · 由 ${input.sourceNode.data.title} 切分`,
    gridStoryboard
  }
}

export function patchGridStoryboardCell(
  storyboard: StudioGridStoryboard,
  row: number,
  col: number,
  patch: Partial<StudioGridStoryboardCell>
): StudioGridStoryboard {
  return {
    ...storyboard,
    cells: storyboard.cells.map((cell) => (
      cell.row === row && cell.col === col ? { ...cell, ...patch } : cell
    ))
  }
}

export function clearGridStoryboardMedia(storyboard: StudioGridStoryboard): StudioGridStoryboard {
  return {
    ...storyboard,
    cells: storyboard.cells.map((cell) => ({
      ...cell,
      mediaUrl: undefined,
      resourceId: undefined,
      mediaKind: undefined,
      mediaAspectRatio: undefined,
      canvasWidth: undefined,
      canvasHeight: undefined,
      status: 'empty' as const
    }))
  }
}

export function filledGridStoryboardCells(storyboard: StudioGridStoryboard) {
  return storyboard.cells.filter((cell) => Boolean(cell.mediaUrl))
}

/** 拖出格槽时保留宫格切分的真实来源；旧工作区缺来源时才兼容回退到分镜组。 */
export function resolveGridStoryboardReferenceSource(
  gridNode: StudioCanvasNode,
  allNodes: StudioCanvasNode[]
): StudioCanvasNode {
  const sourceNodeId = gridNode.data.gridStoryboard?.sourceNodeId
  return allNodes.find((node) => node.id === sourceNodeId) ?? gridNode
}

export async function stitchGridStoryboardToFile(
  storyboard: StudioGridStoryboard,
  fileBaseName: string
): Promise<File> {
  const filled = filledGridStoryboardCells(storyboard).filter(
    (cell) => resolveGridStoryboardCellMediaKind(cell) === 'image'
  )
  if (!filled.length) throw new Error('没有可拼接的图片')
  return stitchStudioGridCellUrls(
    filled.map((cell) => ({ row: cell.row, col: cell.col, mediaUrl: cell.mediaUrl! })),
    storyboard.rows,
    storyboard.columns,
    fileBaseName,
    2048
  )
}

export function spawnGridStoryboardChildNodes(
  node: StudioCanvasNode,
  allNodes: StudioCanvasNode[]
): Array<{
  kind: Extract<StudioNodeKind, 'image' | 'video'>
  position: { x: number; y: number }
  data: Partial<StudioCanvasNode['data']>
}> {
  const storyboard = node.data.gridStoryboard
  if (!storyboard) return []
  const filled = filledGridStoryboardCells(storyboard)
  const gap = 28
  const occupied = allNodes.map((item) => toStudioNodeRect(item))
  const baseY = node.position.y + studioNodeDimensions(node).height + gap
  return filled.map((cell) => {
    const kind = resolveGridStoryboardCellMediaKind(cell)
    const mediaAspectRatio = resolveGridStoryboardCellAspectRatio(node, cell, allNodes)
    const size = resolveGridStoryboardCellCanvasSize(node, cell, allNodes)
    const title = `宫格分镜 ${cell.row + 1}-${cell.col + 1}`
    const desired = {
      x: node.position.x + cell.col * (size.width + gap),
      y: baseY + cell.row * (size.height + gap)
    }
    const position = placeStudioNodeWithoutOverlap(desired, size, occupied)
    occupied.push({ x: position.x, y: position.y, width: size.width, height: size.height })
    return {
      kind,
      position,
      data: {
        title,
        subtitle: `由 ${node.data.title} 解组`,
        zoneId: node.data.zoneId,
        status: 'success' as const,
        mediaKind: kind,
        mediaUrl: cell.mediaUrl,
        ...(cell.resourceId ? { canvasResourceIds: [cell.resourceId], canvasPrimaryResourceId: cell.resourceId } : {}),
        mediaAspectRatio,
        ...(kind === 'image' ? { imagePurpose: 'storyboard' as const } : {}),
        prompt: node.data.prompt,
        storyboardId: node.data.storyboardId,
        resultSummary: `${storyboard.rows}×${storyboard.columns} 第 ${cell.row + 1}-${cell.col + 1} 格`
      }
    }
  })
}

/** 旧工作区没有格槽比例时，按源图比例与行列数确定裁切后的格图比例。 */
export function resolveGridStoryboardCellAspectRatio(
  gridNode: StudioCanvasNode,
  cell: StudioGridStoryboardCell,
  allNodes: StudioCanvasNode[] = []
): number {
  if (typeof cell.mediaAspectRatio === 'number' && Number.isFinite(cell.mediaAspectRatio) && cell.mediaAspectRatio > 0) {
    return cell.mediaAspectRatio
  }
  const storyboard = gridNode.data.gridStoryboard
  const sourceNode = allNodes.find((node) => node.id === storyboard?.sourceNodeId)
  const sourceRatio = sourceNode?.data.mediaAspectRatio
    ?? gridNode.data.mediaAspectRatio
    ?? parseGenerationAspectRatio(sourceNode?.data.generationOptions?.aspectRatio)
    ?? parseGenerationAspectRatio(gridNode.data.generationOptions?.aspectRatio)
    ?? 1
  return storyboard
    ? sourceRatio * Math.max(1, storyboard.rows) / Math.max(1, storyboard.columns)
    : sourceRatio
}

/** 格图尺寸按源节点的可视尺寸除以行列数；旧数据回退到分镜组中实际格槽尺寸。 */
export function resolveGridStoryboardCellCanvasSize(
  gridNode: StudioCanvasNode,
  cell: StudioGridStoryboardCell,
  allNodes: StudioCanvasNode[] = []
): { width: number; height: number } {
  if (
    typeof cell.canvasWidth === 'number'
    && typeof cell.canvasHeight === 'number'
    && Number.isFinite(cell.canvasWidth)
    && Number.isFinite(cell.canvasHeight)
    && cell.canvasWidth > 0
    && cell.canvasHeight > 0
  ) {
    return { width: Math.round(cell.canvasWidth), height: Math.round(cell.canvasHeight) }
  }
  const storyboard = gridNode.data.gridStoryboard
  if (!storyboard) return studioNodeDimensions(gridNode)
  const sourceNode = allNodes.find((node) => node.id === storyboard.sourceNodeId)
  if (sourceNode) {
    const sourceSize = studioNodeDimensions(sourceNode)
    return {
      width: Math.max(96, Math.round(sourceSize.width / Math.max(1, storyboard.columns))),
      height: Math.max(72, Math.round(sourceSize.height / Math.max(1, storyboard.rows)))
    }
  }
  const gridSize = studioNodeDimensions(gridNode)
  const gap = 4
  const padding = 10
  return {
    width: Math.max(96, Math.round((gridSize.width - padding * 2 - gap * (storyboard.columns - 1)) / storyboard.columns)),
    height: Math.max(72, Math.round((gridSize.height - padding * 2 - gap * (storyboard.rows - 1)) / storyboard.rows))
  }
}

export function gridStoryboardFilledCount(storyboard: StudioGridStoryboard | undefined): number {
  if (!storyboard) return 0
  return countFilledGridCells(storyboard)
}

export function sortGridCells(cells: StudioGridCellCoord[]): StudioGridCellCoord[] {
  return [...cells].sort((a, b) => (a.row - b.row) || (a.col - b.col))
}

export function uniqueGridCells(cells: StudioGridCellCoord[]): StudioGridCellCoord[] {
  const map = new Map(cells.map((cell) => [cellKey(cell), cell]))
  return [...map.values()]
}
