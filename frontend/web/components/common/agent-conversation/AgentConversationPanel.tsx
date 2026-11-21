'use client'

import type { HTMLAttributes, ReactNode, Ref } from 'react'
import { Tooltip } from 'antd'
import foldIconRaw from '~/assets/img/icon/icon-fold.svg'
import { LiquidOrb, type LiquidOrbMode } from '@/components/common/liquid-orb/LiquidOrb'
import AgentConversationViewport, {
  type AgentConversationViewportHandle
} from '@/components/common/agent-conversation/AgentConversationViewport'
import { assetUrl } from '@/utils/assetUrl'
import './agent-conversation-panel.css'

const foldIconUrl = assetUrl(foldIconRaw)

export interface AgentConversationPanelProps {
  open?: boolean
  title?: string
  onClose?: () => void
  closeAriaLabel?: string
  className?: string
  surfaceClassName?: string
  headerClassName?: string
  headerProps?: Omit<HTMLAttributes<HTMLElement>, 'children' | 'className'>
  headerActions?: ReactNode
  closeIcon?: ReactNode
  brandClassName?: string
  messagesClassName?: string
  composerClassName?: string
  conversationRef?: Ref<AgentConversationViewportHandle>
  updateToken: string | number
  scopeKey?: string | number
  initialLoading?: boolean
  hasOlder?: boolean
  loadingOlder?: boolean
  onLoadOlder?: () => Promise<unknown> | void
  statusText?: string
  contextSlot?: ReactNode
  emptySlot?: ReactNode
  children: ReactNode
  composer: ReactNode
  orbMode?: LiquidOrbMode
  showBrandOrb?: boolean
  brandIcon?: ReactNode
  followContentResize?: boolean
}

export function AgentConversationPanel({
  open = true,
  title = '新对话',
  onClose,
  closeAriaLabel = '收起对话',
  className = '',
  surfaceClassName = 'agent-conversation-panel__surface',
  headerClassName = 'agent-conversation-panel__header',
  headerProps,
  headerActions,
  closeIcon,
  brandClassName = 'agent-conversation-panel__brand',
  messagesClassName = 'agent-conversation-panel__messages',
  composerClassName = 'agent-conversation-panel__composer',
  conversationRef,
  updateToken,
  scopeKey,
  initialLoading,
  hasOlder,
  loadingOlder,
  onLoadOlder,
  statusText,
  contextSlot,
  emptySlot,
  children,
  composer,
  orbMode = 'listening',
  showBrandOrb = true,
  brandIcon,
  followContentResize = false
}: AgentConversationPanelProps) {
  return (
    <div className={`agent-conversation-panel ${className}`.trim()}>
      <div className={surfaceClassName}>
        <header className={headerClassName} {...headerProps}>
          <div className={brandClassName}>
            {brandIcon ?? (showBrandOrb ? (
              <LiquidOrb
                className="agent-conversation-panel__brand-icon"
                size={28}
                theme="dark"
                mode={orbMode}
                interactive={false}
                webgl={false}
                ariaHidden
              />
            ) : null)}
            <strong>{title}</strong>
          </div>
          <div className="agent-conversation-panel__header-actions">
            {headerActions}
            {onClose ? (
              <Tooltip title={closeAriaLabel} placement="bottom">
                <button type="button" aria-label={closeAriaLabel} onClick={onClose}>
                  {closeIcon ?? <img src={foldIconUrl} alt="" width={20} height={20} />}
                </button>
              </Tooltip>
            ) : null}
          </div>
        </header>
        {statusText ? (
          <span className="sr-only" aria-live="polite">{statusText}</span>
        ) : null}
        <AgentConversationViewport
          ref={conversationRef}
          open={open}
          className={messagesClassName}
          updateToken={updateToken}
          scopeKey={scopeKey}
          initialLoading={initialLoading}
          hasOlder={hasOlder}
          loadingOlder={loadingOlder}
          onLoadOlder={onLoadOlder}
          followContentResize={followContentResize}
        >
          {emptySlot}
          {children}
        </AgentConversationViewport>
        {contextSlot}
        <footer className={composerClassName}>{composer}</footer>
      </div>
    </div>
  )
}

export default AgentConversationPanel
