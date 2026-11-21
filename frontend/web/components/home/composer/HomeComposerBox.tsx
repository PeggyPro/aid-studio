'use client'

import dynamic from 'next/dynamic'
import { forwardRef, useEffect, useRef, useState, type ButtonHTMLAttributes, type DragEvent, type ReactNode, type RefObject } from 'react'
import { CloseOutlined, PlusOutlined } from '@ant-design/icons'
import btnSendDisabledRaw from '~/assets/img/home/btn-send-h.svg'
import { AspectRatioPickerPopover } from '~/components/common/aspect-ratio-picker/AspectRatioPickerPopover'
import { ComposerSendButton } from '~/components/common/composer-send/ComposerSendButton'
import HomeComposerStyleField from '~/components/home/composer/HomeComposerStyleField'
import type { GlobalSettingData } from '~/types'
import type { AspectRatioOption } from '~/utils/aspectRatioPicker'
import { assetUrl } from '~/utils/assetUrl'
import { HomeSegmented } from '~/components/home/theme/HomeSegmented'
import { watchHomeMotion } from '~/components/home/theme/watchHomeMotion'
import { SCRIPT_UPLOAD_ACCEPT } from '~/utils/scriptFileUpload'
import HomeComposerOrbit from './HomeComposerOrbit'
import type { HomeCanvasAssistantControlsProps } from './HomeCanvasAssistantControls'
import './home-composer.css'

const HomeCanvasAssistantControls = dynamic(
  () => import('./HomeCanvasAssistantControls').then((mod) => mod.HomeCanvasAssistantControls),
  { ssr: false }
)
const HomeCanvasAssistantSelections = dynamic(
  () => import('./HomeCanvasAssistantControls').then((mod) => mod.HomeCanvasAssistantSelections),
  { ssr: false }
)

const sendDisabledIconUrl = assetUrl(btnSendDisabledRaw)

const FLOW_PLACEHOLDERS = [
  '描述想写的剧本，或上传已有的 TXT 剧本',
  '雨夜的九龙城寨，一个送外卖的少年撞见了不该看见的交易……',
  '民国上海，戏班花旦与巡捕房探长，一场持续十二集的猫鼠游戏',
  '修仙宗门大比，被逐出师门的废柴少年当众觉醒剑骨'
]

const CANVAS_PLACEHOLDERS = [
  '描述你想创作的画面、角色或镜头，发送后在新画布开始对话…',
  '赛博朋克雨夜，女主角回头特写，霓虹倒映在水洼里',
  '水墨风山门，大雪，镜头缓慢推进到匾额',
  '一只橘猫坐在地铁车厢里，窗外城市飞速掠过'
]

function useTypingPlaceholder(ref: RefObject<HTMLTextAreaElement | null>, active: boolean, lines: readonly string[]) {
  const firstLine = lines[0] ?? ''
  useEffect(() => {
    const textarea = ref.current
    if (!textarea) return
    textarea.placeholder = firstLine
    if (!active || !lines.length) return
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    let lineIndex = 0
    let cursor = 0
    let direction = 1
    let timer = 0
    let visible = false
    let delay = 400
    let deadline = 0
    const type = () => {
      deadline = performance.now() + delay
      timer = window.setTimeout(tick, delay)
    }
    const tick = () => {
      timer = 0
      if (!visible || document.hidden || motion.matches) return
      const line = lines[lineIndex] ?? ''
      cursor += direction
      // This is a visual-only attribute. Keep the editor, pickers and other controls out of the tick.
      textarea.placeholder = line.slice(0, Math.max(0, cursor))
      if (direction > 0 && cursor >= line.length) {
        direction = -1
        delay = 2200
      } else if (direction < 0 && cursor <= 0) {
        direction = 1
        lineIndex = (lineIndex + 1) % lines.length
        delay = 380
      } else delay = direction > 0 ? 46 : 16
      type()
    }
    const pause = () => {
      if (!timer) return
      window.clearTimeout(timer)
      timer = 0
      delay = Math.max(0, deadline - performance.now())
    }
    const onMotionChange = () => {
      pause()
      if (motion.matches) {
        textarea.placeholder = firstLine
        lineIndex = cursor = 0
        direction = 1
        delay = 400
      } else if (visible) type()
    }
    const unwatch = watchHomeMotion(textarea, (shown) => {
      visible = shown
      if (shown && !motion.matches) type()
      else pause()
    })
    motion.addEventListener('change', onMotionChange)
    return () => {
      pause()
      unwatch()
      motion.removeEventListener('change', onMotionChange)
      textarea.placeholder = firstLine
    }
  }, [active, firstLine, lines, ref])
  return firstLine
}

