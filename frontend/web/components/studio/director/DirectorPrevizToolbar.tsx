'use client'
import { useCallback, useRef, useState } from 'react'
import { Button, Switch, Tour } from 'antd'
import { LeftOutlined, QuestionCircleOutlined, RightOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { useAccountOnboardingOnce } from '@/hooks/useAccountOnboardingOnce'
import type { DirectorSceneData } from '@/types/studio'

const STEPS = [
  { title: '1. 布置场景', description: '从左侧「场景」添加地面、方块等道具，或在「模型资产」选择可用模型。选中物体后，底部「对象操作」可切换移动、旋转和缩放，右侧可精确修改参数。', target: 'scene' },
  { title: '2. 放置人物并添加动作', description: '点击左侧「添加角色」，选择一个人物预设。选中人物后，打开左侧「动作库」添加走路、跑步或其他动作，也可以在时间轴「追加动作」。动作控制身体，运动轨迹决定走位和方向。', target: 'actor' },
  { title: '3. 设置机位和画幅', description: '用左侧「添加机位」放置镜头。选中机位后，可在右侧调整位置、目标、焦距和角度。左侧「选择画幅比例」控制构图参考，右上「机位监视器」可检查镜头看到的内容。', target: 'camera' },
  { title: '4. 打开时间轴并画出走位', description: '点底部「动画时间轴」，在角色或机位的轨道上选「添加轨迹」。角色走位先用 XZ 水平面和「铅笔绘制」，在视口按住拖画后松手。已有轨道之外，可用「新建轨道」添加对象。', target: 'timeline' },
  { title: '5. 调整轨迹、动作和时长', description: '拖片段中间调整开始时间，拖右端调整时长，单击可编辑片段参数。「编辑范围」控制时间轴范围，播放和录制以已编排内容的结束位置为准。可在不同时间记录机位关键帧。', target: 'ruler' },
  { title: '6. 播放并检查构图', description: '点播放按钮或按空格预演，拖动刻度可停在某一帧。顶部切换「导演视角 / 机位视角」检查走位与构图。编排了分镜时，用时间轴「预览分镜」检查镜头切换。', target: 'playback' },
  { title: '7. 录制白模视频', description: '点「录制白模视频」，选择机位视角或导演视角、分辨率和是否显示名牌，再点「开始录制」。录制为 30 fps，最长 120 秒，不消耗积分。保持页面可见，等待上传到画布。', target: 'record' },
  { title: '8. 回到画布检查视频', description: '录制成功后会生成白模视频节点。点顶部「保存」再退出，可在画布播放成品、检查走位与切点。若上传失败，可在机位监视器重试上传。', target: 'save' },
  { title: '9. 用白模准备 AI 成片', description: '需要生成正式画面时，回到同一个 3D预演，点「AI 生成成片」，画布会建立引用白模的视频草稿。补充美术参考图，选择支持参考视频的模型并核对提示词、报价，确认后再发起生成。', target: 'finish' },
] as const

export function DirectorPrevizToolbar({
  scene,
  onChange,
  recording,
  openingFinish,
  tutorialReady,
  onOpenRecording,
  onOpenFinish,
  onOpenTimeline,
}: {
  scene: DirectorSceneData
  onChange: (s: DirectorSceneData) => void
  recording: boolean
  openingFinish: boolean
  tutorialReady: boolean
  onOpenRecording: () => void
  onOpenFinish: () => void
  onOpenTimeline: () => void
}) {
  const toolbar = useRef<HTMLDivElement>(null)
  const [step, setStep] = useState(-1)
  const [moreOpen, setMoreOpen] = useState(false)
  const [manuallyOpened, setManuallyOpened] = useState(false)
  const open = useCallback(() => setStep(0), [])
  const finish = useAccountOnboardingOnce('studio-previz', tutorialReady && step < 0 && !manuallyOpened, open)
  const close = () => {
    setStep(-1)
    // Ant Tour invokes onClose immediately before onFinish on the final step.
    queueMicrotask(() => finish('skipped'))
  }
  const openTutorial = () => {
    setManuallyOpened(true)
    open()
  }

  return (
    <div ref={toolbar} className="director-toolbar" role="toolbar" aria-label="预演工具">
      <div className="director-toolbar__primary">
        <div className="director-toolbar__cluster" role="group" aria-label="白模录制与成片">
          <StudioCanvasTooltip title="设置视角和清晰度，录制并保存到画布">
            <Button size="small" type="primary" icon={<VideoCameraOutlined />} data-previz-step="record"
              disabled={recording || openingFinish} onClick={onOpenRecording}>录制白模视频</Button>
          </StudioCanvasTooltip>
          <StudioCanvasTooltip title="用白模视频准备成片草稿；没有录制时先打开录制设置">
            <Button size="small" data-previz-step="finish" loading={openingFinish} disabled={recording} onClick={onOpenFinish}>AI 生成成片</Button>
          </StudioCanvasTooltip>
        </div>
        <div className={`director-toolbar__more${moreOpen ? ' is-open' : ''}`}>
          <div id="director-toolbar-more-tray" className="director-toolbar__more-tray" aria-hidden={!moreOpen} inert={!moreOpen}>
            <label className="director-toolbar__switch">
              半透
              <Switch
                size="small"
                checked={scene.viewportPrefs?.shading === 'xray'}
                onChange={(checked) =>
                  onChange({ ...scene, viewportPrefs: { ...scene.viewportPrefs!, shading: checked ? 'xray' : 'solid' } })
                }
              />
            </label>
            <label className="director-toolbar__switch">
              名牌
              <Switch
                size="small"
                checked={scene.viewportPrefs?.showNameplates}
                onChange={(showNameplates) =>
                  onChange({ ...scene, viewportPrefs: { ...scene.viewportPrefs!, showNameplates } })
                }
              />
            </label>
          </div>
          <Button
            size="small"
            className={`director-toolbar__expand${moreOpen ? ' is-active' : ''}`}
            aria-label={moreOpen ? '收起附加工具' : '展开附加工具'}
            aria-expanded={moreOpen}
            aria-controls="director-toolbar-more-tray"
            onClick={() => setMoreOpen((open) => !open)}
          >
            {moreOpen ? <LeftOutlined /> : <RightOutlined />}
          </Button>
        </div>
      </div>
      <StudioCanvasTooltip title="打开 3D预演教程">
        <Button size="small" type="text" className="director-toolbar__help" icon={<QuestionCircleOutlined />}
          aria-label="打开 3D预演教程" onClick={openTutorial} />
      </StudioCanvasTooltip>
      <Tour
        open={step >= 0}
        current={Math.max(0, step)}
        onChange={index => {
          if (STEPS[index]?.target === 'ruler' || STEPS[index]?.target === 'playback') onOpenTimeline()
          setStep(index)
        }}
        onClose={close}
        onFinish={() => finish('completed')}
        steps={STEPS.map(({ title, description, target }, index) => ({
          title,
          description,
          target: () => {
            const stage = toolbar.current?.closest('.director-stage')
            return stage?.querySelector<HTMLElement>('[data-previz-step="' + target + '"]')
              ?? toolbar.current!
          },
          nextButtonProps: { children: index === STEPS.length - 1 ? '开始练习' : '下一步' },
          prevButtonProps: { children: '上一步' },
        }))}
      />
    </div>
  )
}
