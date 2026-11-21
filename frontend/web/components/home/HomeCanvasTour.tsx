'use client'

import { StudioAddNodeMenuLabel } from '@/components/studio/canvas/StudioAddNodeMenuLabel'
import { StudioFreeDotLattice, type StudioFreeDotFrame } from '@/components/studio/canvas/StudioFreeDotGrid'
import { StudioNodeExternalTitle, StudioImagePendingPreview } from '@/components/studio/canvas/StudioNodeExternalTitle'
import { StudioCanvasAssistantTurn } from '@/components/studio/shell/StudioCanvasAssistantTurn'
import { StudioFreeThemeScope } from '@/components/studio/theme/StudioFreeThemeProvider'
import type { StudioCanvasNode, StudioNodeKind } from '@/types/studio'
import { useV10Theme } from '@/utils/v10ThemePreference'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'
import { STUDIO_NODE_DEFAULT_DIMENSIONS } from '@/utils/studio/studioNodeLod'
import { canvasThemeFromSite } from '@/utils/studio/studioSiteTheme'
import { useEffect, useRef, useState, type CSSProperties, type MutableRefObject, type ReactNode } from 'react'
import '@/components/studio/studio-canvas.css'
import '@/components/studio/shell/studio-canvas-assistant.css'
import '@/components/studio/shell/studio-canvas-conversation.css'

const steps = [
  { description: '在首页选择「无限画布」，从一张空白画布开始创作。' },
  { description: '双击画布，或点击「添加节点」，选用文本、图片或视频节点。' },
  { description: '打开智能体，说出你想创作的内容，让它帮你把想法变成画布上的素材。' }
] as const

const stepDuration = 2200

export default function HomeCanvasTour() {
  const [step, setStep] = useState(0)
  const studioTheme = canvasThemeFromSite(useV10Theme())
  const stageRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => setStep(current => (current + 1) % steps.length), stepDuration)
    return () => window.clearTimeout(timer)
  }, [step])

  return (
    <section className="home-canvas-tour" aria-label="无限画布功能演示">
      <StudioFreeThemeScope className="home-canvas-tour__scope" themeOverride={studioTheme}>
        <div ref={stageRef} className="home-canvas-tour__stage studio-workspace studio-workspace--free">
          <TourDotField theme={studioTheme} pulse={step} stageRef={stageRef} />
          <div key={step} className="home-canvas-tour__scene" aria-hidden="true">
            {step === 0 ? <NewCanvasScene /> : step === 1 ? <AddNodeScene /> : <AgentScene />}
          </div>
        </div>
      </StudioFreeThemeScope>
      <p className="home-canvas-tour__description" aria-live="polite">{steps[step].description}</p>
    </section>
  )
}

function TourDotField({ theme, pulse, stageRef }: {
  theme: StudioFreeTheme
  pulse: number
  stageRef: MutableRefObject<HTMLDivElement | null>
}) {
  const source = useRef<StudioFreeDotFrame>({ theme, animate: true, x: 0, y: 0, zoom: 1, births: [] })
  const read = useRef<() => StudioFreeDotFrame>(() => source.current)
  const redrawRef = useRef<() => void>(() => {})
  const { width, height } = STUDIO_NODE_DEFAULT_DIMENSIONS.image
  source.current.theme = theme
  read.current = () => {
    const rect = stageRef.current?.getBoundingClientRect()
    source.current.x = rect?.width ? rect.width / 2 : 0
    source.current.y = rect?.height ? rect.height / 2 : 0
    return source.current
  }

  useEffect(() => { redrawRef.current() }, [theme])

  useEffect(() => {
    source.current.births = [{
      node: {
        id: `tour-birth-${pulse}`,
        type: 'studioNode',
        position: { x: -width / 2, y: -height / 2 },
        data: { kind: 'image' }
      } as StudioCanvasNode,
      started: performance.now()
    }]
    redrawRef.current()
  }, [height, pulse, width])

  return <StudioFreeDotLattice read={read} redrawRef={redrawRef} />
}

function NewCanvasScene() {
  return (
    <div className="home-canvas-tour__project">
      <div className="home-recent-card home-recent-card--new">
        <span className="home-recent-card__plus">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
            <path d="M9 3.25v11.5M3.25 9h11.5" />
          </svg>
        </span>
        <b>新建画布</b>
      </div>
    </div>
  )
}

function AddNodeScene() {
  return (
    <div className="home-canvas-tour__workflow">
      <TourNode kind="text" title="文本">
        <div className="studio-node-card__text-preview">
          <p>雨后巷口，一盏灯亮起。</p>
        </div>
      </TourNode>
      <div className="studio-node-card home-canvas-tour__menu home-canvas-tour__arrive">
        <div className="home-canvas-tour__menu-row is-current"><StudioAddNodeMenuLabel kind="image" /></div>
        <div className="home-canvas-tour__menu-row"><StudioAddNodeMenuLabel kind="video" /></div>
      </div>
    </div>
  )
}

function AgentScene() {
  return (
    <div className="home-canvas-tour__conversation">
      <TourNode kind="image" title="图片" placeholder>
        <StudioImagePendingPreview kind="image" />
      </TourNode>
      <div className="canvas-assistant-panel home-canvas-tour__agent home-canvas-tour__arrive">
        <div className="canvas-assistant-message is-user"><p>把这个夜景做成电影风格</p></div>
        <StudioCanvasAssistantTurn className="canvas-assistant-prose" label="画布助手已添加图片节点">
          <p>已添加图片节点</p>
        </StudioCanvasAssistantTurn>
        <div className="canvas-agent-composer">
          <div className="canvas-agent-composer__refs"><span>图片</span></div>
          <div className="canvas-agent-composer__field">
            <textarea readOnly tabIndex={-1} rows={2} value="" placeholder="描述创意需求，输入 / 选择 Skill，按 Enter 发送" />
          </div>
        </div>
      </div>
    </div>
  )
}

const nodeScale = 0.5
const nodeTitleSpace = 28

function TourNode({ kind, title, placeholder = false, children }: {
  kind: StudioNodeKind
  title: string
  placeholder?: boolean
  children: ReactNode
}) {
  const { width, height } = STUDIO_NODE_DEFAULT_DIMENSIONS[kind]
  const frame = {
    width: width * nodeScale,
    height: (height + nodeTitleSpace) * nodeScale
  } satisfies CSSProperties
  return (
    <div className="home-canvas-tour__node" style={frame}>
      <div className="home-canvas-tour__node-scale" style={{ transform: `scale(${nodeScale})` }}>
        <div className="studio-node-shell studio-node-shell--titled" style={{ width, height, marginTop: nodeTitleSpace }}>
          <StudioNodeExternalTitle kind={kind} title={title} readOnly />
          <article className={`studio-node-card studio-node-card--success${placeholder ? ' studio-node-card--placeholder studio-node-card--image-only' : ''}`} style={{ width, height }}>
            {children}
          </article>
        </div>
      </div>
    </div>
  )
}
