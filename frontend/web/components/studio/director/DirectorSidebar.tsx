'use client'

import { DirectorActionsIcon } from './DirectorActionsIcon'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { DirectorPanelDescription } from './DirectorPanelDescription'
import { useId, useMemo, useRef, useState } from 'react'
import { Button, Input, InputNumber, Modal, message } from 'antd'
import { STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS } from '../canvas/StudioCanvasDropdownMenu'
import { StudioCanvasPopover } from '../canvas/StudioCanvasPopover'
import {
  ApartmentOutlined,
  AppstoreAddOutlined,
  BorderOutlined,
  BulbOutlined,
  CameraOutlined,
  CaretRightOutlined,
  CloseOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  LoadingOutlined,
  LockOutlined,
  PictureOutlined,
  QuestionCircleOutlined,
  SearchOutlined,
  TeamOutlined,
  UnlockOutlined,
  UserAddOutlined,
  UserOutlined,
  VideoCameraAddOutlined
} from '@ant-design/icons'
import type { DirectorObject, DirectorSceneData } from '@/types/studio'
import {
  DIRECTOR_ASPECT_RATIOS,
  DIRECTOR_CAMERA_PRESETS,
  createDirectorActor,
  createDirectorActorGroup
} from '@/utils/studio/directorScene'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { DirectorAssetLibraryPanel } from './DirectorAssetLibraryPanel'
import { activateDirectorCamera } from './directorCameraState'
import {
  groupDirectorSceneActors,
  isDirectorCrowdGroupFullySelected,
  listDirectorCrowdMemberIds,
  prioritizeUnlockedDirectorSelection
} from './directorCrowdGroup'

export type DirectorSidebarTool = 'scene' | 'actor' | 'asset' | 'camera' | 'panorama' | 'ratio' | 'recognize' | 'actions'

interface DirectorSidebarProps {
  nodeId: string
  scene: DirectorSceneData
  activeTool: DirectorSidebarTool | null
  selectedObjectIds: string[]
  onToolChange: (tool: DirectorSidebarTool | null) => void
  onSelectObject: (id: string | null) => void
  onSelectObjects: (ids: string[]) => void
  onDeleteObjects: (ids: string[]) => void
  onChange: (scene: DirectorSceneData) => void
  onUploadPanorama: (file: File) => Promise<void>
  panoramaUploading: boolean
  onOpenActions: () => void
  onOpenSceneSettings: () => void
  onCreateCameraRequest: (preset: string) => void
  scenePrimitives?: React.ReactNode
  modelingObjects?: { count: number; render: (search: string) => React.ReactNode }
  modelingCameras?: { count: number; render: (search: string) => React.ReactNode }
  assetTools?: React.ReactNode
}

const TOOL_ITEMS: Array<{ key: DirectorSidebarTool; label: string; icon: React.ReactNode }> = [
  { key: 'scene', label: '场景', icon: <ApartmentOutlined /> },
  { key: 'actor', label: '添加角色', icon: <UserAddOutlined /> },
  { key: 'actions', label: '动作库', icon: <DirectorActionsIcon /> },
  { key: 'asset', label: '模型资产', icon: <AppstoreAddOutlined /> },
  { key: 'camera', label: '添加机位', icon: <VideoCameraAddOutlined /> },
  { key: 'panorama', label: '全景图', icon: <PictureOutlined /> },
  { key: 'ratio', label: '选择画幅比例', icon: <BorderOutlined /> }
]

