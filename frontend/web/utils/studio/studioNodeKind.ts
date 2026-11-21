import type { StudioNodeKind } from '@/types/studio'

export type StudioMaterialNodeKind = 'official_asset' | 'style_library'

export function isStudioMaterialNodeKind(kind: StudioNodeKind): kind is StudioMaterialNodeKind {
  return kind === 'official_asset' || kind === 'style_library'
}

export interface StudioAddMenuItem {
  kind: StudioNodeKind
  label: string
}

export interface StudioAddMenuGroup {
  id: 'library'
  label: '素材库'
  description: string
  children: StudioAddMenuItem[]
}

export type StudioAddMenuEntry = StudioAddMenuItem | StudioAddMenuGroup

/** Full node inventory, including types that have dedicated creation workflows. */
export const STUDIO_ADD_MENU: StudioAddMenuEntry[] = [
  { kind: 'image', label: '图片' },
  { kind: 'video', label: '视频' },
  { kind: 'frame_breakdown', label: '逐帧拉片' },
  { kind: 'smart_edit', label: '剪辑成片' },
  { kind: 'text', label: '文本' },
  { kind: 'storyboard_script', label: '分镜脚本' },
  { kind: 'director', label: '3D预演' },
  {
    id: 'library',
    label: '素材库',
    description: '风格与官方创作素材',
    children: [
      { kind: 'style_library', label: '风格库' },
      { kind: 'official_asset', label: '官方素材' }
    ]
  },
  { kind: 'voice', label: '音频' },
  { kind: 'note', label: '备注' }
]

export function isStudioAddMenuGroup(entry: StudioAddMenuEntry): entry is StudioAddMenuGroup {
  return 'children' in entry
}

export const STUDIO_NODE_KIND_ORDER = STUDIO_ADD_MENU.flatMap((entry) =>
  isStudioAddMenuGroup(entry) ? entry.children.map(({ kind }) => kind) : [entry.kind]
)

export function studioShortcutLabel(action: 'paste' | 'undo', platform: 'mac' | 'win'): string {
  const key = action === 'paste' ? 'V' : 'Z'
  return platform === 'mac' ? `⌘${key}` : `Ctrl+${key}`
}

export function defaultZoneByKind(kind: StudioNodeKind): string {
  const zones: Record<StudioNodeKind, string> = {
    image: 'scene-character',
    video: 'storyboard-video',
    frame_breakdown: 'storyboard-video',
    smart_edit: 'storyboard-video',
    text: 'story-script',
    storyboard_script: 'storyboard-script',
    director: 'storyboard-script',
    style_library: 'scene-character',
    official_asset: 'scene-character',
    voice: 'dubbing',
    note: 'global-setting'
  }
  return zones[kind]
}
