'use client'

import type { StudioCanvasNode } from '@/types/studio'
import type { ReactNode } from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioDirectorComposer } from './StudioDirectorComposer'
import { StudioImageComposer } from './StudioImageComposer'
import { StudioLibraryComposer } from './StudioLibraryComposer'
import { StudioScriptComposer } from './StudioScriptComposer'
import { StudioSimpleComposer } from './StudioSimpleComposer'
import { StudioSmartEditComposer } from './StudioSmartEditComposer'
import { StudioVideoComposer } from './StudioVideoComposer'
import { StudioVideoSubjectComposer } from './StudioVideoSubjectComposer'
import { StudioFlowComposerRouter } from '@/components/studio/flow/composer/StudioFlowComposerRouter'
import './studio-composer.css'

export function StudioNodeComposer({ node }: { node: StudioCanvasNode }) {
  const studioMode = useStudioUiStore((state) => state.studioMode)
  if (node.data.flowBinding) return <StudioFlowComposerRouter node={node} />
  let content: ReactNode = null
  switch (node.data.kind) {
    case 'smart_edit': content = <StudioSmartEditComposer node={node} />; break
    case 'image': content = <StudioImageComposer node={node} />; break
    case 'video': content = node.data.videoSubjectEdit ? <StudioVideoSubjectComposer node={node} /> : <StudioVideoComposer node={node} />; break
    case 'storyboard_script': content = <StudioScriptComposer node={node} />; break
    case 'director': content = <StudioDirectorComposer node={node} />; break
    case 'style_library':
    case 'official_asset': content = <StudioLibraryComposer node={node} />; break
    case 'text':
    case 'voice':
    case 'note': content = <StudioSimpleComposer node={node} />; break
  }
  if (studioMode !== 'flow') return content
  return (
    <div className="studio-composer-canvas-only">
      <div className="studio-composer-canvas-only__notice">
        <strong>画布专属 · 本地预演</strong>
        <span>此节点及其 Mock 结果不会写入七步流程业务数据。</span>
      </div>
      {content}
    </div>
  )
}
