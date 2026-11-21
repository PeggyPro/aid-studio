import { AppLoadingIndicator } from '~/components/common/AppLoadingIndicator'

interface CreateFlowStepLoadingProps {
  label?: string
}

export function CreateFlowStepLoading({
  label = '正在准备流程页面…'
}: CreateFlowStepLoadingProps) {
  return (
    <div
      className="create-step-swap-placeholder"
      role="status"
      aria-live="polite"
      aria-label={label}
    >
      <AppLoadingIndicator label={label} inline />
    </div>
  )
}
