import type { CanvasApiId, CanvasId } from '@/types/canvas-api'

export function normalizeCanvasId(value: CanvasApiId | null | undefined): CanvasId {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('画布编号超出安全整数范围')
  const id = String(value ?? '').trim()
  if (!/^\d+$/.test(id) || id === '0') throw new Error('画布编号无效')
  return id
}

export function isCanvasId(value: unknown): value is CanvasApiId {
  return typeof value === 'number'
    ? Number.isSafeInteger(value) && value > 0
    : typeof value === 'string' && /^\d+$/.test(value.trim()) && value.trim() !== '0'
}

export function canvasOperationId(prefix: string): string {
  const safePrefix = prefix.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 18) || 'canvas'
  const random = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID().replace(/-/g, '')
    : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`
  return `${safePrefix}_${random}`.slice(0, 64)
}