export type HomeComposerRatio = GlobalSettingData['aspectRatio']

export interface HomeComposerBoxProps {
  mode: 'flow' | 'canvas'
  onModeChange: (mode: 'flow' | 'canvas') => void
  prompt: string
  onPromptChange: (value: string) => void
  setting: GlobalSettingData
  onSettingChange: (value: GlobalSettingData) => void
  ratioValue: HomeComposerRatio
  ratioOptions: AspectRatioOption<HomeComposerRatio>[]
  onRatioChange: (value: HomeComposerRatio) => void
  sending?: boolean
  flowing?: boolean
  onFlowingChange?: (flowing: boolean) => void
  onUploadScript: (file: File) => void
  onSend: () => void
  onCollapse?: () => void
  variant?: 'hero' | 'dock'
  /** 浮动输入框收起时卸载工具及其 Portal，避免浮层脱离触发器继续显示。 */
  toolsActive?: boolean
  canvasControls?: HomeCanvasAssistantControlsProps
}

function UploadPlusIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
      <path d="M7 1.5V12.5M1.5 7H12.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

function ChevronIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
      <path
        d="M3 4.5L6 7.5L9 4.5"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

const ComposerChipTrigger = forwardRef<
  HTMLButtonElement,
  {
    label: string
    ariaLabel: string
    className?: string
    disabled?: boolean
    icon?: ReactNode
  } & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type' | 'children'>
>(function ComposerChipTrigger(
  { label, ariaLabel, className = '', disabled = false, icon, ...rest },
  ref
) {
  return (
    <button
      {...rest}
      ref={ref}
      type="button"
      aria-label={ariaLabel}
      aria-haspopup="listbox"
      disabled={disabled}
      className={['home-composer-select-trigger', className].filter(Boolean).join(' ')}
    >
      {icon}
      <span className="home-composer-select-trigger__label">{label}</span>
      <ChevronIcon />
    </button>
  )
})

