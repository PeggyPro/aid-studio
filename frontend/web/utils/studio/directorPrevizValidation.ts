import type { DirectorSceneData, DirectorObject, DirectorCameraBookmark, DirectorLensSpec } from '@/types/studio'
import { validVec, normalizeDirectorPathClips } from './directorPathClips'
import { parseDirectorSpaceResult } from '@/components/studio/director/directorScenePackageLoader'
import { validateDirectorActionLibrary } from './directorActionLibrary'
import { validateDirectorActionClips } from './directorActorActions'

const record = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('预演工程字段必须是对象')
  return v as Record<string, unknown>
}
const finite = (v: unknown, min: number, max: number) =>
  typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max
const string = (v: unknown) => typeof v === 'string' && v.length > 0 && v.length < 8192
export function validateDirectorObjectExtras(value: unknown): Pick<DirectorObject, 'actorMeta' | 'scenery'> {
  const input = record(value)
  if (input.actorMeta !== undefined) {
    const m = record(input.actorMeta)
    if (!finite(m.heightMeters, 0.8, 2.2) || !['idle', 'walk', 'run'].includes(String(m.locomotion)))
      throw new Error('人物身高或运动语义无效')
  }
  if (input.scenery !== undefined) {
    const m = record(input.scenery)
    if (!finite(m.partIndex, 0, 255) || !string(m.meshPath) || !string(m.scenePackageMediaId))
      throw new Error('白模部件数据无效')
  } else if (input.kind === 'scenery') throw new Error('白模部件缺少场景包引用')
  return {
    actorMeta: input.actorMeta as DirectorObject['actorMeta'],
    scenery: input.scenery as DirectorObject['scenery'],
  }
}
export function validateDirectorBookmarkExtras(value: unknown): Pick<DirectorCameraBookmark, 'lens' | 'aimObjectId'> {
  const input = record(value)
  if (input.lens !== undefined) validateDirectorLensSpec(input.lens)
  return {
    lens: input.lens as DirectorCameraBookmark['lens'],
    aimObjectId: typeof input.aimObjectId === 'string' ? input.aimObjectId : undefined,
  }
}

