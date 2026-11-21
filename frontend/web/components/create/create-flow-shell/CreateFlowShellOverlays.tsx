'use client'

import { lazy, Suspense, type ComponentProps } from 'react'

const PublishCasePlazaModal = lazy(() => import('~/components/common/PublishCasePlazaModal'))
const ExtractAgentModal = lazy(() =>
  import('~/components/steps/ExtractAgentModal').then((module) => ({
    default: module.ExtractAgentModal
  }))
)
const CreateFirstStepModal = lazy(() =>
  import('~/components/steps/CreateFirstStepModal').then((module) => ({
    default: module.CreateFirstStepModal
  }))
)
const ProjectGenConfigModal = lazy(() =>
  import('~/components/steps/ProjectGenConfigModal').then((module) => ({
    default: module.ProjectGenConfigModal
  }))
)
interface CreateFlowShellOverlaysProps {
  extractModal: ComponentProps<typeof ExtractAgentModal>
  globalSettingModal: ComponentProps<typeof CreateFirstStepModal>
  projectConfigModal: ComponentProps<typeof ProjectGenConfigModal>
  publishModal: ComponentProps<typeof PublishCasePlazaModal>
}

export function CreateFlowShellOverlays(props: CreateFlowShellOverlaysProps) {
  return (
    <>
      <Suspense fallback={null}>
        <ExtractAgentModal {...props.extractModal} />
      </Suspense>
      <Suspense fallback={null}>
        <CreateFirstStepModal {...props.globalSettingModal} />
      </Suspense>
      <Suspense fallback={null}>
        <ProjectGenConfigModal {...props.projectConfigModal} />
      </Suspense>
      <Suspense fallback={null}>
        <PublishCasePlazaModal {...props.publishModal} />
      </Suspense>
    </>
  )
}
