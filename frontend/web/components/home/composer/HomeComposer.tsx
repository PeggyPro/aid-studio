'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { message } from 'antd'
import moviePosterRaw from '~/assets/img/home-v4/movie-front.webp'
import movieBackRaw from '~/assets/img/home-v4/movie-back.webp'
import movieSceneRaw from '~/assets/img/home-v4/movie-scene.webp'
import seriesPosterRaw from '~/assets/img/home-v4/series-front.webp'
import seriesBackRaw from '~/assets/img/home-v4/series-back.webp'
import seriesSceneRaw from '~/assets/img/home-v4/series-scene.webp'
import studioPosterRaw from '~/assets/img/home-v4/canvas-front.webp'
import studioBackRaw from '~/assets/img/home-v4/canvas-back.webp'
import studioSceneRaw from '~/assets/img/home-v4/canvas-scene.webp'
import tutorialPosterRaw from '~/assets/img/home-v4/tutorial-back.webp'
import HomeUpdates from '~/components/home/HomeUpdates'
import HomeComposerBox, {
  HomeComposerMiniBar,
  HomeComposerModeSwitch,
  type HomeComposerRatio
} from '~/components/home/composer/HomeComposerBox'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useCreateDefaultCanvasProject } from '~/hooks/useCreateDefaultCanvasProject'
import { useScriptLimits } from '~/hooks/useScriptLimits'
import { useCreationStore } from '~/stores/creation'
import type { GlobalSettingData } from '~/types'
import { HomeHeroField } from '~/components/home/theme/HomeHeroField'
import { useHomeTheme } from '~/components/home/theme/HomeThemeProvider'
import { assetUrl } from '~/utils/assetUrl'
import type { AspectRatioOption } from '~/utils/aspectRatioPicker'
import { CREATE_FIRST_STEP_DEFAULTS } from '~/utils/createFirstStepDefaults'
import { canvasAssistantAttachmentKind, canvasAssistantLibraryAttachment, type CanvasAssistantAttachment } from '@/utils/canvas/canvasAssistantAttachment'
import type { CanvasHomeAssistantChoices } from '@/utils/canvas/canvasHomeAssistantDraft'
import { apiErrorMessage } from '@/utils/apiError'
import { useCanvasGatewayIdentity } from '@/hooks/studio/useCanvasGatewayIdentity'
import {
  HOME_COMPOSER_ACTION_CARDS,
  homeComposerActionClickTarget,
  type HomeComposerActionCardDef
} from '~/utils/homeComposerActionCards'
import { readHomeScreenplayFile, resolveHomeScreenplayText } from '~/utils/homeComposerScreenplay'
import './home-composer.css'

type ActionCardImages = readonly [back: string, middle: string | null, front?: string]

const ACTION_CARD_IMAGE_URLS: Record<HomeComposerActionCardDef['imageFile'], ActionCardImages> = {
  'moive.png': [assetUrl(movieBackRaw), assetUrl(moviePosterRaw), assetUrl(movieSceneRaw)],
  'series.png': [assetUrl(seriesBackRaw), assetUrl(seriesPosterRaw), assetUrl(seriesSceneRaw)],
  'studio.png': [assetUrl(studioBackRaw), assetUrl(studioPosterRaw), assetUrl(studioSceneRaw)],
  'study.png': [assetUrl(tutorialPosterRaw), null]
}

const ACTION_CARD_DESCRIPTIONS = {
  movie: '电影 / 短片',
  series: '多集剧本，角色一致',
  studio: '图 · 视频 · 音频自由编排',
  tutorial: '三分钟上手'
} as const

const HOME_TITLE = '和 AID 一起'

const RATIO_OPTIONS: AspectRatioOption<HomeComposerRatio>[] = [
  { value: '16:9', label: '16:9' },
  { value: '9:16', label: '9:16' },
  { value: '4:3', label: '4:3' },
  { value: '3:4', label: '3:4' },
  { value: '1:1', label: '1:1' },
  { value: '21:9', label: '21:9' }
]