export function DirectorSidebar({
  scene,
  activeTool,
  selectedObjectIds,
  onToolChange,
  onSelectObject,
  onSelectObjects,
  onDeleteObjects,
  onChange,
  onUploadPanorama,
  panoramaUploading,
  onCreateCameraRequest,
  onOpenActions,
  onOpenSceneSettings,
  scenePrimitives,
  modelingObjects,
  modelingCameras,
  assetTools,
}: DirectorSidebarProps) {
  const panelId = useId()
  const rail = useRef<HTMLElement>(null)
  const collapsed = activeTool === null
  const closePanel = () => {
    rail.current?.querySelector<HTMLButtonElement>(`[data-previz-step="${activeTool}"]`)?.focus()
    onToolChange(null)
  }
  return (
    <aside className={`director-sidebar${collapsed ? ' is-collapsed' : ''}`} aria-label="导演台工具栏">
      <nav ref={rail} className="director-sidebar__rail" aria-label="导演台功能">
        {TOOL_ITEMS.map((item) => (
          <StudioCanvasTooltip key={item.key} title={item.label} placement="right">
            <button
              type="button"
              className={activeTool === item.key ? 'is-active' : ''}
              data-previz-step={item.key}
              aria-label={item.label}
              aria-pressed={activeTool === item.key}
              aria-expanded={activeTool === item.key}
              aria-controls={item.key !== 'actions' ? panelId : undefined}
              onClick={() => {
                if (item.key === 'actions') { onToolChange(null); onOpenActions(); return }
                onToolChange(activeTool === item.key ? null : item.key)
              }}
            >
              {item.icon}
            </button>
          </StudioCanvasTooltip>
        ))}
        <StudioCanvasPopover trigger="click" placement="rightBottom" arrow={false} autoAdjustOverflow
          classNames={{ root: STUDIO_CANVAS_DROPDOWN_POPOVER_CLASS }} content={<DirectorShortcutHelp />}>
          <button type="button" className="director-sidebar__help" aria-label="查看导演台快捷键"><QuestionCircleOutlined /></button>
        </StudioCanvasPopover>
      </nav>
      <section id={panelId} className="director-sidebar__panel" aria-label={TOOL_ITEMS.find(item => item.key === activeTool)?.label ?? '工具内容'} aria-hidden={collapsed} inert={collapsed || undefined}
        onKeyDown={event => { if (event.key === 'Escape' && !event.defaultPrevented) { event.stopPropagation(); closePanel() } }}>
        <header className="director-sidebar__panel-head">
          <strong>{TOOL_ITEMS.find((item) => item.key === activeTool)?.label ?? '工具'}</strong>
          <StudioCanvasTooltip title="收起当前工具面板"><button type="button" className="director-sidebar__dismiss" aria-label="收起当前工具面板" onClick={closePanel}><CloseOutlined /></button></StudioCanvasTooltip>
        </header>
        <div key={activeTool ?? 'closed'} className="director-sidebar__panel-body">
        {activeTool === 'scene' ? (
          <ScenePanel scene={scene} selectedObjectIds={selectedObjectIds} onSelectObject={onSelectObject} onSelectObjects={onSelectObjects} onChange={onChange} onDeleteObjects={onDeleteObjects}
            modelingObjects={modelingObjects} modelingCameras={modelingCameras} onOpenSceneSettings={onOpenSceneSettings} />
        ) : null}
        {activeTool === 'actor' ? (
          <ActorPanel scene={scene} onChange={onChange} onSelectObject={onSelectObject} onSelectObjects={onSelectObjects} />
        ) : null}
        {activeTool === 'asset' ? (
          <DirectorAssetLibraryPanel scene={scene} onChange={onChange} onSelectObject={onSelectObject} primitives={scenePrimitives}>{assetTools}</DirectorAssetLibraryPanel>
        ) : null}
        {activeTool === 'camera' ? <CameraPanel onCreateCameraRequest={onCreateCameraRequest} /> : null}
        {activeTool === 'panorama' ? <PanoramaPanel scene={scene} onChange={onChange} onUpload={onUploadPanorama} uploading={panoramaUploading} /> : null}
        {activeTool === 'ratio' ? <AspectPanel scene={scene} onChange={onChange} /> : null}
        </div>
      </section>
    </aside>
  )
}

