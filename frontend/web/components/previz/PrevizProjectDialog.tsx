'use client'

import { Modal, type ModalProps } from 'antd'
import { StudioWorkspaceModalProvider } from '@/components/studio/shell/StudioWorkspaceModalProvider'
import './previz-project-library.css'

/** Project dialogs share the canvas frame and own a single scrolling body. */
export function PrevizProjectDialog(props: Omit<ModalProps, 'footer' | 'width' | 'destroyOnHidden'>) {
  return (
    <StudioWorkspaceModalProvider rootClassName="pv-modal pv-project-dialog">
      <Modal {...props} width={920} footer={null} destroyOnHidden />
    </StudioWorkspaceModalProvider>
  )
}
