'use client'

import { useState } from 'react'
import { Button, InputNumber, Modal } from 'antd'
import { CopyOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type { DirectorActorAction, DirectorActorActionClip, DirectorSceneData } from '@/types/studio'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioPanelSelectTrigger } from '@/components/studio/composer/StudioPanelSelectTrigger'
import { appendDirectorActorAction, DIRECTOR_ACTOR_ACTIONS, directorActionLabel, removeDirectorActorAction, updateDirectorActorAction } from '@/utils/studio/directorActorActions'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import './director-actor-actions.css'

export function DirectorActorActionsPanel({ scene, actorId, onChange, initialClipId, onOpenTimeline }: {
  scene: DirectorSceneData; actorId: string; onChange: (scene: DirectorSceneData) => void
  initialClipId?: string; onOpenTimeline?: () => void
}) {
  const [selectedId, setSelectedId] = useState(initialClipId)
  const [addOpen, setAddOpen] = useState(false)
  const [error, setError] = useState('')
  const actor = scene.objects.find(object => object.id === actorId && object.kind === 'actor')
  const clips = (scene.timeline?.actionClips ?? []).filter(clip => clip.actorId === actorId).sort((a, b) => a.start - b.start)
  const selected = clips.find(clip => clip.id === selectedId) ?? clips[0]
  if (!actor) return null
  const commit = (work: () => DirectorSceneData) => {
    try { onChange(work()); setError('') } catch (reason) { setError(reason instanceof Error ? reason.message : '动作修改失败') }
  }
  const append = (action: DirectorActorAction, source?: DirectorActorActionClip) => commit(() => {
    const result = appendDirectorActorAction(scene, actorId, action, useDirectorPlaybackStore.getState().playhead, source)
    setSelectedId(result.clip.id)
    useDirectorPlaybackStore.getState().setPlaying(false)
    useDirectorPlaybackStore.getState().setPlayhead(result.clip.start)
    onOpenTimeline?.()
    return result.scene
  })
  return <div className="director-actor-actions">
    <p className="director-actor-actions__hint">轨迹决定走到哪里，动作决定身体如何表演。片段结束后保持末姿，下一个动作从此姿态过渡。</p>
    {scene.timeline?.poseTracks?.some(track => track.objectId === actorId && track.keyframes.length) ?
      <p className="director-actor-actions__hint" role="status">此人物已有姿态关键帧，其中记录的关节会优先于动作。需要完整播放动作时，请在时间轴调整或移除对应姿态轨道。</p> : null}
    <div className="director-actor-actions__toolbar">
      <StudioCanvasDropdownPopover open={addOpen && !actor.locked} onOpenChange={setAddOpen} placement="bottomLeft" autoAdjustOverflow
        ariaLabel="追加人物动作" items={DIRECTOR_ACTOR_ACTIONS}
        onSelect={key => append(key as DirectorActorAction)}
        trigger={<StudioCanvasTooltip title={actor.locked ? '请先解锁人物再编辑动作' : '在游标处或已有动作之后追加3秒片段'}>
          <Button size="small" icon={<PlusOutlined />} aria-label="追加动作" disabled={actor.locked}>追加动作</Button>
        </StudioCanvasTooltip>} />
      {onOpenTimeline ? <Button size="small" onClick={onOpenTimeline}>展开时间轴</Button> : null}
    </div>
    {!clips.length ? <div className="director-actor-actions__empty">先添加跑步，再追加蹲地哭泣；在下面调整每一段的时间。</div> : null}
    <div className="director-actor-actions__list" role="group" aria-label="人物动作顺序">
      {clips.map((clip, index) => <button key={clip.id} type="button" aria-pressed={clip.id === selected?.id}
        className={clip.id === selected?.id ? 'is-active' : ''} onClick={() => {
          setSelectedId(clip.id); setError('')
          useDirectorPlaybackStore.getState().setPlaying(false)
          useDirectorPlaybackStore.getState().setPlayhead(clip.start)
        }}><span>{index + 1}</span><strong>{clip.assetName ?? directorActionLabel(clip.action)}</strong><small>{clip.start.toFixed(2)}–{clip.end.toFixed(2)} s</small></button>)}
    </div>
    {selected ? <ActionClipForm key={JSON.stringify([selected.id, selected.action, selected.start, selected.end, selected.speed, selected.intensity, selected.blendIn, selected.pose, selected.motion?.id])} clip={selected} locked={Boolean(actor.locked)}
      onSave={patch => commit(() => updateDirectorActorAction(scene, selected.id, {
        ...patch, ...(patch.action === 'pose' && !patch.pose ? { pose: { ...actor.pose } } : {})
      }))}
      onSnapshot={() => commit(() => updateDirectorActorAction(scene, selected.id, { action: 'pose', pose: { ...actor.pose } }))}
      onDuplicate={() => append(selected.action, selected)}
      onDelete={() => commit(() => removeDirectorActorAction(scene, selected.id))} /> : null}
    {error ? <p className="director-actor-actions__error" role="alert">{error}</p> : null}
    <p className="director-actor-actions__hint">动作可以原地播放。需要前进时另加走位轨迹；“姿态”轨道会覆盖它记录的关节，可用于局部细调。</p>
  </div>
}

