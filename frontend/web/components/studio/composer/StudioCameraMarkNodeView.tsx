'use client'

/* eslint-disable @next/next/no-img-element -- 运镜封面来自词库运行时地址。 */
import type { MouseEvent } from 'react'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/react'
import { usePromptDictionary } from '@/hooks/usePromptDictionary'
import type { StudioCameraMark } from '@/utils/studio/studioCameraMark'
import type { StudioCameraMarkOptions } from './studioCameraMarkExtension'
import { StudioDictionaryPreview } from './StudioDictionaryPreview'

function markFromAttrs(attrs: NodeViewProps['node']['attrs']): StudioCameraMark {
  return {
    id: String(attrs.id || ''),
    kind: attrs.kind === 'technique' || attrs.kind === 'shot_size' || attrs.kind === 'camera_angle' ? attrs.kind : 'motion',
    optionKey: String(attrs.optionKey || ''),
    label: String(attrs.label || ''),
    description: attrs.description || undefined
  }
}

export function StudioCameraMarkNodeView(props: NodeViewProps) {
  const mark = markFromAttrs(props.node.attrs)
  const { cameraMovementOptions, shootingTechniqueOptions, shotSizeOptions, cameraAngleOptions } = usePromptDictionary()
  const options = props.extension.options as StudioCameraMarkOptions
  const catalog = {
    motion: cameraMovementOptions,
    technique: shootingTechniqueOptions,
    shot_size: shotSizeOptions,
    camera_angle: cameraAngleOptions
  }[mark.kind] ?? cameraMovementOptions
  const rawUrl = String(catalog.find((item) => item.key === mark.optionKey)?.image || '').trim()

  return (
    <NodeViewWrapper
      as="span"
      className="studio-composer-mention"
      contentEditable={false}
      onClick={(event: MouseEvent<HTMLElement>) => {
        event.preventDefault()
        event.stopPropagation()
        options.onCameraMarkClick?.(mark)
      }}
    >
      <StudioDictionaryPreview key={rawUrl} url={rawUrl} label={`${mark.label}预览`} fallback={<i aria-hidden />} />
      <span className="studio-composer-mention__label">{mark.label}</span>
    </NodeViewWrapper>
  )
}