function ScenePanel({
  scene,
  selectedObjectIds,
  onSelectObject,
  onSelectObjects,
  onDeleteObjects,
  onChange,
  modelingObjects,
  modelingCameras,
  onOpenSceneSettings,
}: {
  scene: DirectorSceneData
  selectedObjectIds: string[]
  onSelectObject: (id: string | null) => void
  onSelectObjects: (ids: string[]) => void
  onDeleteObjects: (ids: string[]) => void
  onChange: (scene: DirectorSceneData) => void
  modelingObjects?: DirectorSidebarProps['modelingObjects']
  modelingCameras?: DirectorSidebarProps['modelingCameras']
  onOpenSceneSettings: () => void
}) {
  const [modal, modalHolder] = Modal.useModal()
  const [keyword, setKeyword] = useState('')
  const objects = useMemo(() => {
    const value = keyword.trim().toLowerCase()
    return value
      ? scene.objects.filter((item) => item.name.toLowerCase().includes(value) || Boolean(item.crowdGroupId) && '群众列表'.includes(value))
      : scene.objects
  }, [keyword, scene.objects])
  const actors = objects.filter((item) => item.kind === 'actor')
  const actorTree = useMemo(() => groupDirectorSceneActors(actors), [actors])
  const props = objects.filter((item) => item.kind === 'prop')
  const cameras = scene.cameraBookmarks ?? []
  const selectSceneObject = (id: string) => {
    onSelectObject(id)
    onChange(touchScene({ ...scene, viewMode: 'director' }))
  }
  const selectSceneObjects = (ids: string[]) => {
    onSelectObjects(ids)
    onChange(touchScene({ ...scene, viewMode: 'director' }))
  }
  const patchObjects = (ids: string[], patch: (item: DirectorObject) => DirectorObject) => {
    const idSet = new Set(ids)
    onChange(touchScene({
      ...scene,
      objects: scene.objects.map((item) => idSet.has(item.id) ? patch(item) : item)
    }))
  }

  return (
    <div className="director-tool-panel director-tool-panel--scene">
      {modalHolder}
      <DirectorPanelDescription>{`${scene.objects.length + (modelingObjects?.count ?? 0)} 个场景对象 · ${cameras.length + Number(scene.mainCameraPresent !== false)} 个导演机位`}</DirectorPanelDescription>
      <div className="director-scene-actions"><Button size="small" onClick={onOpenSceneSettings}>场景设置</Button></div>
      <Input
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        prefix={<SearchOutlined />}
        placeholder="搜索场景对象"
        allowClear
      />
      <div className="director-scene-tree">

        <SceneGroup icon={<AppstoreAddOutlined />} title="场景白模" count={objects.filter(o => o.kind === 'scenery').length} defaultOpen>
          <Button size="small" disabled={!objects.some(o => o.kind === 'scenery' && o.locked)} onClick={() => modal.confirm({ title: '解锁场景白模？', content: '解锁后可以移动所选范围内的场景部件。', onOk: () => patchObjects(objects.filter(o => o.kind === 'scenery').map(o => o.id), o => ({ ...o, locked: false })) })}>解锁全部场景白模</Button>
          {objects.filter(o => o.kind === 'scenery').map(object => <SceneObjectRow key={object.id} object={object} active={selectedObjectIds.includes(object.id)} onClick={() => selectSceneObject(object.id)} onDelete={() => onDeleteObjects([object.id])} onToggleVisible={() => patchObjects([object.id], o => ({ ...o, visible: o.visible === false }))} onToggleLocked={() => patchObjects([object.id], o => ({ ...o, locked: !o.locked }))} />)}
        </SceneGroup>
        <SceneGroup icon={<UserOutlined />} title="角色" count={actors.length} defaultOpen={actors.length <= 8 || actorTree.crowds.length > 0}>
          {actorTree.leads.map((object) => (
            <SceneObjectRow
              key={object.id}
              object={object}
              active={selectedObjectIds.includes(object.id)}
              onClick={() => selectSceneObject(object.id)}
              onDelete={() => onDeleteObjects([object.id])}
              onToggleVisible={() => patchObjects([object.id], (item) => ({ ...item, visible: item.visible === false }))}
              onToggleLocked={() => patchObjects([object.id], (item) => ({ ...item, locked: !item.locked }))}
            />
          ))}
          {actorTree.crowds.map((crowd) => {
            const memberIds = listDirectorCrowdMemberIds(scene.objects, crowd.id)
            const groupActive = isDirectorCrowdGroupFullySelected(memberIds, selectedObjectIds)
            const groupPartial = !groupActive && crowd.members.some((item) => selectedObjectIds.includes(item.id))
            const anyHidden = crowd.members.some((item) => item.visible === false)
            const anyLocked = crowd.members.some((item) => item.locked)
            return (
              <CrowdGroupBlock
                key={crowd.id}
                label={crowd.label}
                members={crowd.members}
                selectedObjectIds={selectedObjectIds}
                groupActive={groupActive}
                groupPartial={groupPartial}
                hidden={anyHidden}
                locked={anyLocked}
                onSelectGroup={() => selectSceneObjects(prioritizeUnlockedDirectorSelection(scene.objects, memberIds))}
                onSelectMember={selectSceneObject}
                onDeleteMember={(id) => onDeleteObjects([id])}
                onDeleteGroup={() => onDeleteObjects(memberIds)}
                onToggleGroupVisible={() => patchObjects(memberIds, (item) => ({ ...item, visible: anyHidden }))}
                onToggleGroupLocked={() => patchObjects(memberIds, (item) => ({ ...item, locked: !anyLocked }))}
                onToggleMemberVisible={(id) => patchObjects([id], (item) => ({ ...item, visible: item.visible === false }))}
                onToggleMemberLocked={(id) => patchObjects([id], (item) => ({ ...item, locked: !item.locked }))}
              />
            )
          })}
        </SceneGroup>
        <SceneGroup icon={<AppstoreAddOutlined />} title="物件" count={props.length} defaultOpen={props.length <= 6}>
          {props.map((object) => (
            <SceneObjectRow
              key={object.id}
              object={object}
              active={selectedObjectIds.includes(object.id)}
              onClick={() => selectSceneObject(object.id)}
              onDelete={() => onDeleteObjects([object.id])}
              onToggleVisible={() => onChange(touchScene({
                ...scene,
                objects: scene.objects.map((item) => item.id === object.id ? { ...item, visible: item.visible === false } : item)
              }))}
              onToggleLocked={() => onChange(touchScene({
                ...scene,
                objects: scene.objects.map((item) => item.id === object.id ? { ...item, locked: !item.locked } : item)
              }))}
            />
          ))}
        </SceneGroup>
        {modelingObjects && modelingObjects.count > 0 ? (
          <SceneGroup icon={<AppstoreAddOutlined />} title="模型与图元" count={modelingObjects.count} defaultOpen>
            {modelingObjects.render(keyword)}
          </SceneGroup>
        ) : null}
        <SceneGroup icon={<CameraOutlined />} title="机位" count={cameras.length + Number(scene.mainCameraPresent !== false) + (modelingCameras?.count ?? 0)} defaultOpen>
          {modelingCameras?.render(keyword)}
          {scene.mainCameraPresent !== false && <SceneCameraRow
            name="主机位"
            active={selectedObjectIds.includes('main-camera')}
            visible={scene.mainCameraVisible !== false}
            locked={scene.mainCameraLocked === true}
            onClick={() => {
              onSelectObject('main-camera')
              onChange(touchScene({ ...activateDirectorCamera(scene, 'main-camera'), viewMode: 'camera' }))
            }}
            onDelete={() => onDeleteObjects(['main-camera'])}
            onToggleVisible={() => onChange(touchScene({ ...scene, mainCameraVisible: scene.mainCameraVisible === false }))}
            onToggleLocked={() => onChange(touchScene({ ...scene, mainCameraLocked: !scene.mainCameraLocked }))}
          />}
          {cameras.map((camera) => (
            <SceneCameraRow
              key={camera.id}
              name={camera.name}
              active={selectedObjectIds.includes(camera.id)}
              visible={camera.visible !== false}
              locked={camera.locked === true}
              onClick={() => {
                onSelectObject(camera.id)
                onChange(touchScene({ ...activateDirectorCamera(scene, camera.id), viewMode: 'camera' }))
              }}
              onDelete={() => onDeleteObjects([camera.id])}
              onToggleVisible={() => onChange(touchScene({
                ...scene,
                cameraBookmarks: cameras.map((item) => item.id === camera.id ? { ...item, visible: item.visible === false } : item)
              }))}
              onToggleLocked={() => onChange(touchScene({
                ...scene,
                cameraBookmarks: cameras.map((item) => item.id === camera.id ? { ...item, locked: !item.locked } : item)
              }))}
            />
          ))}
        </SceneGroup>
        <SceneGroup icon={<BorderOutlined />} title="灯光" count={scene.lights.length} defaultOpen={false}>
          {scene.lights.map(light => <SceneTreeRow key={light.id} name={light.name} icon={<BulbOutlined aria-hidden />} kind="light"
            active={selectedObjectIds.includes(light.id)} onClick={() => onSelectObject(light.id)} actions={<div className="director-tree-row__actions">
              <StudioCanvasTooltip title={`${light.enabled ? '隐藏' : '显示'}${light.name}`}>
                <button type="button" aria-label={`${light.enabled ? '隐藏' : '显示'}${light.name}`} onClick={() => onChange(touchScene({ ...scene, lights: scene.lights.map(item => item.id === light.id ? { ...item, enabled: !item.enabled } : item) }))}>
                  {light.enabled ? <EyeOutlined aria-hidden /> : <EyeInvisibleOutlined aria-hidden />}
                </button>
              </StudioCanvasTooltip>
            </div>} />)}
        </SceneGroup>
      </div>
    </div>
  )
}