const MAX_COMPOSER_PROMPT_CHARS = 100_000

function isInsideComposerDockChrome(target: EventTarget | null, morph: HTMLElement | null) {
  if (!(target instanceof Node)) return false
  if (morph?.contains(target)) return true
  if (!(target instanceof Element)) return false
  return Boolean(
    target.closest('.skill-picker-popover, .canvas-skill-picker-popover, .studio-canvas-dropdown-popover') ||
      target.closest('.aspect-ratio-picker-popover') ||
      target.closest('.ant-select-dropdown') ||
      target.closest('.ant-modal-wrap') ||
      target.closest('[role="dialog"]')
  )
}

function cloneComposerSetting(): GlobalSettingData {
  const base = JSON.parse(
    JSON.stringify(useCreationStore.getState().formData.globalSetting)
  ) as GlobalSettingData
  return {
    ...base,
    scriptType: CREATE_FIRST_STEP_DEFAULTS.scriptType,
    modelStrategy: CREATE_FIRST_STEP_DEFAULTS.modelStrategy,
    creationMode: CREATE_FIRST_STEP_DEFAULTS.creationMode,
    aspectRatio: '16:9',
    selectedStyle: null,
    style: '',
    styleSelectionTouched: false,
    styleLocked: false,
    description: ''
  }
}

export interface HomeComposerProps {
  onTutorial: () => void
  onRequireLogin: (actionName?: string) => void
  isLoggedIn: boolean
}

