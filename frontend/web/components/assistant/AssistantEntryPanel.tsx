'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import './assistant.css'
import '@/components/studio/shell/studio-canvas-assistant.css'
import '@/components/studio/shell/studio-canvas-assistant-interaction.css'
import './assistant-entry-studio.css'
import type { ReactNode } from 'react'
import { Button, Drawer, Select, Tooltip } from 'antd'
import { CloseOutlined, HistoryOutlined } from '@ant-design/icons'
import { ComposerSendGlyph } from '@/components/common/composer-send/ComposerSendButton'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { ASSISTANT_HISTORY_MORE_KEY, studioAssistantHistoryItems } from '@/components/studio/canvas/studioAssistantHistoryMenu'
import { useUserStore } from '~/stores/user'
import type { AssistantCapabilities, AssistantMode, AssistantModule } from '~/types/assistant-api'
import { createAssistantApi, mainAssistantTransport } from '~/utils/business/assistant'
import { AssistantSessionPanel, type AssistantSessionPanelProps, type AssistantSessionPickerProps } from './AssistantSessionPanel'

const mobileQuery = '(max-width: 767px)'
function subscribeMobile(listener: () => void) {
  const query = window.matchMedia(mobileQuery)
  query.addEventListener('change', listener)
  return () => query.removeEventListener('change', listener)
}
const mobileSnapshot = () => window.matchMedia(mobileQuery).matches
const desktopServerSnapshot = () => false

function StudioHistoryPicker({ picker }: { picker: AssistantSessionPickerProps }) {
  const [open, setOpen] = useState(false)
  const selected = picker.options.find(option => option.value === picker.value)
  return <StudioCanvasDropdownPopover items={studioAssistantHistoryItems(picker, <HistoryOutlined />)} activeKey={picker.value}
    open={open} onOpenChange={setOpen} placement="bottomLeft" ariaLabel="历史对话"
    onSelect={key => { if (key === ASSISTANT_HISTORY_MORE_KEY) picker.onLoadMore?.(); else picker.onChange(key) }}
    trigger={<StudioCanvasTooltip title="历史对话"><button type="button" className="assistant-entry-history" aria-label="历史对话"
      aria-haspopup="menu" aria-expanded={open} disabled={picker.disabled || (!picker.options.length && !picker.hasMore)}>
      <HistoryOutlined aria-hidden /><span>{selected?.label || '历史对话'}</span>
    </button></StudioCanvasTooltip>} />
}