function CrowdGroupBlock({
  label,
  members,
  selectedObjectIds,
  groupActive,
  groupPartial,
  hidden,
  locked,
  onSelectGroup,
  onSelectMember,
  onDeleteGroup,
  onDeleteMember,
  onToggleGroupVisible,
  onToggleGroupLocked,
  onToggleMemberVisible,
  onToggleMemberLocked
}: {
  label: string
  members: DirectorObject[]
  selectedObjectIds: string[]
  groupActive: boolean
  groupPartial: boolean
  hidden: boolean
  locked: boolean
  onSelectGroup: () => void
  onSelectMember: (id: string) => void
  onDeleteGroup: () => void
  onDeleteMember: (id: string) => void
  onToggleGroupVisible: () => void
  onToggleGroupLocked: () => void
  onToggleMemberVisible: (id: string) => void
  onToggleMemberLocked: (id: string) => void
}) {
  const [open, setOpen] = useState(true)
  return (
    <div className={`director-tree-crowd${open ? ' is-open' : ''}${groupActive ? ' is-active' : ''}${groupPartial ? ' is-partial' : ''}`}>
      <div className="director-tree-crowd__head">
        <button
          type="button"
          className="director-tree-crowd__caret"
          aria-label={open ? '折叠群众列表' : '展开群众列表'}
          onClick={() => setOpen((value) => !value)}
        >
          <CaretRightOutlined className={open ? 'is-expanded' : ''} />
        </button>
        <button type="button" className="director-tree-row director-tree-crowd__row" onClick={onSelectGroup}>
          <TeamOutlined />
          <span>{label}</span>
          <em>{members.length}</em>
        </button>
        <SceneTreeActions name={label} visible={!hidden} locked={locked}
          onToggleVisible={onToggleGroupVisible} onToggleLocked={onToggleGroupLocked} onDelete={onDeleteGroup} />
      </div>
      {open ? (
        <div className="director-tree-crowd__body">
          {members.map((object) => (
            <SceneObjectRow
              key={object.id}
              object={object}
              active={selectedObjectIds.includes(object.id)}
              onClick={() => onSelectMember(object.id)}
              onDelete={() => onDeleteMember(object.id)}
              onToggleVisible={() => onToggleMemberVisible(object.id)}
              onToggleLocked={() => onToggleMemberLocked(object.id)}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function SceneGroup({
  icon,
  title,
  count,
  defaultOpen = true,
  children
}: {
  icon: React.ReactNode
  title: string
  count: number
  defaultOpen?: boolean
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section className={`director-tree-group${open ? ' is-open' : ' is-collapsed'}`}>
      <header role="button" tabIndex={0} onClick={() => setOpen((value) => !value)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setOpen((value) => !value) } }}>
        <CaretRightOutlined className={`director-tree-group__caret${open ? ' is-expanded' : ''}`} />
        {icon}
        <strong>{title}</strong>
        <span>{count}</span>
      </header>
      <div className="director-tree-group__body-wrap">
        <div className="director-tree-group__body">{children || <small>暂无内容</small>}</div>
      </div>
    </section>
  )
}

/** Identical hit area, selection and actions layout for objects, cameras and lights. */
function SceneTreeRow({ name, icon, active, onClick, actions, kind }: {
  name: string; icon: React.ReactNode; active: boolean; onClick: () => void; actions: React.ReactNode; kind?: 'camera' | 'light'
}) {
  return <div className={`director-tree-row-wrap${active ? ' is-active' : ''}${kind ? ` is-${kind}` : ''}`}>
    <button type="button" className={`director-tree-row${kind ? ` is-${kind}` : ''}`} aria-pressed={active} onClick={onClick}>
      {icon}<span>{name}</span>
    </button>
    {actions}
  </div>
}

function SceneObjectRow({
  object,
  active,
  onClick,
  onDelete,
  onToggleVisible,
  onToggleLocked
}: {
  object: DirectorObject
  active: boolean
  onClick: () => void
  onDelete: () => void
  onToggleVisible: () => void
  onToggleLocked: () => void
}) {
  return (
    <SceneTreeRow name={object.name} icon={<i style={{ background: object.color }} />} active={active} onClick={onClick}
      actions={<SceneTreeActions name={object.name} visible={object.visible !== false} locked={object.locked === true}
        onToggleVisible={onToggleVisible} onToggleLocked={onToggleLocked} onDelete={onDelete} />} />
  )
}

function SceneCameraRow({
  name,
  active,
  visible,
  locked,
  onClick,
  onDelete,
  onToggleVisible,
  onToggleLocked
}: {
  name: string
  active: boolean
  visible: boolean
  locked: boolean
  onClick: () => void
  onDelete: () => void
  onToggleVisible: () => void
  onToggleLocked: () => void
}) {
  return (
    <SceneTreeRow name={name} icon={<CameraOutlined aria-hidden />} kind="camera" active={active} onClick={onClick}
      actions={<SceneTreeActions name={name} visible={visible} locked={locked}
        onToggleVisible={onToggleVisible} onToggleLocked={onToggleLocked} onDelete={onDelete} />} />
  )
}

function SceneTreeActions({ name, visible, locked, onToggleVisible, onToggleLocked, onDelete }: {
  name: string
  visible: boolean
  locked: boolean
  onToggleVisible: () => void
  onToggleLocked: () => void
  onDelete: () => void
}) {
  return (
    <div className="director-tree-row__actions">
      <button type="button" aria-label={`${visible ? '隐藏' : '显示'}${name}`} onClick={onToggleVisible}>
        {visible ? <EyeOutlined /> : <EyeInvisibleOutlined />}
      </button>
      <button type="button" aria-label={`${locked ? '解锁' : '锁定'}${name}`} onClick={onToggleLocked}>
        {locked ? <LockOutlined /> : <UnlockOutlined />}
      </button>
      <StudioCanvasTooltip title={locked ? '请先解锁再删除' : `删除${name}`} placement="top">
        <button type="button" className="director-tree-row__delete" aria-label={`删除${name}`}
          aria-disabled={locked || undefined} onClick={onDelete}>
          <DeleteOutlined />
        </button>
      </StudioCanvasTooltip>
    </div>
  )
}

function ActorPanel({
  scene,
  onChange,
  onSelectObject,
  onSelectObjects
}: {
  scene: DirectorSceneData
  onChange: (scene: DirectorSceneData) => void
  onSelectObject: (id: string | null) => void
  onSelectObjects: (ids: string[]) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [rows, setRows] = useState(3)
  const [columns, setColumns] = useState(3)
  const [spacing, setSpacing] = useState(1.2)
  const [crowdOpen, setCrowdOpen] = useState(false)
  const actorCount = scene.objects.filter((item) => item.kind === 'actor').length

  const addActor = (preset: string, referenceImageUrl?: string) => {
    const actor = createDirectorActor(actorCount + 1, preset)
    actor.referenceImageUrl = referenceImageUrl
    onChange(touchScene({ ...scene, objects: [...scene.objects, actor] }))
    onSelectObject(actor.id)
  }
  const upload = async (file?: File) => {
    if (!file) return
    const url = await uploadImageToOssWithToast(file)
    if (url) addActor('自定义角色', url)
  }
  const addGroup = () => {
    const group = createDirectorActorGroup(actorCount + 1, rows, columns, spacing)
    onChange(touchScene({ ...scene, objects: [...scene.objects, ...group], viewMode: 'director' }))
    onSelectObjects(group.map((item) => item.id))
    setCrowdOpen(false)
  }

  return (
    <div className="director-tool-panel director-tool-panel--scroll">
      <DirectorPanelDescription>角色预设、造型参考与群众阵列</DirectorPanelDescription>
      <button type="button" className="director-tool-upload" onClick={() => inputRef.current?.click()}>
        <CloudUploadOutlined /><span><strong>本地上传</strong><small>图片将作为角色造型参考</small></span>
      </button>
      <input ref={inputRef} type="file" accept="image/*" hidden onChange={(event) => { void upload(event.target.files?.[0]); event.target.value = '' }} />
      <ToolSection title="角色预设">
        <div className="director-choice-list">
          {['标准男性', '标准女性', '健硕', '纤细', '少年', '儿童', '宽厚', '二头身'].map((name) => (
            <button key={name} type="button" onClick={() => addActor(name)}><UserOutlined /><span>{name}</span><em>+</em></button>
          ))}
        </div>
      </ToolSection>
      <button type="button" className="director-crowd-trigger" onClick={() => setCrowdOpen(true)}>
        <TeamOutlined />
        <span><strong>群众阵列</strong><small>{rows}×{columns} · 点击配置并添加</small></span>
        <em>+</em>
      </button>
      {crowdOpen ? (
        <>
          <button type="button" className="director-crowd-backdrop" aria-label="关闭群众阵列面板" onClick={() => setCrowdOpen(false)} />
          <aside className="director-crowd-drawer" aria-label="群众阵列配置">
            <header>
              <strong>群众阵列</strong>
              <button type="button" aria-label="关闭" onClick={() => setCrowdOpen(false)}><CloseOutlined /></button>
            </header>
            <div className="director-group-config">
              <label>行数<InputNumber min={1} max={8} value={rows} onChange={(value) => setRows(Number(value ?? 1))} /></label>
              <label>列数<InputNumber min={1} max={8} value={columns} onChange={(value) => setColumns(Number(value ?? 1))} /></label>
              <label>间距<InputNumber min={0.6} max={4} step={0.1} value={spacing} onChange={(value) => setSpacing(Number(value ?? 1.2))} /></label>
              <Button type="primary" block onClick={addGroup}>添加 {rows * columns} 人</Button>
            </div>
          </aside>
        </>
      ) : null}
    </div>
  )
}

function CameraPanel({ onCreateCameraRequest }: { onCreateCameraRequest: (preset: string) => void }) {
  return (
    <div className="director-tool-panel director-tool-panel--scroll">
      <DirectorPanelDescription>先配置机身、镜头和位姿，再写入场景</DirectorPanelDescription>
      <div className="director-camera-grid">
        {['当前视角', ...Object.keys(DIRECTOR_CAMERA_PRESETS).filter(name => name !== '当前视角')].map((name) => (
          <button key={name} type="button" onClick={() => onCreateCameraRequest(name)}><CameraOutlined aria-hidden="true" /><span>{name}</span></button>
        ))}
      </div>
    </div>
  )
}

function PanoramaPanel({ scene, onChange, onUpload, uploading }: {
  scene: DirectorSceneData
  onChange: (scene: DirectorSceneData) => void
  onUpload: (file: File) => Promise<void>
  uploading: boolean
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  return (
    <div className="director-tool-panel director-panorama-panel">
      <button type="button" className="director-panorama-upload" disabled={uploading} onClick={() => inputRef.current?.click()}>
        <span className="director-panorama-upload__icon" aria-hidden="true">{uploading ? <LoadingOutlined /> : <CloudUploadOutlined />}</span>
        <span><strong>{uploading ? '正在上传全景图' : '上传全景图'}</strong><small>等距柱状图片 · 建议 2:1</small></span>
      </button>
      <input ref={inputRef} type="file" aria-label="上传全景图文件" hidden accept="image/*" onChange={(event) => {
        const file = event.target.files?.[0]
        event.target.value = ''
        if (file) void onUpload(file)
      }} />
      <ToolSection title="最近使用">
        {(scene.panoramaHistory ?? []).length ? (
          <div className="director-panorama-history">
            {(scene.panoramaHistory ?? []).map((url, index) => (
              <button key={`${url}-${index}`} type="button" aria-pressed={scene.panoramaUrl === url} disabled={uploading} onClick={() => {
                onChange(touchScene({ ...scene, panoramaUrl: url, panoramaHistory: [url, ...(scene.panoramaHistory ?? []).filter(item => item !== url)] }))
              }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={url} alt="" loading="lazy" />
                <span><strong>全景 {index + 1}</strong>{scene.panoramaUrl === url ? <small>使用中</small> : null}</span>
              </button>
            ))}
          </div>
        ) : <p className="director-panorama-empty">上传后，最近使用的全景会显示在这里。</p>}
      </ToolSection>
    </div>
  )
}

function AspectPanel({ scene, onChange }: { scene: DirectorSceneData; onChange: (scene: DirectorSceneData) => void }) {
  return (
    <div className="director-tool-panel">
      <DirectorPanelDescription>机位视角与截图将按画幅构图</DirectorPanelDescription>
      <div className="director-aspect-grid">
        {DIRECTOR_ASPECT_RATIOS.map((item) => (
          <button
            key={item.value}
            type="button"
            className={(scene.aspectRatio ?? '16:9') === item.value ? 'is-active' : ''}
            onClick={() => onChange(touchScene({ ...scene, aspectRatio: item.value }))}
          >
            <i style={{ aspectRatio: item.ratio ?? 16 / 10 }} />
            <span>{item.label}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

function ToolSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="director-tool-section"><h3>{title}</h3>{children}</section>
}



function DirectorShortcutHelp() {
  const shortcuts = [
    ['俯视视角', 'T'], ['正面视角', 'Y'], ['重置视角', 'H'], ['网格吸附', 'X'],
    ['播放/暂停', 'Space'], ['插入航点', 'I'], ['完成路径', 'Enter'], ['退出绘制', 'Esc'], ['移动', 'V'], ['旋转', 'R'], ['缩放', 'F'], ['撤销', '⌘ Z'], ['删除', '⌫']
  ]
  return <div className="director-shortcut-help studio-canvas-popover-scroll"><strong>快捷键</strong><div>{shortcuts.map(([label, key]) => <span key={label}><em>{label}</em><kbd>{key}</kbd></span>)}</div></div>
}

function touchScene(scene: DirectorSceneData): DirectorSceneData {
  return { ...scene, updatedAt: new Date().toISOString() }
}
