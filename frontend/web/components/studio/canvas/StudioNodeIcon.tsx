import { StudioChromeIcon, type StudioChromeIconName } from '@/components/studio/theme/StudioChromeIcon'
import type { ComponentType, SVGProps } from 'react'
import {
  AppstoreOutlined,
  CompassOutlined,
  FileTextOutlined,
  FormOutlined,
  PictureOutlined,
  PlaySquareOutlined,
  ReadOutlined,
  ScissorOutlined,
  SkinOutlined,
  VideoCameraOutlined
} from '@ant-design/icons'
import type { StudioNodeKind } from '@/types/studio'

/** 与生成态 Demo 同一只描边音符，替代麦克风，避免音频节点看起来像录音入口。 */
function VoiceNoteIcon({ className, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      className={className}
      width="1em"
      height="1em"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      <path d="M8 18V5l12-2v13" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="18" cy="16" r="3" />
    </svg>
  )
}

const STUDIO_NODE_ICON: Record<StudioNodeKind, ComponentType<{ className?: string }>> = {
  image: PictureOutlined,
  video: PlaySquareOutlined,
  frame_breakdown: VideoCameraOutlined,
  smart_edit: ScissorOutlined,
  text: FileTextOutlined,
  storyboard_script: ReadOutlined,
  director: CompassOutlined,
  style_library: SkinOutlined,
  official_asset: AppstoreOutlined,
  voice: VoiceNoteIcon,
  note: FormOutlined
}

const CANVAS_NODE_ICON: Record<StudioNodeKind, StudioChromeIconName> = {
  image: 'nodeImage',
  video: 'nodeVideo',
  frame_breakdown: 'nodeFrames',
  smart_edit: 'nodeEdit',
  text: 'nodeText',
  storyboard_script: 'nodeStoryboard',
  director: 'nodeDirector',
  style_library: 'nodeStyle',
  official_asset: 'nodeAsset',
  voice: 'nodeVoice',
  note: 'nodeNote',
}

export function StudioNodeIcon({
  kind,
  variant = 'default'
}: {
  kind: StudioNodeKind
  variant?: 'default' | 'watermark' | 'chip'
}) {
  const Icon = STUDIO_NODE_ICON[kind]
  const className = `studio-node-icon studio-node-icon--${variant}`
  return <StudioChromeIcon name={CANVAS_NODE_ICON[kind]} size="1em" className={className} fallback={<Icon className={className} aria-hidden={variant !== 'default'} />} />
}
