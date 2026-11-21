'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useState } from 'react'
import { Slider } from 'antd'
import {
  AppstoreOutlined,
  ExperimentOutlined,
  PictureOutlined,
  SkinOutlined,
  UserOutlined,
  VideoCameraOutlined
} from '@ant-design/icons'
import type { StudioCanvasNode, StudioImagePurpose, StudioNodeData } from '@/types/studio'
import { StudioComposerChip } from './StudioComposerChip'
import { useStudioUiStore } from '@/stores/studioUi'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'

type BenchTab = 'preset' | 'texture' | 'narrative'

export function StudioBenchPanel({
  node,
  onChange,
  onDerive
}: {
  node: StudioCanvasNode
  onChange: (options: NonNullable<StudioNodeData['generationOptions']>, imagePurpose?: StudioImagePurpose) => void
  onDerive: (options: NonNullable<StudioNodeData['generationOptions']>, imagePurpose: StudioImagePurpose, label: string) => void
}) {
  const [tab, setTab] = useState<BenchTab>('preset')
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const value = node.data.generationOptions ?? {}
  const texture = value.texture ?? { sharpness: 50, contrast: 50, grain: 20 }
  const narrative = value.narrative ?? { moods: [] }
  const sceneLike = node.data.imagePurpose === 'scene' || /场景|环境|空间/.test(node.data.prompt)
  return (
    <StudioCanvasPopover
      trigger="click"
      nodePlacement
      arrow={false}
      getPopupContainer={getStudioComposerPopupContainer}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
      content={
        <div className="studio-bench-panel">
          <nav>
            <button type="button" className={tab === 'preset' ? 'is-active' : ''} onClick={() => setTab('preset')}>生图设定</button>
            <button type="button" className={tab === 'texture' ? 'is-active' : ''} onClick={() => setTab('texture')}>质感调节</button>
            <button type="button" className={tab === 'narrative' ? 'is-active' : ''} onClick={() => setTab('narrative')}>分镜叙事</button>
          </nav>
          {tab === 'preset' && studioMode === 'flow' ? (
            <div className="studio-bench-panel__preset-groups">
              <PresetGroup title="分镜叙事">
                <PresetChoice icon={<VideoCameraOutlined />} label="调度故事板" description="生成带叙事轨迹的分镜组" onClick={() => onDerive({ ...value, benchPreset: 'multi_camera' }, 'storyboard', '调度故事板')} />
                <PresetChoice icon={<AppstoreOutlined />} label="故事板" description="快速展开关键镜头" onClick={() => onDerive({ ...value, benchPreset: 'quad_grid' }, 'storyboard', '故事板')} />
              </PresetGroup>
              <PresetGroup title="空间与机位">
                <PresetChoice icon={<VideoCameraOutlined />} label="720° 全景" description="建立完整空间关系" onClick={() => onDerive({ ...value, narrative: { ...narrative, shotSize: '全景' } }, 'scene', '720° 全景')} />
                <PresetChoice icon={<AppstoreOutlined />} label="多机位九宫格" description="一次展开多个观察角度" onClick={() => onDerive({ ...value, benchPreset: 'multi_camera' }, sceneLike ? 'scene' : 'storyboard', '多机位九宫格')} />
              </PresetGroup>
              <PresetGroup title="质感调节">
                <PresetChoice icon={<UserOutlined />} label="人像质感调节" description="强化肤质与人物光影" onClick={() => onDerive({ ...value, texture: { ...texture, preset: '人像质感' } }, 'character', '人像质感调节')} />
                <PresetChoice icon={<SkinOutlined />} label="电影级光影校正" description="统一画面色调与层次" onClick={() => onDerive({ ...value, texture: { ...texture, preset: '电影光影' } }, node.data.imagePurpose ?? 'generic', '电影级光影校正')} />
              </PresetGroup>
              <PresetGroup title="设定图">
                <PresetChoice icon={<UserOutlined />} label="角色三视图" description="正侧背完整角色设定" onClick={() => onDerive({ ...value, benchPreset: 'three_view' }, 'character', '角色三视图')} />
                <PresetChoice icon={<PictureOutlined />} label="场景设定图" description="建立环境构图与氛围" onClick={() => onDerive({ ...value, benchPreset: 'quad_grid' }, 'scene', '场景设定图')} />
              </PresetGroup>
            </div>
          ) : tab === 'preset' ? (
            <div className="studio-bench-panel__preset-groups">
              <PresetGroup title="基础设定">
                <PresetChoice icon={<UserOutlined />} label="角色三视图" description="生成正面、侧面和背面设定" onClick={() => onChange({ ...value, benchPreset: 'three_view' }, 'character')} />
                <PresetChoice icon={<VideoCameraOutlined />} label="多机位" description="从不同机位展开同一主体" onClick={() => onChange({ ...value, benchPreset: 'multi_camera' })} />
                {sceneLike ? <PresetChoice icon={<AppstoreOutlined />} label="拆分四宫格" description="将场景拆成四个关键画面" onClick={() => onChange({ ...value, benchPreset: 'quad_grid' }, 'scene')} /> : null}
              </PresetGroup>
            </div>
          ) : null}
          {tab === 'texture' ? (
            <div className="studio-bench-panel__sliders">
              <div className="studio-bench-panel__choices">
                {['胶片', '写实', '动漫质感'].map((preset) => <Choice key={preset} active={texture.preset === preset} label={preset} onClick={() => onChange({ ...value, texture: { ...texture, preset } })} />)}
              </div>
              <label>清晰 <Slider value={texture.sharpness} onChange={(sharpness) => onChange({ ...value, texture: { ...texture, sharpness } })} /></label>
              <label>对比 <Slider value={texture.contrast} onChange={(contrast) => onChange({ ...value, texture: { ...texture, contrast } })} /></label>
              <label>颗粒 <Slider value={texture.grain} onChange={(grain) => onChange({ ...value, texture: { ...texture, grain } })} /></label>
            </div>
          ) : null}
          {tab === 'narrative' ? (
            <div className="studio-bench-panel__narrative">
              <div><span>景别</span>{['特写', '中景', '全景'].map((shotSize) => <Choice key={shotSize} active={narrative.shotSize === shotSize} label={shotSize} onClick={() => onChange({ ...value, narrative: { ...narrative, shotSize } })} />)}</div>
              <div><span>机位</span>{['低机位', '平视', '俯拍'].map((cameraHeight) => <Choice key={cameraHeight} active={narrative.cameraHeight === cameraHeight} label={cameraHeight} onClick={() => onChange({ ...value, narrative: { ...narrative, cameraHeight } })} />)}</div>
              <div><span>情绪</span>{['紧张', '温暖', '孤独'].map((mood) => <Choice key={mood} active={narrative.moods?.includes(mood)} label={mood} onClick={() => {
                const moods = narrative.moods?.includes(mood) ? narrative.moods.filter((item) => item !== mood) : [...(narrative.moods ?? []), mood]
                onChange({ ...value, narrative: { ...narrative, moods } })
              }} />)}</div>
            </div>
          ) : null}
        </div>
      }
    >
      <StudioComposerChip icon={<ExperimentOutlined />} label="影棚" />
    </StudioCanvasPopover>
  )
}

function Choice({ active, label, onClick }: { active?: boolean; label: string; onClick: () => void }) {
  return <button type="button" className={active ? 'is-active' : ''} onClick={onClick}>{label}</button>
}

function PresetGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return <section><h4>{title}</h4><div>{children}</div></section>
}

function PresetChoice({ icon, label, description, onClick }: { icon: React.ReactNode; label: string; description: string; onClick: () => void }) {
  return <button type="button" onClick={onClick}><i>{icon}</i><span><strong>{label}</strong><small>{description}</small></span></button>
}
