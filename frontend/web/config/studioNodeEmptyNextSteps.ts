import type { StudioNodeKind } from '@/types/studio'

export type StudioEmptyPresetId =
  | 'text-to-video'
  | 'image-to-prompt'
  | 'text-to-audio'
  | 'image-to-image'
  | 'image-hd'
  | 'image-to-video'
  | 'first-frame-to-video'
  | 'first-last-frame-to-video'
  | 'audio-image-to-video'
  | 'script-to-storyboard'
  | 'characters-to-storyboard'

export type StudioNodeEmptyNextStepAction =
  | { type: 'enter-text-rich-edit' }
  | { type: 'apply-preset'; presetId: StudioEmptyPresetId }
  | { type: 'upload-then-link-img2img' }
  | { type: 'open-storyboard-workflow' }

export interface StudioNodeEmptyNextStep {
  id: string
  label: string
  action: StudioNodeEmptyNextStepAction
}

const NEXT_STEPS: Partial<Record<StudioNodeKind, readonly StudioNodeEmptyNextStep[]>> = {
  text: [
    { id: 'write', label: '自己编写', action: { type: 'enter-text-rich-edit' } },
    { id: 'text-video', label: '文生视频', action: { type: 'apply-preset', presetId: 'text-to-video' } },
    { id: 'image-prompt', label: '图片反推提示词', action: { type: 'apply-preset', presetId: 'image-to-prompt' } },
    { id: 'text-audio', label: '文字生音乐', action: { type: 'apply-preset', presetId: 'text-to-audio' } }
  ],
  image: [
    { id: 'image-image', label: '图生图', action: { type: 'upload-then-link-img2img' } },
    { id: 'image-hd', label: '图片高清', action: { type: 'apply-preset', presetId: 'image-hd' } }
  ],
  video: [
    { id: 'image-video', label: '图生视频', action: { type: 'apply-preset', presetId: 'image-to-video' } },
    { id: 'first-video', label: '首帧生视频', action: { type: 'apply-preset', presetId: 'first-frame-to-video' } },
    { id: 'first-last-video', label: '首尾帧生视频', action: { type: 'apply-preset', presetId: 'first-last-frame-to-video' } }
  ],
  voice: [
    { id: 'audio-video', label: '音频生视频', action: { type: 'apply-preset', presetId: 'audio-image-to-video' } }
  ],
  storyboard_script: [
    { id: 'write-storyboard', label: '自己编写分镜脚本', action: { type: 'open-storyboard-workflow' } },
    { id: 'script-storyboard', label: '剧本生成分镜脚本', action: { type: 'apply-preset', presetId: 'script-to-storyboard' } },
    { id: 'characters-storyboard', label: '角色生成分镜脚本', action: { type: 'apply-preset', presetId: 'characters-to-storyboard' } }
  ]
}

export function studioNodeEmptyNextSteps(kind: StudioNodeKind): readonly StudioNodeEmptyNextStep[] {
  return NEXT_STEPS[kind] ?? []
}
