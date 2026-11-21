'use client'

import { LinkOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'

export function StudioLibraryComposer({ node }: { node: StudioCanvasNode }) {
  return (
    <div className="studio-composer studio-composer--library">
      <LinkOutlined />
      <div><strong>{node.data.title}</strong><span>从此节点拖线，只能添加图片节点</span></div>
    </div>
  )
}