export function AssistantEntryPanel({ open, onClose, legacyPanel, embedded, ...props }: Omit<AssistantSessionPanelProps, 'actorId'> & {
  open: boolean; onClose: () => void; legacyPanel?: ReactNode; embedded?: boolean
}) {
  const mobile = useSyncExternalStore(subscribeMobile, mobileSnapshot, desktopServerSnapshot)
  const trigger = useRef<{ element: HTMLElement; id: string; label: string } | null>(null)
  useEffect(() => {
    if (open) return
    const remember = (event?: Event) => {
      const target = event?.target || document.activeElement
      if (!(target instanceof HTMLElement)) return
      const element = target.closest<HTMLElement>('button, a, input, [tabindex]')
      if (element) trigger.current = { element, id: element.id, label: element.getAttribute('aria-label') || '' }
    }
    remember()
    document.addEventListener('focusin', remember)
    document.addEventListener('pointerdown', remember, true)
    return () => { document.removeEventListener('focusin', remember); document.removeEventListener('pointerdown', remember, true) }
  }, [open])
  const close = () => {
    const saved = trigger.current
    onClose()
    requestAnimationFrame(() => {
      const element = saved?.element.isConnected ? saved.element : saved?.id ? document.getElementById(saved.id)
        : saved?.label ? [...document.querySelectorAll<HTMLElement>('[aria-label]')].find((item) => item.getAttribute('aria-label') === saved.label && item.getClientRects().length) : null
      element?.focus()
    })
  }
  const actorId = useUserStore((state) => state.user?.id || '')
  const token = useUserStore((state) => state.token)
  const [legacy, setLegacy] = useState(false)
  const [projectScope, setProjectScope] = useState(false)
  const [moduleCode, setModule] = useState<AssistantModule>(props.scope.moduleCode)
  const [mode, setMode] = useState<AssistantMode>(props.scope.mode || 'CRAFT')
  const [capabilities, setCapabilities] = useState<AssistantCapabilities | null>(null)
  const transport = props.transport || mainAssistantTransport
  useEffect(() => {
    if (!open || !actorId || !token) return
    let active = true
    const api = createAssistantApi(transport)
    void api.capabilities().then((value) => { if (active) setCapabilities(value) }).catch(() => { if (active) setCapabilities(null) })
    return () => { active = false; setCapabilities(null) }
  }, [actorId, token, open, transport])
  if (!open) return null
  const entireProject = projectScope && moduleCode === 'SCREENWRITER' && !!props.seriesAdapter && transport.kind === 'MAIN'
  const scope = { ...props.scope, moduleCode, mode: moduleCode === 'SCREENWRITER' ? mode : undefined, episodeId: entireProject ? '0' : props.scope.episodeId }
  const panel = <aside className={embedded || mobile ? 'assistant-entry canvas-assistant-panel flex h-full min-h-0 w-full min-w-0 flex-col' : 'assistant-entry canvas-assistant-panel assistant-entry-floating flex h-full min-h-0 min-w-0 shrink-0 flex-col'} aria-label="创作助手">
    <header className="assistant-entry-header flex shrink-0 flex-wrap items-center gap-2 border-b border-[var(--studio-ui-border,rgba(255,255,255,0.1))] p-3">
      <Button type={!legacy ? 'primary' : 'default'} size="small" aria-pressed={!legacy} onClick={() => setLegacy(false)}>助手</Button>
      {legacyPanel ? <Button type={legacy ? 'primary' : 'default'} size="small" aria-pressed={legacy} onClick={() => setLegacy(true)}>原有会话</Button> : null}
      <Tooltip title="关闭助手"><Button type="text" size="small" className="assistant-close" onClick={close} aria-label="关闭助手" icon={<CloseOutlined />} /></Tooltip>
      {!legacy ? <div className="assistant-entry-settings flex w-full gap-2">
        <Select aria-label="助手模块" className="min-w-0 flex-1" value={moduleCode} disabled={!capabilities}
          options={(capabilities?.modules || [moduleCode]).filter((value) => value !== 'SPATIAL_PREVIS' && value !== 'PREVIZ_3DBOX')
            .map((value) => ({ value, label: value === 'UNIVERSAL' ? '全能助手' : '创作编剧助手' }))} onChange={setModule} />
        {moduleCode === 'SCREENWRITER' ? <Select aria-label="创作模式" value={mode} className="w-28" disabled={!capabilities}
          options={(capabilities?.screenwriterModes || [mode]).map((value) => ({ value, label: value === 'CRAFT' ? '原创' : '编辑 / 改编' }))} onChange={setMode} /> : null}
      </div> : null}
      {!legacy && moduleCode === 'SCREENWRITER' && props.seriesAdapter && transport.kind === 'MAIN' ? <label className="flex min-h-11 w-full items-center gap-2 text-xs">会话范围
        <Select aria-label="编剧会话范围" className="min-w-0 flex-1" value={entireProject ? 'PROJECT' : 'EPISODE'} onChange={value => setProjectScope(value === 'PROJECT')}
          options={[{ value: 'EPISODE', label: '当前集' }, { value: 'PROJECT', label: '整个项目（多集创作）' }]} />
      </label> : null}
      {entireProject && !legacy ? <p className="w-full text-xs">项目会话单独保存消息与授权，不沿用当前集的运行授权。</p> : null}
    </header>
    <div className="min-h-0 flex-1 overflow-hidden">
      {legacy ? legacyPanel : <AssistantSessionPanel key={moduleCode + ':' + mode + ':' + props.scope.projectId + ':' + scope.episodeId + ':' + actorId + ':' + transport.identityKey()} {...props} studioLayout composerSendIcon={hasContent => <ComposerSendGlyph empty={!hasContent} />}
        renderSessionPicker={picker => <StudioHistoryPicker picker={picker} />} adoptionAdapter={entireProject ? undefined : props.adoptionAdapter} seriesAdapter={entireProject ? props.seriesAdapter : undefined} scope={scope} actorId={token ? String(actorId) : ''} />}
    </div>
  </aside>
  return mobile && !embedded ? <Drawer open={open} onClose={close} aria-label="创作助手" placement="right" size="100%"
    closeIcon={false} keyboard focusable={{ trap: true, focusTriggerAfterClose: true }} destroyOnHidden
    rootClassName="assistant-mobile-drawer" zIndex={1100}
    styles={{ wrapper: { width: '100%', maxWidth: '100%' }, body: { padding: 0, overflow: 'hidden' }, section: { background: 'var(--create-surface-modal, #191a1d)' } }}>
    {panel}
  </Drawer> : panel
}
