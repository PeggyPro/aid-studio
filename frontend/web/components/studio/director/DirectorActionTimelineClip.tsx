'use client'

import { App } from 'antd'
import type { DirectorActorActionClip, DirectorSceneData } from '@/types/studio'
import { directorActionLabel, updateDirectorActorAction } from '@/utils/studio/directorActorActions'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { DirectorTimelineClip } from './DirectorTimelineClip'

export function DirectorActionTimelineClip({ scene, clip, pixelsPerSecond, onChange, onSelect }: {
  scene: DirectorSceneData; clip: DirectorActorActionClip; pixelsPerSecond: number
  onChange: (scene: DirectorSceneData) => void; onSelect: () => void
}) {
  const { message } = App.useApp()
  const locked = scene.objects.find(object => object.id === clip.actorId)?.locked
  const name = clip.assetName ?? directorActionLabel(clip.action)
  const interval = name + '动作片段 ' + clip.start.toFixed(2) + ' 至 ' + clip.end.toFixed(2) + ' 秒'
  return <StudioCanvasTooltip title={locked ? interval + '（人物已锁定）' : interval + ' · 拖动移动，拖右端调时长，点击编辑'}>
    <DirectorTimelineClip start={clip.start} end={clip.end} scale={pixelsPerSecond} minimumDuration={1 / 30}
      label={interval} locked={locked} action onSelect={onSelect}
      onRetime={(start, end) => {
        try { onChange(updateDirectorActorAction(scene, clip.id, { start, end })) }
        catch (reason) { message.warning(reason instanceof Error ? reason.message : '动作区间修改失败') }
      }}>{name}</DirectorTimelineClip>
  </StudioCanvasTooltip>
}
