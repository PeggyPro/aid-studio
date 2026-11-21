'use client'

import { fetchMediaBlob } from '@/utils/mediaFetch'
import type { StudioGridCellCoord } from '@/types/studio'

export interface StudioImageGridOptions {
  rows: number
  columns: number
  fileBaseName: string
}

export const STUDIO_GRID_MAX_EDGE = 5
export const STUDIO_GRID_MAX_PARTS = 25

const MAX_SOURCE_DIMENSION = 16_384
const MAX_SOURCE_PIXELS = 64_000_000

interface StudioGridImageSource {
  drawable: CanvasImageSource
  width: number
  height: number
  dispose: () => void
}

export interface StudioGridCellFile {
  row: number
  col: number
  width: number
  height: number
  file: File
}

/** 浏览器内确定性裁切，不调用 AI、不创建任务。 */
export async function splitStudioImageGrid(
  sourceUrl: string,
  options: StudioImageGridOptions
): Promise<File[]> {
  const parts = await cropStudioImageGridCells(sourceUrl, {
    rows: options.rows,
    columns: options.columns,
    fileBaseName: options.fileBaseName
  })
  return parts.map((item) => item.file)
}

/** 按选中格坐标裁切；未传 selected 时切满整表。 */
export async function cropStudioImageGridCells(
  sourceUrl: string,
  options: StudioImageGridOptions & { selected?: StudioGridCellCoord[] }
): Promise<StudioGridCellFile[]> {
  const rows = Math.max(1, Math.floor(options.rows))
  const columns = Math.max(1, Math.floor(options.columns))
  if (rows * columns > STUDIO_GRID_MAX_PARTS) {
    throw new Error(`单次最多切分 ${STUDIO_GRID_MAX_PARTS} 张图片`)
  }
  const selected = options.selected?.length
    ? options.selected
    : Array.from({ length: rows * columns }, (_, index) => ({
        row: Math.floor(index / columns),
        col: index % columns
      }))
  if (selected.length > STUDIO_GRID_MAX_PARTS) {
    throw new Error(`单次最多切分 ${STUDIO_GRID_MAX_PARTS} 张图片`)
  }

  const blob = await fetchMediaBlob(sourceUrl)
  if (!blob) throw new Error('图片读取失败，请检查媒体代理或 CDN 跨域配置')

  const source = await decodeStudioGridImage(blob)
  try {
    validateStudioGridImageSize(source.width, source.height)
    const files: StudioGridCellFile[] = []
    for (const cell of selected) {
      const row = Math.max(0, Math.min(rows - 1, Math.floor(cell.row)))
      const col = Math.max(0, Math.min(columns - 1, Math.floor(cell.col)))
      const sourceTop = Math.round((row * source.height) / rows)
      const sourceBottom = Math.round(((row + 1) * source.height) / rows)
      const sourceLeft = Math.round((col * source.width) / columns)
      const sourceRight = Math.round(((col + 1) * source.width) / columns)
      const width = Math.max(1, sourceRight - sourceLeft)
      const height = Math.max(1, sourceBottom - sourceTop)
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d', { alpha: false })
      if (!context) throw new Error('浏览器无法创建图片裁切画布')
      context.drawImage(source.drawable, sourceLeft, sourceTop, width, height, 0, 0, width, height)
      const part = await canvasToBlob(canvas)
      files.push({
        row,
        col,
        width,
        height,
        file: new File(
          [part],
          `${safeFileBase(options.fileBaseName)}-${row + 1}-${col + 1}.png`,
          { type: 'image/png' }
        )
      })
    }
    return files
  } finally {
    source.dispose()
  }
}

/** 将多张格图按行列拼成一张（最长边约 targetMaxEdge）。 */
export async function stitchStudioGridCellUrls(
  cells: Array<{ row: number; col: number; mediaUrl: string }>,
  rows: number,
  columns: number,
  fileBaseName: string,
  targetMaxEdge = 2048
): Promise<File> {
  if (!cells.length) throw new Error('没有可拼接的图片')
  const decoded = await Promise.all(cells.map(async (cell) => {
    const blob = await fetchMediaBlob(cell.mediaUrl)
    if (!blob) throw new Error('拼接图片读取失败')
    const source = await decodeStudioGridImage(blob)
    return { ...cell, source }
  }))
  try {
    const cellW = Math.max(...decoded.map((item) => item.source.width))
    const cellH = Math.max(...decoded.map((item) => item.source.height))
    let outW = cellW * columns
    let outH = cellH * rows
    const scale = Math.min(1, targetMaxEdge / Math.max(outW, outH))
    outW = Math.max(1, Math.round(outW * scale))
    outH = Math.max(1, Math.round(outH * scale))
    const slotW = Math.max(1, Math.round(outW / columns))
    const slotH = Math.max(1, Math.round(outH / rows))
    const canvas = document.createElement('canvas')
    canvas.width = outW
    canvas.height = outH
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法创建拼接画布')
    ctx.fillStyle = '#111318'
    ctx.fillRect(0, 0, outW, outH)
    for (const item of decoded) {
      ctx.drawImage(
        item.source.drawable,
        item.col * slotW,
        item.row * slotH,
        slotW,
        slotH
      )
    }
    const blob = await canvasToBlob(canvas)
    return new File([blob], `${safeFileBase(fileBaseName)}-stitch.png`, { type: 'image/png' })
  } finally {
    for (const item of decoded) item.source.dispose()
  }
}

async function decodeStudioGridImage(blob: Blob): Promise<StudioGridImageSource> {
  if (typeof window !== 'undefined' && typeof window.createImageBitmap === 'function') {
    try {
      const bitmap = await window.createImageBitmap(blob)
      return {
        drawable: bitmap,
        width: bitmap.width,
        height: bitmap.height,
        dispose: () => bitmap.close()
      }
    } catch {
      // Safari 旧版本和部分特殊图片格式可能暴露 API 但解码失败，继续使用 img 回退。
    }
  }
  if (typeof document === 'undefined' || typeof URL === 'undefined') {
    throw new Error('当前环境不支持浏览器图片解码')
  }
  const objectUrl = URL.createObjectURL(blob)
  const image = document.createElement('img')
  try {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('图片格式无法解码'))
      image.src = objectUrl
    })
    return {
      drawable: image,
      width: image.naturalWidth,
      height: image.naturalHeight,
      dispose: () => URL.revokeObjectURL(objectUrl)
    }
  } catch {
    URL.revokeObjectURL(objectUrl)
    throw new Error('浏览器无法解码该图片，请转换为 JPEG、PNG 或 WebP 后重试')
  }
}

function validateStudioGridImageSize(width: number, height: number): void {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 1 || height < 1) {
    throw new Error('图片尺寸无效')
  }
  if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION || width * height > MAX_SOURCE_PIXELS) {
    throw new Error(`图片尺寸过大，最长边不能超过 ${MAX_SOURCE_DIMENSION}px，且总像素不能超过 ${MAX_SOURCE_PIXELS / 1_000_000}MP`)
  }
}

function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob?.size) resolve(blob)
      else reject(new Error('图片裁切结果为空'))
    }, 'image/png')
  })
}

function safeFileBase(value: string): string {
  return (value.trim() || '宫格切片').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
}
