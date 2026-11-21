'use client'

import { forwardRef, memo, useImperativeHandle, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import {
  AppstoreOutlined,
  BorderOuterOutlined,
  CopyOutlined,
  GroupOutlined,
  RobotOutlined,
  SaveOutlined
} from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { SelectionScreenRect, StudioArrangement } from '@/utils/studio/studioSelectionLayout'
import { placeStudioSelectionToolbar } from '@/utils/studio/studioSelectionLayout'
import {
  STUDIO_FLOW_BATCH_INTENT_LABELS,
  inferStudioFlowBatchIntent
} from '@/utils/studio/studioFlowBatchSelection'
import {
  STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP,
  canMergeNodesToGridStoryboard
} from '@/utils/studio/studioGroupStoryboardBridge'
import { StudioCanvasDropdownPopover } from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'

export interface StudioSelectionToolbarHandle {
  reposition: (selectionRect: SelectionScreenRect, viewportRect: SelectionScreenRect) => void
}

export const StudioSelectionToolbar = memo(forwardRef<StudioSelectionToolbarHandle>(
  function StudioSelectionToolbar(_, ref) {
    const elementRef = useRef<HTMLDivElement>(null)
    const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
    const studioMode = useStudioUiStore((state) => state.studioMode)
    const setRightCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
    const nodes = useStudioUiStore((state) => state.nodes)
    const selectedNodes = useMemo(() => {
      const selectedIdSet = new Set(selectedNodeIds)
      return nodes.filter((node) => selectedIdSet.has(node.id))
    }, [nodes, selectedNodeIds])
    const arrangeNodes = useStudioUiStore((state) => state.arrangeNodes)
    const duplicateNodes = useStudioUiStore((state) => state.duplicateNodes)
    const groupNodes = useStudioUiStore((state) => state.groupNodes)
    const mergeNodesToGridStoryboard = useStudioUiStore((state) => state.mergeNodesToGridStoryboard)
    const saveNodesToAssets = useStudioUiStore((state) => state.saveNodesToAssets)
    const hasFlowBinding = selectedNodes.some((node) => Boolean(node.data.flowBinding))
    const mergeCheck = useMemo(() => canMergeNodesToGridStoryboard(selectedNodes), [selectedNodes])
    const batchIntent = useMemo(
      () => (studioMode === 'flow' ? inferStudioFlowBatchIntent(selectedNodes) : null),
      [selectedNodes, studioMode]
    )
    const [arrangeOpen, setArrangeOpen] = useState(false)
    const [groupOpen, setGroupOpen] = useState(false)

    const openAgentBatchPanel = () => {
      if (!batchIntent) {
        message.warning('当前选区不符合批量任务规则，请只选择同类型的流程实体节点')
        return
      }
      setRightCollapsed(false)
      message.info(`已在右侧 Agent 面板就绪：${STUDIO_FLOW_BATCH_INTENT_LABELS[batchIntent]}`)
    }

    useImperativeHandle(ref, () => ({
      reposition(selectionRect, viewportRect) {
        const element = elementRef.current
        if (!element) return
        const measured = element.getBoundingClientRect()
        const size = { width: Math.max(measured.width, 220), height: Math.max(measured.height, 40) }
        const position = placeStudioSelectionToolbar(selectionRect, viewportRect, size)
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

    const groupItems = useMemo(
      () => [
        {
          key: 'group',
          label: '打组',
          icon: <GroupOutlined aria-hidden />,
          disabled: selectedNodeIds.length < 2
        },
        {
          key: 'merge',
          label: '合并分镜组',
          icon: <BorderOuterOutlined aria-hidden />,
          disabled: !mergeCheck.ok,
          disabledHint: mergeCheck.ok ? undefined : STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP
        }
      ],
      [mergeCheck.ok, selectedNodeIds.length]
    )

    const saveAssets = () => {
      const count = saveNodesToAssets(selectedNodeIds)
      if (count) message.success(`已保存 ${count} 项到资产`)
      else message.info('没有可保存的媒体')
    }

    return (
      <div
        ref={elementRef}
        className="studio-selection-toolbar studio-canvas-glass-toolbar nodrag nowheel"
        role="toolbar"
        aria-label={`已选择 ${selectedNodeIds.length} 个节点`}
        onPointerDown={(event) => event.stopPropagation()}
      >
        <StudioCanvasDropdownPopover
          placement="bottom"
          open={arrangeOpen}
          onOpenChange={setArrangeOpen}
          ariaLabel="排列方式"
          items={arrangementItems}
          onSelect={(key) => arrangeNodes(selectedNodeIds, key as StudioArrangement)}
          trigger={
            <StudioCanvasToolbarButton
              showLabel
              caret
              expanded={arrangeOpen}
              label="排列"
              icon={<AppstoreOutlined aria-hidden />}
            />
          }
        />
        <StudioCanvasToolbarButton
          label={hasFlowBinding ? '流程实体请在原流程编辑器中操作' : '保存到资产'}
          icon={<SaveOutlined aria-hidden />}
          disabled={hasFlowBinding}
          onClick={saveAssets}
        />
        <StudioCanvasToolbarButton
          label={hasFlowBinding ? '流程实体不支持画布伪复制' : '创建副本'}
          icon={<CopyOutlined aria-hidden />}
          disabled={hasFlowBinding}
          onClick={() => duplicateNodes(selectedNodeIds)}
        />
        <StudioCanvasDropdownPopover
          placement="bottom"
          open={groupOpen}
          onOpenChange={setGroupOpen}
          ariaLabel="打组选项"
          items={groupItems}
          onSelect={(key) => {
            if (key === 'group') {
              groupNodes(selectedNodeIds)
              return
            }
            if (key === 'merge') {
              const id = mergeNodesToGridStoryboard(selectedNodeIds)
              if (!id) message.warning(STUDIO_GRID_STORYBOARD_MERGE_TOOLTIP)
            }
          }}
          trigger={
            <StudioCanvasToolbarButton
              showLabel
              caret
              expanded={groupOpen}
              label="打组"
              icon={<GroupOutlined aria-hidden />}
            />
          }
        />
        {studioMode === 'flow' && selectedNodeIds.length >= 2 ? (
          <StudioCanvasToolbarButton
            label={batchIntent ? `发送到 Agent：${STUDIO_FLOW_BATCH_INTENT_LABELS[batchIntent]}` : '选区类型不一致'}
            icon={<RobotOutlined aria-hidden />}
            disabled={!batchIntent}
            onClick={openAgentBatchPanel}
          />
        ) : null}
      </div>
    )
  }
))
