'use client'
import { useEffect, useRef, useState } from 'react'
import { Button, Input, Modal } from 'antd'
import {
  DownloadOutlined,
  ImportOutlined,
  SearchOutlined,
  DeleteOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import type { DirectorActorActionClip, DirectorSceneData } from '@/types/studio'
import { DIRECTOR_ACTOR_ACTIONS, appendDirectorActorAction } from '@/utils/studio/directorActorActions'
import { downloadDirectorActionTemplate, readDirectorActionFile } from '@/utils/studio/directorActionLibrary'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioPanelSelectTrigger } from '@/components/studio/composer/StudioPanelSelectTrigger'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { serializeDirectorProject } from './directorProjectIo'
import { DirectorActionPreview } from './DirectorActionPreview'
import './director-action-library.css'

type Category = 'all' | 'move' | 'daily' | 'emotion' | 'sport'
const groups: Record<string, Category> = {
  walk: 'move',
  run: 'move',
  crouch: 'move',
  sit: 'move',
  kneel: 'move',
  reach: 'move',
  stretch: 'move',
  crouch_cry: 'emotion',
  cheer: 'emotion',
  bow: 'emotion',
  wave: 'emotion',
  nod: 'emotion',
  shake_head: 'emotion',
  march: 'move', tiptoe: 'move', wave_both: 'emotion',
  jump: 'sport', squat_repeat: 'sport', lunge: 'sport', punch: 'sport', kick: 'sport',
  throw: 'sport', push: 'sport', pull: 'sport', dance: 'sport'
}
export function DirectorActionLibraryDialog({
  open,
  scene,
  actorId,
  onChange,
  onClose,
  onApplied
}: {
  open: boolean
  scene: DirectorSceneData
  actorId?: string | null
  onChange: (scene: DirectorSceneData) => void
  onClose: () => void
  onApplied: (id: string) => void
}) {
  return (
    <Modal
      open={open}
      centered
      width={1040}
      title={
        <span>
          动作库 <small className="director-motion-subtitle">身体表演 · 独立于走位轨迹</small>
        </span>
      }
      className="create-flow-modal director-motion-modal"
      footer={null}
      onCancel={onClose}
      destroyOnHidden
    >
      {open ? (
        <ActionLibrary scene={scene} actorId={actorId} onChange={onChange} onApplied={onApplied} />
      ) : null}
    </Modal>
  )
}
function ActionLibrary({
  scene,
  actorId,
  onChange,
  onApplied
}: Omit<Parameters<typeof DirectorActionLibraryDialog>[0], 'open' | 'onClose'>) {
  const [source, setSource] = useState<'official' | 'imported'>('official'),
    [category, setCategory] = useState<Category>('all'),
    [search, setSearch] = useState(''),
    [selected, setSelected] = useState('walk'),
    [speed, setSpeed] = useState(1),
    [target, setTarget] = useState(actorId ?? ''),
    [menu, setMenu] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null),
    flight = useRef(false),
    live = useRef(scene),
    alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  useEffect(() => {
    live.current = scene
  }, [scene])
  const actors = scene.objects.filter((o) => o.kind === 'actor'),
    actor = actors.find((o) => o.id === target) ?? actors.find((o) => !o.locked)
  const library = scene.actionLibrary ?? [],
    asset = library.find((a) => a.id === selected)
  const official = DIRECTOR_ACTOR_ACTIONS.filter((a) => a.key !== 'pose'),
    action = official.find((a) => a.key === selected)
  const label = asset?.name ?? action?.label ?? '站立呼吸',
    duration = asset?.duration ?? 6
  const clip: DirectorActorActionClip = {
    id: 'preview',
    actorId: 'preview',
    action: asset ? 'imported' : (action?.key ?? 'stand'),
    start: 0,
    end: duration,
    speed,
    intensity: 1,
    blendIn: 0,
    ...(asset ? { motion: asset, assetId: asset.id, assetName: asset.name } : {})
  }
  const choose = (id: string) => {
    setSelected(id)
    setError('')
  }
  const importFile = async (file?: File) => {
    if (!file || flight.current) return
    flight.current = true
    setBusy(true)
    setError('')
    try {
      const asset = await readDirectorActionFile(file)
      if (!alive.current) return
      const current = live.current
      if ((current.actionLibrary?.length ?? 0) >= 32)
        throw new Error('项目动作库最多32条，请先清理不使用的动作')
      const next = { ...current, actionLibrary: [...(current.actionLibrary ?? []), asset] }
      serializeDirectorProject(next)
      onChange(next)
      setSource('imported')
      choose(asset.id)
    } catch (e) {
      if (alive.current) setError(e instanceof Error ? e.message : '动作导入失败')
    } finally {
      flight.current = false
      if (alive.current) setBusy(false)
    }
  }
  // The modal owns this import; ignore late file decoding after it closes.
  const reason = !actor ? '请先在场景中添加人物' : actor.locked ? '请先解锁所选人物' : ''
  const add = () => {
    if (!actor || reason) return
    try {
      const source = asset ? { ...clip, end: asset.duration, speed: 1 } : undefined
      const result = appendDirectorActorAction(
        scene,
        actor.id,
        clip.action,
        useDirectorPlaybackStore.getState().playhead,
        source
      )
      serializeDirectorProject(result.scene)
      onChange(result.scene)
      useDirectorPlaybackStore.getState().setPlaying(false)
      useDirectorPlaybackStore.getState().setPlayhead(result.clip.start)
      onApplied(actor.id)
      setError('')
    } catch (e) {
      setError((e as Error).message)
    }
  }
  const list =
    source === 'official'
      ? official
          .filter(
            (a) =>
              (category === 'all' || (groups[a.key] ?? 'daily') === category) &&
              [a.label, a.description].some((s) => s.includes(search.trim()))
          )
          .map((a) => ({ id: a.key, name: a.label, detail: a.description, tag: '官方' }))
      : library
          .filter((a) => a.name.includes(search.trim()))
          .map((a) => ({
            id: a.id,
            name: a.name,
            detail: `${a.duration.toFixed(1)}秒 · ${a.frames.length}帧`,
            tag: a.source.toUpperCase()
          }))
  return (
    <div className="director-motion-library">
      <section className="director-motion-browser" aria-label="动作列表">
        <div className="director-motion-tabs" role="tablist" aria-label="动作来源">
          {(['official', 'imported'] as const).map((s) => (
            <button
              type="button"
              role="tab"
              aria-selected={source === s}
              key={s}
              onClick={() => {
                setSource(s)
                setSearch('')
              }}
            >
              {s === 'official' ? '官方动作' : '项目导入'}
              <span>{s === 'official' ? official.length : library.length}</span>
            </button>
          ))}
        </div>
        <div className="director-motion-search">
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索动作"
            aria-label="搜索动作"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            allowClear
          />
          <StudioCanvasTooltip title="导入BVH或AID动作文件">
            <Button
              icon={<ImportOutlined />}
              aria-label="导入动作"
              loading={busy}
              onClick={() => input.current?.click()}
            />
          </StudioCanvasTooltip>
          <input
            hidden
            type="file"
            accept=".bvh,.json"
            ref={input}
            onChange={(e) => {
              void importFile(e.target.files?.[0])
              e.target.value = ''
            }}
          />
        </div>
        {source === 'official' ? (
          <div className="director-motion-categories" role="tablist" aria-label="动作分类">
            {(
              [
                { id: 'all', label: '全部' },
                { id: 'move', label: '移动与姿势' },
                { id: 'daily', label: '日常表演' },
                { id: 'emotion', label: '情绪与互动' },
                { id: 'sport', label: '运动与表演' }
              ] as const
            ).map((c) => (
              <button
                key={c.id}
                type="button"
                role="tab"
                aria-selected={category === c.id}
                onClick={() => setCategory(c.id)}
              >
                {c.label}
              </button>
            ))}
          </div>
        ) : null}
        <div className="director-motion-grid">
          {list.map((a) => (
            <button
              key={a.id}
              type="button"
              aria-pressed={selected === a.id}
              className={selected === a.id ? 'is-selected' : ''}
              onClick={() => choose(a.id)}
            >
              <strong>{a.name}</strong>
              <small>{a.detail}</small>
              <em>{selected === a.id ? "正在预览" : source === "official" ? "选择预览" : a.tag}</em>
            </button>
          ))}
          {!list.length ? (
            <div className="director-motion-empty">
              <ImportOutlined />
              <strong>{search ? '没有匹配的动作' : '建立自己的动作库'}</strong>
              <p>导入动作后即可预览，并用于人物时间轴。</p>
              <Button onClick={() => input.current?.click()}>导入动作</Button>
            </div>
          ) : null}
        </div>
        <footer>
          <p>支持BVH人形骨架与AID动作JSON，最长60秒。BVH转换为白模关节表演，水平走位仍由轨迹控制。</p>
          <Button
            type="text"
            size="small"
            icon={<DownloadOutlined />}
            onClick={downloadDirectorActionTemplate}
          >
            动作模板
          </Button>
        </footer>
      </section>
      <section className="director-motion-player" aria-label="动作预览">
        <header>
          <span>实时预览</span>
          <strong>{label}</strong>
          {asset ? (
            <StudioCanvasTooltip title="从项目动作库移除；已添加的时间轴片段保留">
              <Button
                type="text"
                size="small"
                icon={<DeleteOutlined />}
                aria-label="移除导入动作"
                onClick={() => {
                  onChange({ ...scene, actionLibrary: library.filter((a) => a.id !== asset.id) })
                  choose('walk')
                  setSource('official')
                }}
              />
            </StudioCanvasTooltip>
          ) : (
            <small>官方动作</small>
          )}
        </header>
        <DirectorActionPreview clip={clip} />
        <div className="director-motion-speed">
          <span>预览速度</span>
          {[0.5, 1, 1.5].map((s) => (
            <button type="button" key={s} aria-pressed={speed === s} onClick={() => setSpeed(s)}>
              {s}×
            </button>
          ))}
        </div>
      </section>
      <footer className="director-motion-apply">
        <div className="director-motion-target">
          <span>添加到人物</span>
          <StudioCanvasDropdownPopover
            open={menu} onOpenChange={setMenu} ariaLabel="动作目标人物" placement="topLeft"
            items={actors.map((a) => ({ key: a.id, label: a.name }))} onSelect={setTarget}
            trigger={<StudioPanelSelectTrigger label={actor?.name ?? '请添加人物'} ariaLabel="动作目标人物" />}
          />
        </div>
        <StudioCanvasTooltip title={reason || `将「${label}」追加到游标或现有动作之后`}>
          <Button type="primary" icon={<PlusOutlined />} disabled={Boolean(reason)} onClick={add}>添加到时间轴</Button>
        </StudioCanvasTooltip>
      </footer>
      {error ? (
        <div className="director-motion-error" role="alert">
          {error}
        </div>
      ) : null}
    </div>
  )
}