export function validateDirectorLensSpec(value: unknown): DirectorLensSpec {
  const lens = record(value)
  if (
    !['full-frame', 'super-35', 'aps-c'].includes(String(lens.bodyName)) ||
    !['prime', 'zoom'].includes(String(lens.lensType)) ||
    !finite(lens.focalLengthMm, 1, 1000) ||
    (lens.apertureF !== undefined && !finite(lens.apertureF, 0.7, 32)) ||
    (lens.sensorWidthMm !== undefined && !finite(lens.sensorWidthMm, 1, 100)) ||
    (lens.cameraBody !== undefined && !['virtual-production', 'lightweight-handheld', 'digital-cinema'].includes(String(lens.cameraBody))) ||
    (lens.lensSeries !== undefined && !['cine-prime', 'cine-zoom', 'anamorphic-prime', 'documentary-zoom'].includes(String(lens.lensSeries)))
  ) throw new Error('镜头参数无效')
  return lens as unknown as DirectorLensSpec
}
export function validateDirectorSceneExtras(value: unknown, scene: DirectorSceneData): DirectorSceneData {
  const input = record(value)
  if(input.actionLibrary !== undefined) scene.actionLibrary = validateDirectorActionLibrary(input.actionLibrary)
  const rawTimeline = input.timeline === undefined ? {} : record(input.timeline)
  if (rawTimeline.actionClips !== undefined) {
    scene.timeline = { ...scene.timeline!, actionClips: validateDirectorActionClips(rawTimeline.actionClips, scene) }
  }
  if (rawTimeline.pathClips !== undefined) {
    if (!Array.isArray(rawTimeline.pathClips) || rawTimeline.pathClips.length > 2000)
      throw new Error('路径片段数量无效')
    const clips = normalizeDirectorPathClips(rawTimeline.pathClips, scene.timeline?.duration ?? 10)
    if (clips.length !== rawTimeline.pathClips.length) throw new Error('路径片段已损坏，导入已取消')
    const ids = new Set<string>()
    for (const c of clips) {
      if (!string(c.id) || ids.has(c.id)) throw new Error('路径片段 ID 无效或重复')
      ids.add(c.id)
      const targetExists =
        c.targetKind === 'object'
          ? scene.objects.some((o) => o.id === c.targetId)
          : (c.targetId === 'main-camera' && scene.mainCameraPresent !== false) || scene.cameraBookmarks?.some((b) => b.id === c.targetId)
      if (
        !targetExists ||
        (c.follow && !scene.objects.some((o) => o.id === c.follow!.targetObjectId && o.kind === 'actor'))
      )
        throw new Error('路径引用对象不存在')
      const pointIds = new Set<string>()
      for (const p of c.waypoints) {
        if (
          !string(p.id) ||
          pointIds.has(p.id) ||
          !validVec(p.position) ||
          (p.fov !== undefined && !finite(p.fov, 1, 179))
        )
          throw new Error('航点数据无效')
        pointIds.add(p.id)
      }
    }
    scene.timeline = { ...scene.timeline!, pathClips: clips, fps: 30 }
  }
  if (input.viewportPrefs !== undefined) {
    const prefs = record(input.viewportPrefs)
    if (
      !['solid', 'xray'].includes(String(prefs.shading)) ||
      !['persp', 'top', 'front'].includes(String(prefs.cameraPreset)) ||
      (prefs.pathDrawPlane !== undefined && !['xz', 'xy', 'yz'].includes(String(prefs.pathDrawPlane))) ||
      typeof prefs.showNameplates !== 'boolean'
    )
      throw new Error('视口偏好无效')
    scene.viewportPrefs = { ...prefs, pathDrawMode: false } as DirectorSceneData['viewportPrefs']
  }
  if (input.scenePackage !== undefined) {
    const pkg = record(input.scenePackage)
    if (
      !string(pkg.mediaId) ||
      typeof pkg.url !== 'string' ||
      !/^https:\/\//.test(pkg.url) ||
      pkg.format !== 'glb' ||
      !Array.isArray(pkg.sourceImageMediaIds) ||
      !pkg.sourceImageMediaIds.every(string)
    )
      throw new Error('场景包引用无效')
    scene.scenePackage = pkg as unknown as DirectorSceneData['scenePackage']
  }
  if (input.scenePackages !== undefined) {
    if (!Array.isArray(input.scenePackages) || input.scenePackages.length > 256) throw new Error('场景包集合无效')
    const ids = new Set<string>()
    scene.scenePackages = input.scenePackages.map(value => {
      const pkg = record(value)
      if (!string(pkg.mediaId) || typeof pkg.url !== 'string' || !/^https:\/\//.test(pkg.url) || pkg.format !== 'glb'
        || !Array.isArray(pkg.sourceImageMediaIds) || !pkg.sourceImageMediaIds.every(string) || ids.has(String(pkg.mediaId))) throw new Error('场景包集合无效')
      ids.add(String(pkg.mediaId))
      return pkg as unknown as NonNullable<DirectorSceneData['scenePackages']>[number]
    })
  }
  if (input.spaceGenJob !== undefined) {
    const job = record(input.spaceGenJob)
    if (
      !(string(job.taskId) || job.taskId === '' && ['submitting', 'unknown'].includes(String(job.status)) && job.submission) ||
      !['submitting', 'unknown', 'queued', 'running', 'succeeded', 'failed', 'cancelled'].includes(String(job.status)) ||
      !Array.isArray(job.sourceImageMediaIds) ||
      !job.sourceImageMediaIds.every(string)
    )
      throw new Error('空间任务字段无效')
    if (job.applyMode !== undefined && !['append', 'replace'].includes(String(job.applyMode))) throw new Error('空间导入模式无效')
    if (job.progress !== undefined && !finite(job.progress, 0, 100)) throw new Error('空间任务进度无效')
    if (job.submission !== undefined) {
      const submission = record(job.submission)
      if (!string(submission.key) || !finite(submission.clientQuoteAmount, 0, Number.MAX_SAFE_INTEGER)
        || !Array.isArray(submission.imageMediaIds) || !submission.imageMediaIds.length || submission.imageMediaIds.length > 3 || !submission.imageMediaIds.every(string)) throw new Error('空间提交恢复字段无效')
    }
    if (job.result !== undefined) job.result = parseDirectorSpaceResult(job.result)
    scene.spaceGenJob = job as unknown as DirectorSceneData['spaceGenJob']
  }
  return scene
}
