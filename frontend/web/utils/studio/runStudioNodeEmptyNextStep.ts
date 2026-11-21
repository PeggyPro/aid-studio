import type { StudioNodeEmptyNextStep } from '@/config/studioNodeEmptyNextSteps'
import { useStudioUiStore } from '@/stores/studioUi'
import { requestStudioTextEditorFocus } from '@/utils/studio/studioTextEditorEvents'

export function runStudioNodeEmptyNextStep(input: {
  nodeId: string
  step: StudioNodeEmptyNextStep
  requestImageUpload: () => void
}): void {
  const store = useStudioUiStore.getState()
  if (store.studioMode !== 'free') return

  if (input.step.action.type === 'upload-then-link-img2img') {
    input.requestImageUpload()
    return
  }

  if (input.step.action.type === 'apply-preset') {
    store.applyEmptyPreset(input.nodeId, input.step.action.presetId)
    return
  }

  store.updateNodeData(input.nodeId, {
    emptyNextStepConsumed: true,
    ...(input.step.action.type === 'enter-text-rich-edit' ? { textEditingEnabled: true } : {})
  }, true)
  store.selectNodes([input.nodeId])

  if (input.step.action.type === 'enter-text-rich-edit') {
    window.requestAnimationFrame(() => requestStudioTextEditorFocus(input.nodeId))
    return
  }

  window.requestAnimationFrame(() => {
    window.dispatchEvent(new CustomEvent('studio:open-storyboard-editor', { detail: { nodeId: input.nodeId } }))
  })
}