export function HomeComposer({ onTutorial, onRequireLogin, isLoggedIn }: HomeComposerProps) {
  const homeCreateModal = useHomeShellCreateModal()
  const homeTheme = useHomeTheme()
  const composerRef = useRef<HTMLDivElement | null>(null)
  const morphRef = useRef<HTMLDivElement | null>(null)
  const [prompt, setPrompt] = useState('')
  const [canvasPrompt, setCanvasPrompt] = useState('')
  const { identity: canvasIdentity } = useCanvasGatewayIdentity()
  const [canvasOptions, setCanvasOptions] = useState(() => newCanvasOptions(canvasIdentity))
  const activeCanvasOptions = canvasOptions.identity === canvasIdentity ? canvasOptions : newCanvasOptions(canvasIdentity)
  const { choices: canvasChoices, attachments: canvasAttachments, skillName: canvasSkillName } = activeCanvasOptions
  const updateCanvasOptions = (update: (current: typeof canvasOptions) => typeof canvasOptions) => {
    setCanvasOptions(current => update(current.identity === canvasIdentity ? current : newCanvasOptions(canvasIdentity)))
  }
  const [mode, setMode] = useState<'flow' | 'canvas'>('canvas')
  const scriptLimits = useScriptLimits(mode === 'flow')
  const importingScript = useRef(false)
  const [readingScript, setReadingScript] = useState(false)
  const [setting, setSetting] = useState<GlobalSettingData>(() => cloneComposerSetting())
  const [heroVisible, setHeroVisible] = useState(true)
  const [dockExpanded, setDockExpanded] = useState(false)
  const [dockMounted, setDockMounted] = useState(false)
  const [dockShown, setDockShown] = useState(false)
  const [heroFlowing, setHeroFlowing] = useState(false)
  const [dockFlowing, setDockFlowing] = useState(false)
  const [miniFlowing, setMiniFlowing] = useState(false)
  const { createAndEnter: createCanvasAndEnter, creating: creatingCanvas } = useCreateDefaultCanvasProject()

  useEffect(() => {
    const el = composerRef.current
    if (!el || typeof IntersectionObserver === 'undefined') return undefined
    const observer = new IntersectionObserver(
      ([entry]) => {
        const visible = Boolean(entry?.isIntersecting && (entry.intersectionRatio ?? 0) > 0.12)
        setHeroVisible(visible)
        if (visible) setDockExpanded(false)
      },
      { root: el.closest('.home-new-main'), threshold: [0, 0.12, 0.4] }
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const showDock = !heroVisible

  useEffect(() => {
    if (showDock) {
      setDockMounted(true)
      let innerFrame = 0
      const outerFrame = window.requestAnimationFrame(() => {
        innerFrame = window.requestAnimationFrame(() => setDockShown(true))
      })
      return () => {
        window.cancelAnimationFrame(outerFrame)
        window.cancelAnimationFrame(innerFrame)
      }
    }
    setDockShown(false)
    const timer = window.setTimeout(() => setDockMounted(false), 380)
    return () => window.clearTimeout(timer)
  }, [showDock])

  useEffect(() => {
    if (!showDock || !dockExpanded) return undefined
    const focusFrame = window.requestAnimationFrame(() => morphRef.current?.querySelector('textarea')?.focus({ preventScroll: true }))
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setDockExpanded(false)
        window.requestAnimationFrame(() => morphRef.current?.querySelector<HTMLButtonElement>('.home-composer-mini')?.focus({ preventScroll: true }))
      }
    }
    function onPointerDown(event: PointerEvent) {
      if (isInsideComposerDockChrome(event.target, morphRef.current)) return
      setDockExpanded(false)
    }
    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.cancelAnimationFrame(focusFrame)
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [showDock, dockExpanded])

  useEffect(() => {
    const root = document.querySelector('.home-new-index')
    if (!root) return undefined
    root.classList.toggle('has-composer-dock', dockMounted)
    root.classList.toggle('has-composer-dock-expanded', dockMounted && dockExpanded)
    return () => {
      root.classList.remove('has-composer-dock', 'has-composer-dock-expanded')
    }
  }, [dockMounted, dockExpanded])

  async function onUploadScript(file: File) {
    if (importingScript.current || homeCreateModal.createConfirmLoading) return
    if (!isLoggedIn) {
      onRequireLogin('导入剧本')
      return
    }
    if (!scriptLimits) {
      message.warning('尚未取得剧本字数限制，请稍候重试')
      return
    }
    importingScript.current = true
    setReadingScript(true)
    try {
      const input = await readHomeScreenplayFile(file, scriptLimits)
      await homeCreateModal.createFromComposer({ input, globalSetting: setting })
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法读取该文件')
    } finally {
      importingScript.current = false
      setReadingScript(false)
    }
  }

  function onSend() {
    if (importingScript.current) return
    if (!isLoggedIn) {
      onRequireLogin('发送创作需求')
      return
    }
    if (mode === 'canvas') {
      if (!canvasPrompt.trim()) {
        message.warning('请描述你的画布创作想法')
        return
      }
      if (canvasPrompt.trim().length > MAX_COMPOSER_PROMPT_CHARS) {
        message.warning(`创作内容不能超过 ${MAX_COMPOSER_PROMPT_CHARS.toLocaleString('zh-CN')} 字`)
        return
      }
      const submittedPrompt = canvasPrompt
      void createCanvasAndEnter({
        ...canvasChoices,
        ...(canvasChoices.moduleCode === 'UNIVERSAL' ? {} : { selectedSkillCode: '', selectedModels: [] }),
        prompt: canvasPrompt.trim(),
        attachments: canvasAttachments
      }).then(created => {
        if (!created) return
        setCanvasPrompt(current => current === submittedPrompt ? '' : current)
        setCanvasOptions(current => current.identity === canvasIdentity && current.attachments === canvasAttachments ? { ...current, attachments: [] } : current)
      })
      return
    }
    if (prompt.trim().length > MAX_COMPOSER_PROMPT_CHARS) {
      message.warning(`创作内容不能超过 ${MAX_COMPOSER_PROMPT_CHARS.toLocaleString('zh-CN')} 字`)
      return
    }
    try {
      const input = resolveHomeScreenplayText(prompt, scriptLimits)
      void homeCreateModal.createFromComposer({ input, globalSetting: setting })
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法读取创作内容')
    }
  }

  function onCreateMovie() {
    if (!isLoggedIn) {
      onRequireLogin('制作大片')
      return
    }
    homeCreateModal.openCreateModal({ worksTab: 'film', hideProjectType: true })
  }

  function onCreateSeries() {
    if (!isLoggedIn) {
      onRequireLogin('制作连续剧')
      return
    }
    homeCreateModal.openCreateModal({ worksTab: 'series', hideProjectType: true })
  }

  function onOpenStudio() {
    if (!isLoggedIn) {
      onRequireLogin('使用无限画布')
      return
    }
    void createCanvasAndEnter()
  }

  const actionClicks = {
    movie: onCreateMovie,
    series: onCreateSeries,
    studio: onOpenStudio,
    tutorial: onTutorial
  }

  const boxProps = {
    mode,
    onModeChange: setMode,
    prompt: mode === 'flow' ? prompt : canvasPrompt,
    onPromptChange: (value: string) => {
      if (mode === 'canvas') { setCanvasPrompt(value); return }
      setPrompt(value)
    },
    setting,
    onSettingChange: setSetting,
    ratioValue: setting.aspectRatio,
    ratioOptions: RATIO_OPTIONS,
    onRatioChange: (value: HomeComposerRatio) =>
      setSetting((prev) => ({ ...prev, aspectRatio: value })),
    sending: homeCreateModal.createConfirmLoading || creatingCanvas || readingScript,
    canvasControls: {
      choices: canvasChoices,
      skillName: canvasSkillName,
      attachments: canvasAttachments,
      disabled: creatingCanvas,
      loggedIn: isLoggedIn,
      onChoicesChange: (changes: Partial<CanvasHomeAssistantChoices>) => updateCanvasOptions(current => ({ ...current, choices: { ...current.choices, ...changes } })),
      onSkillChange: (selectedSkillCode: string, name: string) => {
        updateCanvasOptions(current => ({ ...current, choices: { ...current.choices, selectedSkillCode }, skillName: name }))
      },
      onAdd: (items: CanvasAssistantAttachment[]) => {
        if (creatingCanvas || !isLoggedIn) return false
        if (canvasAttachments.length + items.length > 100) {
          void message.warning('一次最多携带 100 项素材')
          return false
        }
        try {
          const accepted = items.map(item => {
            if (!(item instanceof File)) return canvasAssistantLibraryAttachment(item)
            canvasAssistantAttachmentKind(item)
            return item
          })
          updateCanvasOptions(current => ({ ...current, attachments: [...current.attachments, ...accepted] }))
          return true
        } catch (cause) {
          void message.warning(apiErrorMessage(cause, '无法添加素材'))
          return false
        }
      },
      onRemove: (index: number) => updateCanvasOptions(current => ({ ...current, attachments: current.attachments.filter((_, itemIndex) => itemIndex !== index) }))
    },
    onUploadScript,
    onSend
  }

  return (
    <>
      <section
        className="home-composer-hero"
        aria-label="AI 创作"
      >
        <HomeUpdates />
        <HomeHeroField />
        <div className="home-composer-stage">
          <div className="home-hero-frame">
          <div className="home-title-row">
            <h1>
              <span className="home-title__word" aria-label={HOME_TITLE}>
                {Array.from(HOME_TITLE).map((char, index) => (
                  <span key={`${char}-${index}`} style={{ '--i': index } as CSSProperties} aria-hidden="true">
                    {char === ' ' ? '\u00A0' : char}
                  </span>
                ))}
              </span>
            </h1>
            <HomeComposerModeSwitch mode={mode} onChange={setMode} disabled={boxProps.sending} />
          </div>

          <div className="home-composer-main">
            <div ref={composerRef} className="home-composer-box-anchor">
              <HomeComposerBox
                {...boxProps}
                flowing={heroFlowing}
                onFlowingChange={setHeroFlowing}
              />
            </div>

            <div className="home-composer-actions">
              {[...HOME_COMPOSER_ACTION_CARDS].sort((a, b) =>
                ['movie', 'series', 'studio', 'tutorial'].indexOf(a.kind) -
                ['movie', 'series', 'studio', 'tutorial'].indexOf(b.kind)
              ).map((card) => {
                const clickTarget = homeComposerActionClickTarget(card.kind)
                return (
                  <ActionCard
                    key={card.kind}
                    title={card.title}
                    description={ACTION_CARD_DESCRIPTIONS[card.kind]}
                    imageUrls={ACTION_CARD_IMAGE_URLS[card.imageFile]}
                    comingSoonLabel={card.comingSoon ? card.comingSoonLabel : undefined}
                    onClick={clickTarget ? actionClicks[clickTarget] : undefined}
                    onboardingId={card.onboardingId}
                    busy={clickTarget === 'studio' && creatingCanvas}
                  />
                )
              })}
            </div>
          </div>
          </div>
        </div>
      </section>

      {dockMounted ? createPortal(
        <div className="home-ui-v4-shell home-ui-v4-floating" data-home-theme={homeTheme}><div className="home-new-index home-ui-v4"><div
          className={`home-composer-dock-layer${dockShown ? ' is-shown' : ''}${
            dockExpanded ? ' is-expanded' : ''
          }`}
        >
          <div ref={morphRef} className="home-composer-dock-morph">
            <div className="home-composer-dock-full" aria-hidden={!dockExpanded} inert={!dockExpanded}>
              <HomeComposerBox
                {...boxProps}
                variant="dock"
                toolsActive={dockExpanded}
                flowing={dockFlowing}
                onFlowingChange={setDockFlowing}
                onCollapse={() => setDockExpanded(false)}
              />
            </div>
            <HomeComposerMiniBar
              expanded={dockExpanded}
              onExpand={() => setDockExpanded(true)}
              flowing={miniFlowing}
              onFlowingChange={setMiniFlowing}
              prompt={boxProps.prompt}
              mode={mode}
            />
          </div>
        </div></div></div>, document.body
      ) : null}
    </>
  )
}

function newCanvasOptions(identity: string) {
  const choices: CanvasHomeAssistantChoices = {
    moduleCode: 'UNIVERSAL', mode: 'CRAFT', executionPreference: 'MANUAL', selectedSkillCode: '', selectedModels: []
  }
  return { identity, choices, skillName: '', attachments: [] as CanvasAssistantAttachment[] }
}

function ActionCard({
  title,
  description,
  imageUrls,
  comingSoonLabel,
  onClick,
  onboardingId,
  busy = false
}: {
  title: string
  description: string
  imageUrls: ActionCardImages
  comingSoonLabel?: string
  onClick?: () => void
  onboardingId?: string
  busy?: boolean
}) {
  const comingSoon = Boolean(comingSoonLabel)
  return (
    <button
      type="button"
      data-onboarding={onboardingId}
      disabled={comingSoon || busy}
      aria-busy={busy}
      onClick={onClick}
      className={`home-composer-action-card home-spot${comingSoon ? ' home-composer-action-card--coming-soon' : ''}`}
      aria-label={comingSoon && comingSoonLabel ? `${title}，${comingSoonLabel}` : busy ? `${title}，正在创建` : title}
    >
      <span className="home-composer-action-card__label">
        <strong>{comingSoon ? comingSoonLabel : busy ? '正在创建…' : title}</strong>
        <small>{description}</small>
      </span>
      <span className="home-composer-action-card__deck" aria-hidden="true">
        <i style={{ backgroundImage: `url(${imageUrls[0]})` }} />
        <i style={imageUrls[1] ? { backgroundImage: `url(${imageUrls[1]})` } : undefined} />
        {imageUrls[2] ? (
          <i style={{ backgroundImage: `url(${imageUrls[2]})` }} />
        ) : (
          <i className="is-play">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l10.5-6.5z" /></svg>
          </i>
        )}
      </span>
    </button>
  )
}

export default HomeComposer
