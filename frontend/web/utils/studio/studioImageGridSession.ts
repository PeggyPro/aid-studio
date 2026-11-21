import type { StudioGridCellCoord, StudioGridStoryboard, StudioGridStoryboardCell } from '@/types/studio'
import { studioId } from '@/utils/studio/studioGraph'

export function cellKey(cell: StudioGridCellCoord): string {
  return `${cell.row}:${cell.col}`
}

export function parseCellKey(key: string): StudioGridCellCoord | null {
  const match = key.match(/^(\d+):(\d+)$/)
  if (!match) return null
  return { row: Number(match[1]), col: Number(match[2]) }
}

export function cellsEqual(a: StudioGridCellCoord, b: StudioGridCellCoord): boolean {
  return a.row === b.row && a.col === b.col
}

export function toggleCellSelection(
  selected: StudioGridCellCoord[],
  cell: StudioGridCellCoord,
  additive: boolean
): StudioGridCellCoord[] {
  const exists = selected.some((item) => cellsEqual(item, cell))
  if (additive) {
    if (exists) return selected.filter((item) => !cellsEqual(item, cell))
    return [...selected, cell]
  }
  if (exists && selected.length === 1) return []
  return [cell]
}

/** 矩形拖选覆盖的格子（含起止）。 */
export function cellsInRect(
  start: StudioGridCellCoord,
  end: StudioGridCellCoord,
  rows: number,
  columns: number
): StudioGridCellCoord[] {
  const r0 = Math.max(0, Math.min(start.row, end.row, rows - 1))
  const r1 = Math.min(rows - 1, Math.max(start.row, end.row, 0))
  const c0 = Math.max(0, Math.min(start.col, end.col, columns - 1))
  const c1 = Math.min(columns - 1, Math.max(start.col, end.col, 0))
  const next: StudioGridCellCoord[] = []
  for (let row = r0; row <= r1; row += 1) {
    for (let col = c0; col <= c1; col += 1) {
      next.push({ row, col })
    }
  }
  return next
}

export function mergeCellSelection(
  base: StudioGridCellCoord[],
  rectCells: StudioGridCellCoord[],
  additive: boolean
): StudioGridCellCoord[] {
  if (!additive) return rectCells
  const map = new Map(base.map((item) => [cellKey(item), item]))
  for (const cell of rectCells) map.set(cellKey(cell), cell)
  return [...map.values()]
}

export function pointerToGridCell(
  localX: number,
  localY: number,
  width: number,
  height: number,
  rows: number,
  columns: number
): StudioGridCellCoord | null {
  if (width < 1 || height < 1 || rows < 1 || columns < 1) return null
  const col = Math.min(columns - 1, Math.max(0, Math.floor((localX / width) * columns)))
  const row = Math.min(rows - 1, Math.max(0, Math.floor((localY / height) * rows)))
  return { row, col }
}

export function createEmptyGridStoryboard(options: {
  rows: number
  columns: number
  aspectRatio?: string
  sourceNodeId?: string
  filled?: Array<{
    row: number
    col: number
    mediaUrl: string
    resourceId?: string
    mediaKind?: StudioGridStoryboardCell['mediaKind']
    mediaAspectRatio?: number
    canvasWidth?: number
    canvasHeight?: number
    title?: string
  }>
}): StudioGridStoryboard {
  const rows = Math.max(1, options.rows)
  const columns = Math.max(1, options.columns)
  const filledMap = new Map(
    (options.filled ?? []).map((item) => [cellKey(item), item])
  )
  const cells: StudioGridStoryboardCell[] = []
  for (let row = 0; row < rows; row += 1) {
    for (let col = 0; col < columns; col += 1) {
      const hit = filledMap.get(cellKey({ row, col }))
      cells.push({
        id: studioId('grid-cell'),
        row,
        col,
        mediaUrl: hit?.mediaUrl,
        resourceId: hit?.resourceId,
        mediaKind: hit?.mediaKind,
        mediaAspectRatio: hit?.mediaAspectRatio,
        canvasWidth: hit?.canvasWidth,
        canvasHeight: hit?.canvasHeight,
        title: hit?.title ?? `${row + 1}-${col + 1}`,
        status: hit?.mediaUrl ? 'success' : 'empty'
      })
    }
  }
  return {
    rows,
    columns,
    aspectRatio: options.aspectRatio ?? 'original',
    showSerial: false,
    cells,
    sourceNodeId: options.sourceNodeId
  }
}

export function countFilledGridCells(storyboard: StudioGridStoryboard): number {
  return storyboard.cells.filter((cell) => Boolean(cell.mediaUrl)).length
}

export function resizeGridStoryboard(
  current: StudioGridStoryboard,
  rows: number,
  columns: number
): StudioGridStoryboard {
  const prev = new Map(current.cells.map((cell) => [cellKey(cell), cell]))
  const cells: StudioGridStoryboardCell[] = []
  for (let row = 0; row < rows; row += 1) {
    for (let col = 0; col < columns; col += 1) {
      const hit = prev.get(cellKey({ row, col }))
      cells.push({
        id: hit?.id ?? studioId('grid-cell'),
        row,
        col,
        mediaUrl: hit?.mediaUrl,
        resourceId: hit?.resourceId,
        mediaKind: hit?.mediaKind,
        mediaAspectRatio: hit?.mediaAspectRatio,
        canvasWidth: hit?.canvasWidth,
        canvasHeight: hit?.canvasHeight,
        title: hit?.title ?? `${row + 1}-${col + 1}`,
        status: hit?.mediaUrl ? (hit.status ?? 'success') : 'empty'
      })
    }
  }
  return { ...current, rows, columns, cells }
}

export { STUDIO_GRID_MAX_EDGE, STUDIO_GRID_MAX_PARTS } from '@/utils/studio/studioImageGrid'

export const STUDIO_GRID_PRESETS = [
  { key: '2x2', rows: 2, columns: 2, label: '2x2' },
  { key: '3x3', rows: 3, columns: 3, label: '3x3' },
  { key: '4x4', rows: 4, columns: 4, label: '4x4' },
  { key: '5x5', rows: 5, columns: 5, label: '5x5' }
] as const
