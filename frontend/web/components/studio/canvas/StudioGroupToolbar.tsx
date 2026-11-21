'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { forwardRef, memo, useCallback, useImperativeHandle, useMemo, useRef, useState } from 'react'
import {
  AppstoreOutlined,
  BorderOutlined,
  DownloadOutlined,
  FolderOutlined,
  PlayCircleOutlined,
  UngroupOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { executeStudioGroupImages } from '@/hooks/studio/executeStudioGroupImages'
import { useStudioUiStore } from '@/stores/studioUi'
import { downloadMediaFileWithToast } from '@/utils/mediaFileDownload'
import {
  STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP,
  canMergeNodesToGridStoryboard
} from '@/utils/studio/studioGroupStoryboardBridge'
import { listGroupDownloadableMedia } from '@/utils/studio/studioGroupActions'
import type { SelectionScreenRect, StudioArrangement } from '@/utils/studio/studioSelectionLayout'
import { placeStudioSelectionToolbar } from '@/utils/studio/studioSelectionLayout'
import { studioNodeMediaDownloadType } from '@/utils/studio/studioNodeMedia'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { StudioGroupColorPicker } from './StudioGroupColorPicker'

export interface StudioGroupToolbarHandle {
  reposition: (selectionRect: SelectionScreenRect, viewportRect: SelectionScreenRect) => void
}

function sameIdSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const set = new Set(a)
  return b.every((id) => set.has(id))
}

