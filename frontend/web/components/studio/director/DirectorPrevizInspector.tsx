'use client'

import { directorActorHeight } from '@/utils/studio/directorSceneUnits'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioPanelSelectTrigger } from '@/components/studio/composer/StudioPanelSelectTrigger'
import { useState } from 'react'
import { App, Button, ColorPicker, InputNumber, Select, Switch } from 'antd'
import { Vector3 } from 'three'
import type {
  DirectorSceneData,
  DirectorActorMeta,
  DirectorPathClip,
  DirectorTransformTuple as Vec,
} from '@/types/studio'
import { directorAnchor } from '@/utils/studio/directorFollowConstraints'
import { applyDirectorTimelineAt, normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { studioId } from '@/utils/studio/studioGraph'
import { useDirectorPlaybackStore } from './directorPlaybackStore'

export function DirectorPrevizInspector({
  scene,
  selectedId,
  onChange: onSceneChange,
  variant = 'auto',
}: {
  scene: DirectorSceneData
  selectedId: string | null
  onChange: (s: DirectorSceneData) => void
  /** auto：按选中项渲染；embedded：供属性页内嵌（角色运动 / 镜头跟拍） */
  variant?: 'auto' | 'actor-motion' | 'camera-follow'
}) {
  const { message } = App.useApp()
  const [target, setTarget] = useState<string>()
  const [legacyMotionOpen, setLegacyMotionOpen] = useState(false)
  const [kind, setKind] = useState<DirectorPathClip['kind']>('follow_relative')
  const [anchor, setAnchor] = useState<'origin' | 'chest' | 'head'>('chest')
  const [continuous, setContinuous] = useState(true)
  const object = scene.objects.find((o) => o.id === selectedId)
  const bookmark = scene.cameraBookmarks?.find((b) => b.id === selectedId)
  const camera = selectedId === 'main-camera' ? scene.camera : bookmark?.camera
  const onChange = (next: DirectorSceneData) => {
    if (!object?.locked && !bookmark?.locked && !(selectedId === 'main-camera' && scene.mainCameraLocked))
      onSceneChange(next)
  }
  const actors = scene.objects.filter((o) => o.kind === 'actor')
  const addFollow = () => {
    const actor = actors.find((a) => a.id === target)
    if (!actor || !camera) {
      message.warning('请选择人物作为跟踪目标')
      return
    }
    const timeline = normalizeDirectorTimeline(scene.timeline),
      start = useDirectorPlaybackStore.getState().playhead,
      end = Math.max(start + 0.2, Math.min(timeline.duration, start + 3))
    const paths = timeline.pathClips ?? []
    const existing = paths.find((c) => c.targetId === selectedId && c.start < end && c.end > start)
    if (existing && !(kind === 'follow_track' && existing.kind === 'path')) {
      message.warning('该时段已有路径或跟拍，请先调整片段区间')
      return
    }
    const evaluated = applyDirectorTimelineAt(scene, start).objects.find((o) => o.id === actor.id)!
    const offset = new Vector3(...camera.position)
      .sub(new Vector3(...directorAnchor(evaluated, anchor)))
      .applyAxisAngle(new Vector3(0, 1, 0), -evaluated.transform.rotation[1])
      .toArray()
    const point = { position: camera.position, lookAt: camera.target, fov: camera.fov }
    const clip: DirectorPathClip = {
      id: existing?.id ?? studioId('follow'),
      kind,
      targetKind: 'camera',
      targetId: selectedId!,
      start: existing?.start ?? start,
      end: existing?.end ?? end,
      waypoints: existing?.waypoints ?? [
        { ...point, id: studioId('wp'), time: start },
        { ...point, id: studioId('wp'), time: end },
      ],
      follow: {
        targetObjectId: actor.id,
        offset,
        anchor,
        continuousLookAt: kind === 'follow_static' || continuous,
        lockedPosition: camera.position,
      },
      createdAt: new Date().toISOString(),
    }
    onChange({
      ...scene,
      timeline: {
        ...timeline,
        duration: Math.max(timeline.duration, end),
        pathClips: [...paths.filter((c) => c.id !== clip.id), clip],
      },
    })
  }

  const showActor = variant === 'actor-motion' || (variant === 'auto' && object?.kind === 'actor')
  const showCamera = variant === 'camera-follow' || (variant === 'auto' && Boolean(camera) && object?.kind !== 'actor')
  const showLight = variant === 'auto' && Boolean(scene.lights.find((l) => l.id === selectedId))

  if (showActor && object?.kind === 'actor') {
    const meta: DirectorActorMeta = object.actorMeta ?? {
      heightMeters: directorActorHeight(object),
      locomotion: 'idle' as const,
      identifyColor: object.color,
    }
    const update = (patch: Partial<typeof meta>) =>
      onChange({
        ...scene,
        objects: scene.objects.map((o) =>
          o.id === object.id
            ? {
                ...o,
                actorMeta: { ...meta, ...patch },
                ...(patch.heightMeters
                  ? {
                      transform: {
                        ...o.transform,
                        scale: o.transform.scale.map((v) => (v * patch.heightMeters!) / directorActorHeight(o)) as Vec,
                      },
                    }
                  : {}),
                ...(patch.identifyColor ? { color: patch.identifyColor } : {}),
              }
            : o,
        ),
      })
    const moving = (scene.timeline?.pathClips ?? []).some(
      (c) =>
        c.targetId === object.id &&
        c.waypoints.some((p) => new Vector3(...p.position).distanceTo(new Vector3(...c.waypoints[0].position)) > 0.5),
    )
    return (
      <section className="director-control-section director-control-section--flat" aria-label="运动语义">
        <div className="director-section-title"><strong>运动语义</strong></div>
        <label className="director-field">
          <span>体型</span>
          <Select
            aria-label="体型"
            size="small"
            disabled={object.locked}
            value={meta.bodyPreset ?? '标准角色'}
            options={['标准角色', '纤细', '宽厚'].map((value) => ({ value, label: value }))}
            onChange={(bodyPreset) => {
              const oldWidth = meta.bodyPreset === '宽厚' ? 1.15 : meta.bodyPreset === '纤细' ? 0.9 : 1
              const width = bodyPreset === '宽厚' ? 1.15 : bodyPreset === '纤细' ? 0.9 : 1
              onChange({
                ...scene,
                objects: scene.objects.map((o) =>
                  o.id === object.id
                    ? {
                        ...o,
                        actorMeta: { ...meta, bodyPreset },
                        transform: {
                          ...o.transform,
                          scale: [
                            (o.transform.scale[0] * width) / oldWidth,
                            o.transform.scale[1],
                            (o.transform.scale[2] * width) / oldWidth,
                          ],
                        },
                      }
                    : o,
                ),
              })
            }}
          />
        </label>
        <label className="director-field">
          <span>身高（米）</span>
          <InputNumber
            size="small"
            min={0.8}
            max={2.2}
            step={0.05}
            disabled={object.locked}
            value={directorActorHeight(object)}
            onChange={(v) => v !== null && update({ heightMeters: v })}
          />
        </label>
        <div className="director-field">
          <span>无片段默认</span>
          <StudioCanvasDropdownPopover open={legacyMotionOpen && !object.locked && !scene.timeline?.actionClips?.some(clip => clip.actorId === object.id)}
            onOpenChange={setLegacyMotionOpen} placement="topRight" autoAdjustOverflow ariaLabel="无动作片段时的默认动作"
            activeKey={meta.locomotion} items={[{ key: 'idle', label: '站立' }, { key: 'walk', label: '行走' }, { key: 'run', label: '跑步' }]}
            onSelect={key => update({ locomotion: key as DirectorActorMeta['locomotion'] })}
            trigger={<StudioCanvasTooltip title={object.locked ? '请先解锁人物' : scene.timeline?.actionClips?.some(clip => clip.actorId === object.id)
              ? '已有分段动作，请切到“动作”页编辑' : '兼容旧轨迹的默认步态；分段表演请使用“动作”页'}>
              <StudioPanelSelectTrigger className="director-legacy-motion" ariaLabel="无动作片段时的默认动作"
                label={meta.locomotion === 'run' ? '跑步' : meta.locomotion === 'walk' ? '行走' : '站立'}
                disabled={Boolean(object.locked || scene.timeline?.actionClips?.some(clip => clip.actorId === object.id))} />
            </StudioCanvasTooltip>} />
        </div>
        <label className="director-field">
          <span>辨识色</span>
          <ColorPicker
            size="small"
            disabled={object.locked}
            value={meta.identifyColor}
            onChangeComplete={(v) => update({ identifyColor: v.toHexString() })}
          />
        </label>
        {moving && meta.locomotion === 'idle' && !scene.timeline?.actionClips?.some(clip => clip.actorId === object.id) ? (
          <p className="director-field__hint" role="status">走位轨迹只改变位置。请切到“动作”页添加行走、跑步或后续表演。</p>
        ) : null}
      </section>
    )
  }

  const light = scene.lights.find((l) => l.id === selectedId)
  if (showLight && light) {
    return (
      <section className="director-control-section director-control-section--flat" aria-label="灯光">
        <div className="director-section-title"><strong>{light.name}</strong></div>
        <label className="director-field">
          启用
          <Switch
            size="small"
            checked={light.enabled}
            onChange={(enabled) =>
              onChange({ ...scene, lights: scene.lights.map((l) => (l.id === light.id ? { ...l, enabled } : l)) })
            }
          />
        </label>
        <label className="director-field">
          强度
          <InputNumber
            size="small"
            min={0}
            max={20}
            step={0.1}
            value={light.intensity}
            onChange={(intensity) =>
              intensity !== null &&
              onChange({ ...scene, lights: scene.lights.map((l) => (l.id === light.id ? { ...l, intensity } : l)) })
            }
          />
        </label>
      </section>
    )
  }

  if (!showCamera || !camera) return null

  const cameraLocked = Boolean(bookmark?.locked || (selectedId === 'main-camera' && scene.mainCameraLocked))

  return (
    <section
      className={`director-control-section director-control-section--flat${cameraLocked ? ' is-locked' : ''}`}
      aria-label="跟拍"
    >
      <div className="director-section-title"><strong>跟拍</strong></div>
      <label className="director-field">
        <span>跟拍人物</span>
        <Select
          aria-label="跟拍目标人物"
          size="small"
          placeholder="选择跟拍人物"
          disabled={cameraLocked}
          value={target}
          onChange={setTarget}
          options={actors.map((a) => ({ value: a.id, label: a.name }))}
        />
      </label>
      <label className="director-field">
        <span>跟拍方式</span>
        <Select
          aria-label="跟拍方式"
          size="small"
          disabled={cameraLocked}
          value={kind}
          onChange={setKind}
          options={[
            { value: 'follow_relative', label: '相对位置跟随' },
            { value: 'follow_track', label: '轨迹跟拍' },
            { value: 'follow_static', label: '定点跟拍' },
          ]}
        />
      </label>
      <label className="director-field">
        <span>跟拍锚点</span>
        <Select
          aria-label="跟拍锚点"
          size="small"
          disabled={cameraLocked}
          value={anchor}
          onChange={setAnchor}
          options={[
            { value: 'origin', label: '原点' },
            { value: 'chest', label: '胸口' },
            { value: 'head', label: '头部' },
          ]}
        />
      </label>
      <label className="director-field">
        <span>持续看向</span>
        <Switch
          size="small"
          checked={kind === 'follow_static' || continuous}
          disabled={cameraLocked || kind === 'follow_static'}
          onChange={setContinuous}
        />
      </label>
      <Button block size="small" type="primary" disabled={cameraLocked} onClick={addFollow}>添加跟拍片段</Button>
    </section>
  )
}
