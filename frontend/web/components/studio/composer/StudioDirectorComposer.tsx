'use client'

import { useRef } from 'react'
import { CloudUploadOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { upsertComposerRef } from '@/utils/studio/studioComposerRefs'
import { createTrackedObjectUrl } from '@/utils/objectUrl'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioComposerFrame } from './StudioComposerFrame'

export function StudioDirectorComposer({ node }: { node: StudioCanvasNode }) {
  const inputRef = useRef<HTMLInputElement>(null)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  return (
    <>
      <StudioComposerFrame
        node={node}
        placeholder="上传参考图，或描述导演构图目标"
        bottomLeft={
          <StudioComposerChip icon={<CloudUploadOutlined />} label="上传" arrow={false} onClick={() => inputRef.current?.click()} />
        }
      />
      <input
        ref={inputRef}
        hidden
        type="file"
        accept="image/*"
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (!file) return
          const previewUrl = createTrackedObjectUrl(file)
          updateNodeData(node.id, {
            prompt: `已识别：${file.name}。请基于该参考完成导演构图预览。`,
            composerRefs: upsertComposerRef(node.data.composerRefs, {
              id: `upload:${file.name}:${file.lastModified}`,
              kind: 'upload',
              title: file.name,
              mediaKind: 'image',
              previewUrl,
              channel: 'attachment'
            })
          })
          event.target.value = ''
        }}
      />
    </>
  )
}
