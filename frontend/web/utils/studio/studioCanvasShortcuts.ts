import type { StudioMode } from '@/types/studio'

/** React Flow 画布手势使用的按键，和快捷键面板共用，避免两处各写一套。 */
export const STUDIO_CANVAS_PAN_KEY = 'Space'
export const STUDIO_CANVAS_SELECTION_KEY = 'Shift'
export const STUDIO_CANVAS_ZOOM_KEY = 'Control'

export type StudioCanvasShortcutAction =
  | 'temporary-pan'
  | 'dismiss'
  | 'undo'
  | 'redo'
  | 'copy'
  | 'paste'
  | 'duplicate'
  | 'delete'
  | 'zoom-in'
  | 'zoom-out'
  | 'edge-flow'
  | 'edge-reference'
  | 'edge-sequence'

type ShortcutEvent = {
  key: string
  code: string
  ctrlKey: boolean
  metaKey: boolean
  shiftKey: boolean
}

export function resolveStudioCanvasShortcut(event: ShortcutEvent): StudioCanvasShortcutAction | null {
  if (event.code === STUDIO_CANVAS_PAN_KEY) return 'temporary-pan'
  if (event.key === 'Escape') return 'dismiss'
  const command = event.ctrlKey || event.metaKey
  const key = event.key.toLowerCase()
  if (command && (key === 'z' || key === 'y')) {
    return key === 'y' || event.shiftKey ? 'redo' : 'undo'
  }
  if (command && key === 'c') return 'copy'
  if (command && key === 'v') return 'paste'
  if (command && key === 'd') return 'duplicate'
  if (event.key === 'Delete' || event.key === 'Backspace') return 'delete'
  if (event.key === '+' || event.key === '=') return 'zoom-in'
  if (event.key === '-' || event.key === '_') return 'zoom-out'
  if (event.key === '1') return 'edge-flow'
  if (event.key === '2') return 'edge-reference'
  if (event.key === '3') return 'edge-sequence'
  return null
}

export type StudioCanvasShortcutToken =
  | { kind: 'command' }
  | { kind: 'text'; text: string }
  | { kind: 'wheel' }
  | { kind: 'pinch' }
  | { kind: 'pointer' }
  | { kind: 'trackpad' }

export interface StudioCanvasShortcutItem {
  id: string
  label: string
  group: 'create' | 'zoom' | 'move' | 'other'
  chords: StudioCanvasShortcutToken[][]
  modes?: StudioMode[]
  hint?: string
  hintOrder?: number
}

const ITEMS: StudioCanvasShortcutItem[] = [
  {
    id: 'copy',
    label: '复制节点',
    group: 'create',
    chords: [[{ kind: 'command' }, { kind: 'text', text: 'C' }]]
  },
  {
    id: 'paste',
    label: '粘贴',
    group: 'create',
    chords: [[{ kind: 'command' }, { kind: 'text', text: 'V' }]]
  },
  {
    id: 'duplicate',
    label: '创建副本',
    group: 'create',
    chords: [[{ kind: 'command' }, { kind: 'text', text: 'D' }]]
  },
  {
    id: 'add',
    label: '新建节点',
    group: 'create',
    modes: ['free'],
    chords: [[{ kind: 'text', text: '双击空白处' }]]
  },
  {
    id: 'edge-flow',
    label: '流程连线',
    group: 'create',
    chords: [[{ kind: 'text', text: '1' }]]
  },
  {
    id: 'edge-reference',
    label: '引用连线',
    group: 'create',
    chords: [[{ kind: 'text', text: '2' }]]
  },
  {
    id: 'edge-sequence',
    label: '镜头顺序',
    group: 'create',
    chords: [[{ kind: 'text', text: '3' }]]
  },
  {
    id: 'zoom-in',
    label: '放大',
    group: 'zoom',
    hint: '+/- 缩放',
    hintOrder: 6,
    chords: [[{ kind: 'text', text: '+' }]]
  },
  {
    id: 'zoom-out',
    label: '缩小',
    group: 'zoom',
    chords: [[{ kind: 'text', text: '−' }]]
  },
  {
    id: 'zoom-wheel',
    label: '滚轮缩放',
    group: 'zoom',
    hint: 'Ctrl+滚轮缩放',
    hintOrder: 5,
    chords: [[{ kind: 'text', text: 'Ctrl' }, { kind: 'wheel' }]]
  },
  {
    id: 'zoom-pinch',
    label: '触控板缩放',
    group: 'zoom',
    chords: [[{ kind: 'pinch' }]]
  },
  {
    id: 'temporary-pan',
    label: '临时抓手',
    group: 'move',
    hint: 'Space 临时抓手',
    hintOrder: 2,
    chords: [[{ kind: 'text', text: 'Space' }]]
  },
  {
    id: 'middle-pan',
    label: '中键平移',
    group: 'move',
    chords: [[{ kind: 'pointer' }]]
  },
  {
    id: 'wheel-pan',
    label: '滚轮上下平移',
    group: 'move',
    hint: '滚轮上下平移',
    hintOrder: 4,
    chords: [[{ kind: 'wheel' }]]
  },
  {
    id: 'trackpad-pan',
    label: '触控板上下滑动',
    group: 'move',
    chords: [[{ kind: 'trackpad' }]]
  },
  {
    id: 'marquee',
    label: '框选',
    group: 'move',
    hint: '选择工具拖拽框选',
    hintOrder: 1,
    chords: [[{ kind: 'text', text: '拖拽' }]]
  },
  {
    id: 'undo',
    label: '撤销',
    group: 'other',
    chords: [[{ kind: 'command' }, { kind: 'text', text: 'Z' }]]
  },
  {
    id: 'redo',
    label: '重做',
    group: 'other',
    chords: [
      [{ kind: 'command' }, { kind: 'text', text: 'Shift' }, { kind: 'text', text: 'Z' }],
      [{ kind: 'command' }, { kind: 'text', text: 'Y' }]
    ]
  },
  {
    id: 'delete',
    label: '删除',
    group: 'other',
    chords: [[{ kind: 'text', text: 'Delete' }], [{ kind: 'text', text: 'Backspace' }]]
  },
  {
    id: 'append-select',
    label: '追加框选',
    group: 'other',
    hint: 'Shift 追加框选',
    hintOrder: 3,
    chords: [[{ kind: 'text', text: 'Shift' }]]
  },
  {
    id: 'dismiss',
    label: '取消选择',
    group: 'other',
    chords: [[{ kind: 'text', text: 'Esc' }]]
  }
]

const GROUPS = [
  { id: 'create', title: '创作' },
  { id: 'zoom', title: '缩放' },
  { id: 'move', title: '移动画布' },
  { id: 'other', title: '其他' }
] as const

export function studioCanvasShortcutPlatform(): 'mac' | 'win' {
  if (typeof navigator === 'undefined') return 'win'
  return /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent) ? 'mac' : 'win'
}

export function studioCanvasShortcutSections(mode: StudioMode) {
  return GROUPS.map((group) => ({
    ...group,
    items: ITEMS.filter((item) => item.group === group.id && (!item.modes || item.modes.includes(mode)))
  })).filter((group) => group.items.length > 0)
}

export function studioCanvasShortcutHints(): string[] {
  return ITEMS
    .flatMap((item) => item.hint ? [{ hint: item.hint, order: item.hintOrder ?? 0 }] : [])
    .sort((left, right) => left.order - right.order)
    .map((item) => item.hint)
}

export function studioCanvasShortcutCommandLabel(platform: 'mac' | 'win'): string {
  return platform === 'mac' ? '⌘' : 'Ctrl'
}
