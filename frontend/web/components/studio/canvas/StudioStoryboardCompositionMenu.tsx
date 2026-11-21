'use client'

import { useMemo, useState } from 'react'
import { AppstoreOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import {
  buildStudioStoryboardCompositionDerivedUpdate,
  getStudioStoryboardComposition,
  listStudioStoryboardCompositionHeaderItems
} from '@/utils/studio/studioStoryboardComposition'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'

/** 自由画布图片头栏「分镜构图」：下拉选功能后派生下游节点并写入 composition 模式。 */
export function StudioStoryboardCompositionMenu({ node }: { node: StudioCanvasNode }) {
  const deriveImageNode = useStudioUiStore((state) => state.deriveImageNode)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const [open, setOpen] = useState(false)
  const disabled = !String(node.data.mediaUrl || '').trim()

  const items = useMemo(
    () => listStudioStoryboardCompositionHeaderItems().map((item) => ({
      key: item.id,
      label: item.label,
      icon: <AppstoreOutlined />
    })),
    []
  )

  const onSelect = (id: string) => {
    const item = getStudioStoryboardComposition(id)
    if (!item) return
    if (disabled) {
      message.warning('请先生成/上传图片')
      return
    }
    const derivedUpdate = buildStudioStoryboardCompositionDerivedUpdate(item)
    const generationOptions = {
      ...(node.data.generationOptions ?? {}),
      ...(derivedUpdate.generationOptions ?? {})
    }
    const targetId = deriveImageNode(
      node.id,
      generationOptions,
      derivedUpdate.imagePurpose ?? 'generic',
      item.label,
      ''
    )
    if (!targetId) {
      message.warning('无法派生图片节点')
      return
    }
    updateNodeData(targetId, {
      ...derivedUpdate,
      generationOptions,
      // Composition targets need their own prompt/quote/send controls before a Run exists.
      composerHidden: false,
      model: undefined,
      modelName: undefined,
      modelCostCredits: undefined,
      modelIsFree: undefined,
      prompt: '',
      status: 'empty'
    })
    selectNodes([targetId])
    setOpen(false)
  }

  return (
    <StudioCanvasDropdownPopover
      open={open}
      onOpenChange={setOpen}
      items={items}
      onSelect={onSelect}
      placement="bottom"
      ariaLabel="分镜构图"
      trigger={
        <StudioCanvasToolbarButton
          showLabel
          caret
          expanded={open}
          label="分镜构图"
          icon={<AppstoreOutlined aria-hidden />}
          disabled={disabled}
        />
      }
    />
  )
}