function ActionClipForm({ clip, locked, onSave, onSnapshot, onDuplicate, onDelete }: {
  clip: DirectorActorActionClip; locked: boolean; onSave: (patch: Partial<DirectorActorActionClip>) => void
  onSnapshot: () => void; onDuplicate: () => void; onDelete: () => void
}) {
  const [draft, setDraft] = useState(clip)
  const [open, setOpen] = useState(false)
  const field = (label: string, key: 'start' | 'end' | 'speed' | 'intensity' | 'blendIn', min: number, max: number, step: number) => (
    <label className="director-action-field"><span>{label}</span><InputNumber aria-label={label} min={min} max={max} step={step}
      precision={key === 'start' || key === 'end' ? 2 : undefined} disabled={locked} value={draft[key]}
      onChange={value => { if (value !== null) setDraft(current => ({ ...current, [key]: value })) }} /></label>
  )
  return <section className="director-action-form" aria-label="动作片段设置">
    <div className="director-action-field director-action-field--wide"><span>动作类型</span>
      <StudioCanvasDropdownPopover open={open && !locked} onOpenChange={setOpen} placement="topLeft" autoAdjustOverflow
        ariaLabel="动作类型" items={clip.action === 'imported' ? [{ key: 'imported', label: clip.assetName ?? '导入动作' }, ...DIRECTOR_ACTOR_ACTIONS] : DIRECTOR_ACTOR_ACTIONS} activeKey={draft.action}
        onSelect={key => setDraft(current => ({ ...current, action: key as DirectorActorAction, assetId: key === 'imported' ? clip.assetId : undefined, assetName: key === 'imported' ? clip.assetName : undefined, motion: key === 'imported' ? clip.motion : undefined }))}
        trigger={<StudioPanelSelectTrigger label={draft.assetName ?? directorActionLabel(draft.action)} ariaLabel="动作类型" disabled={locked} />} />
    </div>
    {field('开始时间（秒）', 'start', 0, 86400, 1 / 30)}
    {field('结束时间（秒）', 'end', 1 / 30, 86400, 1 / 30)}
    {field('动作速度', 'speed', .25, 3, .25)}
    {field('动作幅度', 'intensity', 0, 1, .1)}
    {field('进入过渡（秒）', 'blendIn', 0, 2, .05)}
    <div className="director-action-form__actions">
      <StudioCanvasTooltip title={locked ? '请先解锁人物再编辑动作' : '保存动作类型、时间和表演参数'}>
        <Button size="small" type="primary" aria-label="保存片段" disabled={locked} onClick={() => onSave(draft)}>保存片段</Button>
      </StudioCanvasTooltip>
      <StudioCanvasTooltip title="复制到动作序列末尾"><Button size="small" icon={<CopyOutlined />} aria-label="复制动作片段" disabled={locked} onClick={onDuplicate} /></StudioCanvasTooltip>
      <StudioCanvasTooltip title="删除此片段，保留走位与姿态轨道"><Button size="small" danger icon={<DeleteOutlined />} aria-label="删除动作片段" disabled={locked} onClick={onDelete} /></StudioCanvasTooltip>
    </div>
    <Button className="director-action-form__snapshot" size="small" disabled={locked} onClick={onSnapshot}>用当前摆姿记录自定义动作</Button>
  </section>
}

export function DirectorActorActionDialog({ scene, clipId, onChange, onClose }: {
  scene: DirectorSceneData; clipId?: string; onChange: (scene: DirectorSceneData) => void; onClose: () => void
}) {
  const clip = scene.timeline?.actionClips?.find(item => item.id === clipId)
  const actor = scene.objects.find(object => object.id === clip?.actorId && object.kind === 'actor')
  return <Modal open={Boolean(clip && actor)} title={`${actor?.name ?? '人物'} · 动作编排`}
    className="create-flow-modal director-action-modal" centered width={540} footer={null} onCancel={onClose} destroyOnHidden>
    {clip && actor ? <DirectorActorActionsPanel key={clip.id} scene={scene} actorId={actor.id} initialClipId={clip.id} onChange={onChange} /> : null}
  </Modal>
}
