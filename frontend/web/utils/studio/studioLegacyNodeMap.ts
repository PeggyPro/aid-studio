import type { StudioImagePurpose, StudioNodeKind } from '@/types/studio'

export function mapLegacyStudioNodeKind(
  kind: string,
  mediaKind?: string
): { kind: StudioNodeKind; imagePurpose?: StudioImagePurpose } {
  switch (kind) {
    case 'role': return { kind: 'image', imagePurpose: 'character' }
    case 'scene': return { kind: 'image', imagePurpose: 'scene' }
    case 'prop': return { kind: 'image', imagePurpose: 'prop' }
    case 'unit': return { kind: 'image', imagePurpose: 'storyboard' }
    case 'ref_image': return { kind: 'image', imagePurpose: 'reference' }
    case 'script': return { kind: 'storyboard_script' }
    case 'result':
      return mediaKind === 'video'
        ? { kind: 'video' }
        : { kind: 'image', imagePurpose: 'generic' }
    case 'image':
    case 'video':
    case 'text':
    case 'storyboard_script':
    case 'director':
    case 'style_library':
    case 'official_asset':
    case 'voice':
    case 'note':
      return { kind }
    default:
      return { kind: 'note' }
  }
}

type MigratableWorkspace = {
  version?: number
  nodes: Array<{ data: Record<string, unknown> }>
  savedAssets?: Array<{ kind?: unknown; mediaKind?: unknown }>
}

export function migrateStudioWorkspaceData<T extends MigratableWorkspace>(workspace: T): T {
  if (workspace.version === 3) return workspace
  if (workspace.version === 2) return { ...workspace, version: 3 } as T
  return {
    ...workspace,
    version: 3,
    nodes: workspace.nodes.map((node) => {
      const mapped = mapLegacyStudioNodeKind(
        String(node.data.kind),
        typeof node.data.mediaKind === 'string' ? node.data.mediaKind : undefined
      )
      return {
        ...node,
        data: {
          ...node.data,
          kind: mapped.kind,
          ...(mapped.imagePurpose ? { imagePurpose: mapped.imagePurpose } : {})
        }
      }
    }),
    ...(workspace.savedAssets ? {
      savedAssets: workspace.savedAssets.map((asset) => ({
        ...asset,
        kind: asset.mediaKind === 'video' || asset.kind === 'video' ? 'video' : 'image'
      }))
    } : {})
  } as T
}
