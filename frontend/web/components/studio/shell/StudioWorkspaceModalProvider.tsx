'use client'

import { ConfigProvider } from 'antd'
import type { ReactNode } from 'react'
import './studio-workspace-modal.css'

/** Own the header geometry once, including dialogs rendered through portals by nested tools. */
export function StudioWorkspaceModalProvider({ children, rootClassName = '' }: { children: ReactNode; rootClassName?: string }) {
  return <ConfigProvider modal={{ centered: true, classNames: {
    root: `studio-workspace-modal ${rootClassName}`.trim(), container: 'studio-workspace-modal__container',
    header: 'studio-workspace-modal__header', title: 'studio-workspace-modal__title', close: 'studio-workspace-modal__close',
    body: 'studio-workspace-modal__body', footer: 'studio-workspace-modal__footer'
  } }}>{children}</ConfigProvider>
}
