import { createRandomUuid } from '@/utils/clientId'
import type { DirectorSceneData } from '@/types/studio'
import { parseDirectorProjectText } from '@/components/studio/director/directorProjectIo'
import { readPackage, downloadPreviz } from '@/utils/previz/export'
import { createProject } from '@/utils/previz/core'
import { isThreePrevizPackage, serializeThreePreviz, THREE_PREVIZ_MAX_BYTES, validateThreePrevizModeling, unpackThreePrevizAssets } from './threePrevizDocument'

function readDirectorBase(scene: unknown) {
  return parseDirectorProjectText(JSON.stringify({ kind: 'aid-director-project', schemaVersion: 3, version: 3, scene }), THREE_PREVIZ_MAX_BYTES)
}

/** Validate legacy formats with their own parsers; never reinterpret one as the other. */
export async function readThreePrevizProject(file: File, current: DirectorSceneData): Promise<DirectorSceneData> {
  if (!file.size || file.size > THREE_PREVIZ_MAX_BYTES) throw new Error('请选择不超过128MiB的3D预演工程JSON文件')
  const text = await file.text()
  let raw: unknown
  try { raw = JSON.parse(text) } catch { throw new Error('3D预演工程JSON格式无效') }
  if (isThreePrevizPackage(raw)) {
    const { modeling, ...director } = raw.scene
    const scene = unpackThreePrevizAssets({ ...readDirectorBase(director), modeling }, raw.assetPool)
    validateThreePrevizModeling(scene)
    if (scene.modeling) {
      const source = scene.modeling.project
      // Importing a file creates an independent document. Server projects are opened through the project library.
      const { remoteWorkspaceId: _project, remoteRole: _role, remoteDocumentVersion: _document, remoteSourceVersion: _source, editorVersion: _editor, ...project } = source
      const versions = project.versions.map(version => {
        const layer = version.scene.directorLayer
        if (!layer) return version
        return { ...version, scene: { ...version.scene, directorLayer: { ...layer, scene: readDirectorBase(layer.scene) } } }
      })
      scene.modeling = { ...scene.modeling, project: { ...project, id: createRandomUuid(), versions } }
    }
    return scene
  }
  if (raw && typeof raw === 'object' && (raw as { format?: string }).format === 'AID-3D-BOX') {
    const imported = await readPackage(file)
    const project = Object.assign(createProject(imported.title), imported)
    return { ...current, name: project.title, aspectRatio: project.scene.aspectRatio, environmentColor: project.scene.environment.background, environmentPreset: project.scene.environment.presetCode, modeling: { schemaVersion: 1, project } }
  }
  return { ...parseDirectorProjectText(text), modeling: current.modeling }
}

export function downloadThreePrevizProject(scene: DirectorSceneData) {
  downloadPreviz(new Blob([serializeThreePreviz(scene)], { type: 'application/json' }), `${scene.name || '3D预演'}.aid-previz.json`)
}
