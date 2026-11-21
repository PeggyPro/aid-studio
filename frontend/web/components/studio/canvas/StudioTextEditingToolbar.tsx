'use client'

import { forwardRef, memo, useCallback, useImperativeHandle, useLayoutEffect, useRef } from 'react'
import {
  BgColorsOutlined,
  BoldOutlined,
  CopyOutlined,
  ExpandAltOutlined,
  ItalicOutlined,
  MenuOutlined,
  OrderedListOutlined,
  UnorderedListOutlined
} from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioTextBackgroundId } from '@/types/studio'
import { placeStudioNodeMediaToolbar, type StudioMediaToolbarRect } from '@/utils/studio/studioNodeMediaToolbar'
import { dispatchStudioTextEditorCommand } from '@/utils/studio/studioTextEditorEvents'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'

export interface StudioTextEditingToolbarHandle {
  reposition: (selectionRect: StudioMediaToolbarRect, viewportRect: StudioMediaToolbarRect) => void
}

const BACKGROUNDS: Array<{ key: StudioTextBackgroundId; label: string; color: string }> = [
  { key: 'neutral', label: '默认', color: 'var(--studio-text-background-neutral)' },
  { key: 'paper', label: '纸白', color: 'var(--studio-text-background-paper)' },
  { key: 'mist', label: '雾蓝', color: 'var(--studio-text-background-mist)' },
  { key: 'lavender', label: '淡紫', color: 'var(--studio-text-background-lavender)' },
  { key: 'sage', label: '鼠尾草', color: 'var(--studio-text-background-sage)' }
]

export const StudioTextEditingToolbar = memo(forwardRef<StudioTextEditingToolbarHandle>(
  function StudioTextEditingToolbar(_, ref) {
    const elementRef = useRef<HTMLDivElement>(null)
    const studioMode = useStudioUiStore((state) => state.studioMode)
    const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
    const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === selectedNodeIds[0]))
    const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
    const selectNodes = useStudioUiStore((state) => state.selectNodes)

    const reposition = useCallback((selectionRect: StudioMediaToolbarRect, viewportRect: StudioMediaToolbarRect) => {
      const element = elementRef.current
      if (!element || !node) return
      // 未锚定时工具条是 visibility:hidden。用 offset 量尺寸，避免离屏 getBoundingClientRect 把宽度量成 0。
      const size = {
        width: Math.max(element.offsetWidth, element.scrollWidth, 120),
        height: Math.max(element.offsetHeight, 36)
      }
      const position = placeStudioNodeMediaToolbar(selectionRect, viewportRect, size)
      element.style.left = `${position.left}px`
      element.style.top = `${position.top}px`
      element.dataset.anchorNodeId = node.id
      element.dataset.placement = position.placement
      element.style.visibility = 'visible'
      element.style.pointerEvents = 'auto'
    }, [node])

    useImperativeHandle(ref, () => ({ reposition }), [reposition])

    useLayoutEffect(() => {
      const element = elementRef.current
      if (!element || element.dataset.anchorNodeId === node?.id) return
      element.style.visibility = 'hidden'
      element.style.pointerEvents = 'none'
      delete element.dataset.anchorNodeId
    }, [node?.id])

    if (studioMode !== 'free' || selectedNodeIds.length !== 1 || node?.data.kind !== 'text' || !node.data.textEditingEnabled) {
      return null
    }

    const command = (value: Parameters<typeof dispatchStudioTextEditorCommand>[1]) => {
      dispatchStudioTextEditorCommand(node.id, value)
    }
    const background = node.data.textBackground ?? 'neutral'

    return (
      <div
        ref={elementRef}
        className="studio-node-media-toolbar studio-canvas-glass-toolbar nodrag nowheel"
        role="toolbar"
        aria-label={`${node.data.title}文本编辑工具栏`}
        style={{ left: -9999, top: 0 }}
        onPointerDown={(event) => event.preventDefault()}
      >
        <StudioCanvasDropdownPopover
          ariaLabel="段落格式"
          activeKey={undefined}
          items={[
            { key: 'p', label: '段落' },
            { key: 'h1', label: '标题 1' },
            { key: 'h2', label: '标题 2' },
            { key: 'h3', label: '标题 3' }
          ]}
          onSelect={(key) => command({ type: 'format-block', value: key as 'p' | 'h1' | 'h2' | 'h3' })}
          trigger={<StudioCanvasToolbarButton label="段落格式" showLabel caret icon={<MenuOutlined />} />}
        />
        <StudioCanvasDropdownPopover
          ariaLabel="文本背景色"
          activeKey={background}
          items={BACKGROUNDS.map((item) => ({
            key: item.key,
            label: item.label,
            icon: <span className="h-3 w-3 rounded-sm border border-[var(--studio-ui-border)]" style={{ background: item.color }} />
          }))}
          onSelect={(key) => {
            updateNodeData(node.id, { textBackground: key as StudioTextBackgroundId }, true)
            selectNodes([node.id])
          }}
          trigger={<StudioCanvasToolbarButton label="背景色" showLabel caret icon={<BgColorsOutlined />} />}
        />
        <span className="studio-canvas-glass-toolbar__divider" aria-hidden />
        <StudioCanvasToolbarButton label="粗体" icon={<BoldOutlined />} onClick={() => command({ type: 'bold' })} />
        <StudioCanvasToolbarButton label="斜体" icon={<ItalicOutlined />} onClick={() => command({ type: 'italic' })} />
        <StudioCanvasToolbarButton label="无序列表" icon={<UnorderedListOutlined />} onClick={() => command({ type: 'unordered-list' })} />
        <StudioCanvasToolbarButton label="有序列表" icon={<OrderedListOutlined />} onClick={() => command({ type: 'ordered-list' })} />
        <StudioCanvasToolbarButton label="分割线" icon={<span aria-hidden>—</span>} onClick={() => command({ type: 'divider' })} />
        <StudioCanvasToolbarButton label="复制" icon={<CopyOutlined />} onClick={() => command({ type: 'copy' })} />
        <StudioCanvasToolbarButton label="展开" icon={<ExpandAltOutlined />} onClick={() => command({ type: 'expand' })} />
      </div>
    )
  }
))