export const StudioGroupToolbar = memo(forwardRef<StudioGroupToolbarHandle>(
  function StudioGroupToolbar(_, ref) {
    const elementRef = useRef<HTMLDivElement>(null)
    const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
    const nodes = useStudioUiStore((state) => state.nodes)
    const groups = useStudioUiStore((state) => state.groups)
    const arrangeNodes = useStudioUiStore((state) => state.arrangeNodes)
    const ungroupNodes = useStudioUiStore((state) => state.ungroupNodes)
    const convertGroupToGridStoryboard = useStudioUiStore((state) => state.convertGroupToGridStoryboard)
    const setGroupColor = useStudioUiStore((state) => state.setGroupColor)
    const saveNodesToAssets = useStudioUiStore((state) => state.saveNodesToAssets)
    const [colorOpen, setColorOpen] = useState(false)
    const [arrangeOpen, setArrangeOpen] = useState(false)
    const [busy, setBusy] = useState(false)

    const group = useMemo(
      () => groups.find((item) => sameIdSet(item.childIds, selectedNodeIds)),
      [groups, selectedNodeIds]
    )
    const childNodes = useMemo(() => {
      if (!group) return []
      const idSet = new Set(group.childIds)
      return nodes.filter((node) => idSet.has(node.id))
    }, [group, nodes])
    const mergeCheck = useMemo(() => canMergeNodesToGridStoryboard(childNodes), [childNodes])

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect) {
        const element = elementRef.current
        if (!element) return
        const measured = element.getBoundingClientRect()
        const position = placeStudioSelectionToolbar(
          selectionRect,
          viewportRect,
          { width: Math.max(measured.width, 280), height: Math.max(measured.height, 40) }
        )
        element.style.left = `${position.left}px`
        element.style.top = `${position.top}px`
        element.dataset.placement = position.placement
      }
    }), [])

    const arrangementItems = useMemo(
      () => [
        { key: 'grid', label: '宫格排列' },
        { key: 'horizontal', label: '水平排列' },
        { key: 'vertical', label: '垂直排列' }
      ],
      []
    )

    const executeGroup = useCallback(async () => {
      if (!group || busy) return
      setBusy(true)
      try {
        await executeStudioGroupImages(group.childIds)
      } finally {
        setBusy(false)
      }
    }, [busy, group])

    const downloadGroup = useCallback(async () => {
      if (!group) return
      const items = listGroupDownloadableMedia(nodes, group.childIds)
      if (!items.length) {
        message.info('没有可下载的媒体')
        return
      }
      for (const item of items) {
        await downloadMediaFileWithToast(
          item.url,
          item.title,
          studioNodeMediaDownloadType(item.kind, undefined)
        )
      }
    }, [group, nodes])

    const saveToToolbox = useCallback(() => {
      if (!group) return
      const count = saveNodesToAssets(group.childIds)
      if (count) message.success(`已添加 ${count} 项到工具箱`)
      else message.info('没有可保存的媒体')
    }, [group, saveNodesToAssets])

    if (!group) return null

    const color = group.colorTag ?? null

    return (
      <div
        ref={elementRef}
        className="studio-group-toolbar studio-canvas-glass-toolbar nodrag nowheel"
        role="toolbar"
        aria-label={group.title || `分组 ${group.childIds.length} 个节点`}
        style={{ left: -9999, top: 0 }}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <StudioCanvasPopover
          open={colorOpen}
          onOpenChange={setColorOpen}
          trigger="click"
          placement="bottom"
          arrow={false}
          classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-canvas-dropdown-popover' }}
          getPopupContainer={(trigger) => trigger.closest('.studio-canvas') ?? document.body}
          content={(
            <StudioGroupColorPicker
              value={color}
              onChange={(next) => {
                setGroupColor(group.id, next)
                setColorOpen(false)
              }}
            />
          )}
        >
          <span className="studio-node-media-toolbar__popover-anchor">
            <StudioCanvasTooltip title="标签">
              <button
                type="button"
                className="studio-canvas-glass-toolbar__btn studio-group-toolbar__color-btn"
                aria-label="标签"
                onClick={() => setColorOpen(true)}
              >
                <i
                  className={`studio-group-toolbar__color-dot${color ? '' : ' is-none'}`}
                  style={color ? { background: color } : undefined}
                />
              </button>
            </StudioCanvasTooltip>
          </span>
        </StudioCanvasPopover>

        <StudioCanvasDropdownPopover
          placement="bottom"
          open={arrangeOpen}
          onOpenChange={setArrangeOpen}
          ariaLabel="组内排列"
          items={arrangementItems}
          onSelect={(key) => arrangeNodes(group.childIds, key as StudioArrangement)}
          trigger={
            <StudioCanvasToolbarButton
              caret
              expanded={arrangeOpen}
              label="排列"
              icon={<AppstoreOutlined aria-hidden />}
            />
          }
        />

        <span className="studio-canvas-glass-toolbar__divider" aria-hidden />

        <StudioCanvasToolbarButton
          showLabel
          label="整组执行"
          icon={<PlayCircleOutlined aria-hidden />}
          disabled={busy}
          onClick={() => { void executeGroup() }}
        />

        <StudioCanvasToolbarButton
          showLabel
          label="添加到工具箱"
          icon={<FolderOutlined aria-hidden />}
          onClick={saveToToolbox}
        />

        {mergeCheck.ok ? (
          <StudioCanvasToolbarButton
            showLabel
            label="转分镜组"
            icon={<BorderOutlined aria-hidden />}
            onClick={() => {
              const id = convertGroupToGridStoryboard(group.id)
              if (!id) message.warning(STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP)
            }}
          />
        ) : (
          <StudioCanvasTooltip title={STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP}>
            <span>
              <StudioCanvasToolbarButton
                showLabel
                label="转分镜组"
                icon={<BorderOutlined aria-hidden />}
                disabled
              />
            </span>
          </StudioCanvasTooltip>
        )}

        <StudioCanvasToolbarButton
          showLabel
          label="解组"
          icon={<UngroupOutlined aria-hidden />}
          onClick={() => ungroupNodes(group.childIds)}
        />

        <StudioCanvasToolbarButton
          showLabel
          label="批量下载"
          icon={<DownloadOutlined aria-hidden />}
          onClick={() => { void downloadGroup() }}
        />
      </div>
    )
  }
))