export function HomeComposerBox({
  mode,
  onModeChange,
  prompt,
  onPromptChange,
  setting,
  onSettingChange,
  ratioValue,
  ratioOptions,
  onRatioChange,
  sending = false,
  flowing = false,
  onFlowingChange,
  onUploadScript,
  onSend,
  onCollapse,
  toolsActive = true,
  canvasControls,
  variant = 'hero'
}: HomeComposerBoxProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const dragDepthRef = useRef(0)
  const [focused, setFocused] = useState(false)
  const [dragging, setDragging] = useState(false)
  const isDock = variant === 'dock'
  const placement = isDock ? 'topLeft' : 'bottomLeft'
  const placeholder = useTypingPlaceholder(textareaRef, toolsActive && prompt.length === 0, mode === 'canvas' ? CANVAS_PLACEHOLDERS : FLOW_PLACEHOLDERS)

  function pickFile() {
    fileInputRef.current?.click()
  }

  function onDragEnter(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepthRef.current += 1
    setDragging(true)
  }

  function onDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
  }

  function onDragLeave() {
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setDragging(false)
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepthRef.current = 0
    setDragging(false)
    if (mode === 'canvas') {
      const files = Array.from(event.dataTransfer.files)
      if (files.length && !canvasControls?.disabled && canvasControls?.loggedIn) canvasControls.onAdd(files)
      return
    }
    const file = event.dataTransfer.files?.[0]
    if (file) onUploadScript(file)
  }

  return (
    <div
      data-home-canvas-composer={mode === 'canvas' ? '' : undefined}
      className={[
        'home-composer-box home-spot w-full',
        flowing ? 'is-flowing' : '',
        focused ? 'is-focus' : '',
        dragging ? 'is-drag' : '',
        prompt.trim() ? 'has-text' : ''
      ].filter(Boolean).join(' ')}
      onMouseEnter={() => onFlowingChange?.(true)}
      onMouseLeave={() => onFlowingChange?.(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        const next = event.relatedTarget
        if (next instanceof Node && event.currentTarget.contains(next)) return
        setFocused(false)
      }}
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      <HomeComposerOrbit active={toolsActive} />
      <div className="home-composer-box-inner">
        {isDock ? (
          <div className="home-composer-dock-head">
            <HomeComposerModeSwitch mode={mode} onChange={onModeChange} disabled={sending} />
            <button
              type="button"
              className="home-composer-dock-close"
              aria-label="收起创作输入框"
              onClick={onCollapse}
            >
              <CloseOutlined />
            </button>
          </div>
        ) : null}
        <input
          ref={fileInputRef}
          type="file"
          accept={SCRIPT_UPLOAD_ACCEPT}
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) onUploadScript(file)
          }}
        />
        <div className="home-composer-box-inner__top">
          {mode === 'canvas' && canvasControls ? <HomeCanvasAssistantSelections {...canvasControls} /> : null}
          <textarea
            ref={textareaRef}
            value={prompt}
            disabled={mode === 'canvas' && sending}
            onChange={(event) => onPromptChange(event.target.value)}
            aria-label={mode === 'canvas' ? '画布创作想法' : '创作想法或剧本'}
            placeholder={placeholder}
            rows={isDock ? 3 : 4}
            className="home-composer-textarea"
          />
        </div>

        <div className="home-composer-box-inner__bottom">
          {toolsActive ? <div className="flex min-w-0 flex-wrap items-center gap-2">
            {mode === 'flow' ? <>
            <button
              type="button"
              aria-label="上传剧本"
              className="home-composer-upload"
              onClick={pickFile}
            >
              <span className="home-composer-upload__icon">
                <PlusOutlined className="text-[18px] leading-none" />
              </span>
            </button>
            <HomeComposerStyleField modelValue={setting} onModelValueChange={onSettingChange} />
            <AspectRatioPickerPopover
              value={ratioValue}
              options={ratioOptions}
              placement={placement}
              ariaLabel="选择比例"
              onChange={onRatioChange}
            >
              <ComposerChipTrigger
                label={ratioValue}
                icon={<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" aria-hidden="true"><rect x="2" y="4" width="12" height="8" rx="1.5" /></svg>}
                ariaLabel="选择比例"
                className="home-composer-ratio-trigger"
              />
            </AspectRatioPickerPopover>
            </> : canvasControls ? <HomeCanvasAssistantControls {...canvasControls} /> : null}
          </div> : null}
          <div className="flex shrink-0 items-center gap-2">
            <span className="home-composer-count">{prompt.trim().length} 字</span>
            <ComposerSendButton
              hasContent={Boolean(prompt.trim())}
              mode="home"
              loading={sending}
              onSend={onSend}
            />
          </div>
        </div>
      </div>
    </div>
  )
}

export function HomeComposerMiniBar({
  expanded,
  onExpand,
  flowing,
  onFlowingChange,
  prompt,
  mode
}: {
  expanded: boolean
  onExpand: () => void
  flowing: boolean
  onFlowingChange: (flowing: boolean) => void
  prompt: string
  mode: 'flow' | 'canvas'
}) {
  return (
    <button
      type="button"
      aria-label="打开创作输入框"
      aria-expanded={expanded}
      aria-hidden={expanded}
      tabIndex={expanded ? -1 : undefined}
      className={`home-composer-mini home-spot ${flowing ? 'is-flowing' : ''}`}
      onMouseEnter={() => onFlowingChange(true)}
      onMouseLeave={() => onFlowingChange(false)}
      onClick={onExpand}
    >
      <HomeComposerOrbit active={!expanded} />
      <span className="home-composer-upload home-composer-upload--mini">
        <span className="home-composer-upload__icon">
          <UploadPlusIcon />
        </span>
      </span>
      <span className="home-composer-mini__mode">{mode === 'flow' ? '剧本成片' : '自由画布'}</span>
      <span className="home-composer-mini__placeholder">{prompt.trim() || (mode === 'flow' ? '写下灵感，或上传剧本…' : '描述画布创作想法…')}</span>
      <img src={sendDisabledIconUrl} alt="" width={32} height={32} className="h-8 w-8 shrink-0" />
    </button>
  )
}

export function HomeComposerModeSwitch({ mode, onChange, disabled = false }: {
  mode: 'flow' | 'canvas'
  onChange: (mode: 'flow' | 'canvas') => void
  disabled?: boolean
}) {
  return (
    <HomeSegmented
      className="home-seg--mode"
      ariaLabel="创作模式"
      value={mode}
      disabled={disabled}
      options={[
        { value: 'flow', label: '剧本成片' },
        { value: 'canvas', label: '自由画布' }
      ]}
      onChange={onChange}
    />
  )
}

export default HomeComposerBox
