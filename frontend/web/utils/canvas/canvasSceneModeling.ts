import type { CanvasSceneDocument } from '@/types/canvas-api'
import type { PrevizAnnotation, PrevizAsset, PrevizNode, PrevizProject, PrevizScene, PrevizVersion } from '@/types/previz'
import type { ThreePrevizModeling } from '@/types/threePreviz'
import type { DirectorSceneData } from '@/types/studio'
import { PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { materializePrevizDirectorView } from '@/utils/previz/sceneProjection'

type Asset = Pick<PrevizAsset, 'id' | 'name' | 'kind' | 'mime'> & { resourceId: string }
type Graph = Omit<PrevizScene, 'assets' | 'directorLayer' | 'nodes'> & {
  assets: Asset[]
  nodes: (Omit<PrevizNode, 'parentId' | 'directorBinding'> & { parentId?: string })[]
}
type Annotation = Omit<PrevizAnnotation, 'image'> & { resourceId: string }
type Version = Omit<PrevizVersion, 'scene'> & {
  scene: Graph
  directorSnapshot?: Record<string, unknown>
  directorBindings?: NonNullable<PrevizScene['directorLayer']>['bindings']
}
export interface CanvasSceneModeling {
  schemaVersion: 1
  scene: Graph
  versions: Version[]
  annotations: Annotation[]
  thumbnailResourceId?: string
  directorBindings?: ThreePrevizModeling['directorBindings']
  adoption?: { workspace: string; source?: string | null; document?: string; editor?: string; steps: string[] }
}

export async function prepareSceneModeling(
  modeling: ThreePrevizModeling,
  source: DirectorSceneData,
  register: (url: string, kind: PrevizAsset['kind']) => Promise<string>,
  snapshot: (scene: DirectorSceneData) => Promise<Record<string, unknown>>,
): Promise<CanvasSceneModeling> {
  const { project, directorBindings } = modeling
  const graph = async (value: PrevizScene, director?: DirectorSceneData): Promise<Graph> => {
    const { directorLayer: _projection, assets, ...authored } = materializePrevizDirectorView(
      value, director?.directorCamera ?? director?.camera
    )
    const registered: Asset[] = []
    for (const { id, name, kind, mime, dataUrl } of assets) {
      registered.push({ id, name, kind, mime, resourceId: await register(dataUrl, kind) })
    }
    return {
      ...authored, assets: registered,
      nodes: authored.nodes.map(({ directorBinding: _binding, parentId, ...node }) =>
        parentId ? { ...node, parentId } : node),
    }
  }
  const scene = await graph(project.scene, source)
  const versions: Version[] = []
  for (const version of project.versions) {
    const layer = version.scene.directorLayer
    versions.push({
      ...version, scene: await graph(version.scene, layer?.scene),
      ...(layer ? { directorSnapshot: await snapshot(layer.scene), directorBindings: layer.bindings } : {}),
    })
  }
  const annotations: Annotation[] = []
  for (const { image, ...annotation } of project.annotations) {
    annotations.push({ ...annotation, resourceId: await register(image, 'IMAGE') })
  }
  return {
    schemaVersion: 1, scene, versions, annotations, directorBindings,
    ...(project.remoteWorkspaceId ? { adoption: {
      workspace: project.remoteWorkspaceId,
      source: project.remoteSourceVersion ?? undefined,
      document: project.remoteDocumentVersion,
      editor: project.editorVersion ?? undefined,
      steps: project.adoptedBuildSteps ?? [],
    } } : {}),
    ...(project.thumbnail ? { thumbnailResourceId: await register(project.thumbnail, 'IMAGE') } : {}),
  }
}

export function canvasModelingResources(document?: CanvasSceneDocument): Map<string, string> {
  const result = new Map<string, string>()
  for (const scene of document?.scenes ?? []) {
    const modeling = scene.modeling as CanvasSceneModeling | undefined
    if (!modeling) continue
    for (const graph of [modeling.scene, ...modeling.versions.map(version => version.scene)]) {
      for (const asset of graph.assets) result.set(asset.resourceId, asset.mime)
    }
    for (const annotation of modeling.annotations) if (!result.has(annotation.resourceId)) result.set(annotation.resourceId, '')
    if (modeling.thumbnailResourceId && !result.has(modeling.thumbnailResourceId)) result.set(modeling.thumbnailResourceId, '')
  }
  return result
}

export function restoreSceneModeling(
  value: CanvasSceneModeling,
  source: { id: string; name: string; updatedAt: string },
  dataUrl: (resourceId: string) => string,
  snapshot: (scene: Record<string, unknown>) => DirectorSceneData,
): ThreePrevizModeling {
  const graph = ({ assets, ...authored }: Graph, projected: boolean): PrevizScene => ({
    ...authored,
    // Standalone history has no director layer to supply an observer after decoding.
    nodes: authored.nodes.filter(node => !projected || node.id !== PREVIZ_DIRECTOR_VIEW_ID || !node.locked || node.visible)
      .map(node => ({ ...node, parentId: node.parentId ?? null })),
    assets: assets.map(({ resourceId, ...asset }) => ({ ...asset, dataUrl: dataUrl(resourceId) })),
  })
  const project: PrevizProject = {
    id: 'canvas-' + source.id,
    title: source.name,
    revision: value.versions.at(-1)?.revision ?? 0,
    versionId: value.versions.at(-1)?.id ?? 'canvas-' + source.updatedAt,
    createdAt: source.updatedAt,
    updatedAt: source.updatedAt,
    scene: graph(value.scene, true),
    versions: value.versions.map(({ scene, directorSnapshot, directorBindings, ...version }) => {
      const restored = graph(scene, !!directorSnapshot && !!directorBindings)
      if (directorSnapshot && directorBindings) {
        restored.directorLayer = { scene: { ...snapshot(directorSnapshot), id: source.id }, bindings: directorBindings }
        restored.nodes = restored.nodes.map(node => ({ ...node, directorBinding: directorBindings[node.id] }))
      }
      return { ...version, scene: restored }
    }),
    annotations: value.annotations.map(({ resourceId, ...annotation }) => ({ ...annotation, image: dataUrl(resourceId) })),
    thumbnail: value.thumbnailResourceId ? dataUrl(value.thumbnailResourceId) : undefined,
    messages: [],
    draft: '',
    ...(value.adoption ? {
      remoteWorkspaceId: value.adoption.workspace,
      remoteSourceVersion: value.adoption.source ?? null,
      remoteDocumentVersion: value.adoption.document,
      editorVersion: value.adoption.editor,
      adoptedBuildSteps: value.adoption.steps,
    } : {}),
  }
  return { schemaVersion: 1, project, directorBindings: value.directorBindings }
}
