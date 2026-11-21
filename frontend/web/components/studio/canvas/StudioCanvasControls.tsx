'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { memo, useCallback, useState } from 'react'
import { Button } from 'antd'
import {
  ApartmentOutlined,
  AimOutlined,
  AppstoreOutlined,
  CompressOutlined,
  LayoutOutlined,
  MinusOutlined,
  NodeIndexOutlined,
  PlusOutlined,
  MoonOutlined,
  SunOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons'
import { useReactFlow, useStore } from '@xyflow/react'
import { useStudioUiStore } from '@/stores/studioUi'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { useStudioFreeTheme } from '../theme/StudioFreeThemeProvider'

const ZOOM_PRESETS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2] as const

function StudioCanvasControlsComponent({ readOnly = false }: { readOnly?: boolean }) {
  const { theme, isAutomatic, siteSynced, toggleTheme } = useStudioFreeTheme()
  const themeModeLabel = isAutomatic ? '自动' : theme === 'opal' ? '月白' : '柔雾'
  const nextThemeModeLabel = siteSynced ? theme === 'opal' ? '柔雾' : '月白'
    : isAutomatic ? '月白' : theme === 'opal' ? '柔雾' : '自动'
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const leftCollapsed = useStudioUiStore((state) => state.leftCollapsed)
  const setLeftCollapsed = useStudioUiStore((state) => state.setLeftCollapsed)
  const hideEdges = useStudioUiStore((state) => state.hideEdges)
  const setHideEdges = useStudioUiStore((state) => state.setHideEdges)
  const gridSnapEnabled = useStudioUiStore((state) => state.gridSnapEnabled)
  const setGridSnapEnabled = useStudioUiStore((state) => state.setGridSnapEnabled)
  const miniMapOpen = useStudioUiStore((state) => state.miniMapOpen)
  const setMiniMapOpen = useStudioUiStore((state) => state.setMiniMapOpen)
  const tidyCanvas = useStudioUiStore((state) => state.tidyCanvas)
  const nodes = useStudioUiStore((state) => state.nodes)
  const expandZoomLocked = useStudioUiStore((state) => (
    state.imageEditSession?.mode === 'expand' || Boolean(state.touchEditSession)
  ))
  const { zoomIn, zoomOut, zoomTo, fitView, fitBounds } = useReactFlow()
  const zoom = useStore(state => state.transform[2])
  const [zoomOpen, setZoomOpen] = useState(false)
  const miniMapDisabled = nodes.length > 500
  const tidyTargetCount = studioMode === 'flow'
    ? filterStudioFlowCanvasVisibleNodes(nodes).length
    : nodes.length

  const fitNodes = useCallback((targetNodes: typeof nodes) => {
    if (expandZoomLocked) return
    if (!targetNodes.length) {
      void fitView({ padding: 0.14, duration: 220 })
      return
    }
    const left = Math.min(...targetNodes.map((node) => node.position.x))
    const top = Math.min(...targetNodes.map((node) => node.position.y))
    const right = Math.max(...targetNodes.map((node) => node.position.x + studioNodeDimensions(node).width))
    const bottom = Math.max(...targetNodes.map((node) => node.position.y + studioNodeDimensions(node).height))
    void fitBounds(
      { x: left, y: top, width: right - left, height: bottom - top },
      { padding: 0.14, duration: 220 }
    )
  }, [expandZoomLocked, fitBounds, fitView])

  const fitAll = useCallback(() => {
    const state = useStudioUiStore.getState()
    const targetNodes = state.studioMode === 'flow'
      ? filterStudioFlowCanvasVisibleNodes(state.nodes)
      : state.nodes
    fitNodes(targetNodes)
  }, [fitNodes])

  const tidyCanvasAndFit = useCallback(() => {
    tidyCanvas()
    const state = useStudioUiStore.getState()
    const targetNodes = state.studioMode === 'flow'
      ? filterStudioFlowCanvasVisibleNodes(state.nodes)
      : state.nodes.filter((node) => !node.data.flowBinding)
    fitNodes(targetNodes.length ? targetNodes : state.nodes)
  }, [fitNodes, tidyCanvas])

  const zoomPanel = (
    <div className="studio-canvas-controls__zoom-panel" role="menu" aria-label="缩放选项">
      <button
        type="button"
        className="studio-canvas-controls__zoom-action"
        disabled={expandZoomLocked}
        onClick={() => { void zoomIn({ duration: 160 }); if (studioMode !== 'free') setZoomOpen(false) }}
      >
        <ZoomInOutlined aria-hidden />
        <span>放大</span>
      </button>
      <button
        type="button"
        className="studio-canvas-controls__zoom-action"
        disabled={expandZoomLocked}
        onClick={() => { void zoomOut({ duration: 160 }); if (studioMode !== 'free') setZoomOpen(false) }}
      >
        <ZoomOutOutlined aria-hidden />
        <span>缩小</span>
      </button>
      <button
        type="button"
        className="studio-canvas-controls__zoom-action"
        disabled={expandZoomLocked}
        onClick={() => { fitAll(); setZoomOpen(false) }}
      >
        <CompressOutlined aria-hidden />
        <span>适应画布</span>
      </button>
      <span className="studio-canvas-controls__zoom-divider" />
      {ZOOM_PRESETS.map((preset) => {
        const active = Math.abs(zoom - preset) < 0.02
        return (
          <button
            key={preset}
            type="button"
            role="menuitemradio"
            aria-checked={active}
            disabled={expandZoomLocked}
            className={`studio-canvas-controls__zoom-preset${active ? ' is-active' : ''}`}
            onClick={() => {
              void zoomTo(preset, { duration: 160 })
              setZoomOpen(false)
            }}
          >
            {Math.round(preset * 100)}%
          </button>
        )
      })}
    </div>
  )

  return (
    <div className="studio-canvas-controls" aria-label="画布视图控件">
      <div className="studio-canvas-controls__bar">
        {studioMode === 'free' && !readOnly ? (
          <>
            <StudioCanvasTooltip title="画布面板">
              <Button
                className={`studio-canvas-tool${!leftCollapsed ? ' is-active' : ''}`}
                type="text"
                icon={<ApartmentOutlined />}
                aria-label="打开画布面板"
                aria-pressed={!leftCollapsed}
                data-studio-guide="planning"
                onClick={() => setLeftCollapsed(!leftCollapsed)}
              />
            </StudioCanvasTooltip>
            <StudioCanvasTooltip title={hideEdges ? '显示节点连线' : '隐藏节点连线'}>
              <Button
                className={`studio-canvas-tool${hideEdges ? ' is-active' : ''}`}
                type="text"
                icon={<NodeIndexOutlined />}
                aria-label={hideEdges ? '显示节点连线' : '隐藏节点连线'}
                aria-pressed={hideEdges}
                onClick={() => setHideEdges(!hideEdges)}
              />
            </StudioCanvasTooltip>
            <StudioCanvasTooltip title={gridSnapEnabled ? '关闭网格吸附' : '开启网格吸附'}>
              <Button
                className={`studio-canvas-tool${gridSnapEnabled ? ' is-active' : ''}`}
                type="text"
                icon={<AppstoreOutlined />}
                aria-label={gridSnapEnabled ? '关闭网格吸附' : '开启网格吸附'}
                aria-pressed={gridSnapEnabled}
                onClick={() => setGridSnapEnabled(!gridSnapEnabled)}
              />
            </StudioCanvasTooltip>
            <StudioCanvasTooltip title="按连线方向整理画布">
              <Button
                className="studio-canvas-tool"
                type="text"
                icon={<LayoutOutlined />}
                aria-label="整理画布"
                disabled={!tidyTargetCount}
                onClick={tidyCanvasAndFit}
              />
            </StudioCanvasTooltip>
            <StudioCanvasTooltip title={miniMapDisabled ? '节点过多，已停用小地图' : miniMapOpen ? '关闭小地图' : '打开小地图'}>
              <Button
                className={`studio-canvas-tool${miniMapOpen ? ' is-active' : ''}`}
                type="text"
                icon={<AimOutlined />}
                aria-label={miniMapDisabled ? '小地图已停用' : miniMapOpen ? '关闭小地图' : '打开小地图'}
                aria-pressed={miniMapOpen}
                disabled={miniMapDisabled}
                onClick={() => setMiniMapOpen(!miniMapOpen)}
              />
            </StudioCanvasTooltip>
            <span className="studio-canvas-controls__divider" />
            <StudioCanvasTooltip
              title={`当前${themeModeLabel}${isAutomatic ? `（${theme === 'opal' ? '月白' : '柔雾'}，按时间）` : ''}，点击切换${nextThemeModeLabel}`}
            >
              <Button
                className="studio-canvas-tool studio-canvas-theme-toggle"
                type="text"
                icon={(
                  <span className="relative inline-flex items-center justify-center" aria-hidden>
                    {theme === 'opal' ? <SunOutlined /> : <MoonOutlined />}
                    {isAutomatic ? (
                      <span className="absolute -right-1.5 -top-1 rounded-full bg-[var(--studio-accent)] px-0.5 text-[7px] font-bold leading-3 text-[var(--studio-theme-inverse-text)]">
                        A
                      </span>
                    ) : null}
                  </span>
                )}
                aria-label={`主题模式：${themeModeLabel}${isAutomatic ? `，当前显示${theme === 'opal' ? '月白' : '柔雾'}` : ''}；切换到${nextThemeModeLabel}`}
                onClick={toggleTheme}
              />
            </StudioCanvasTooltip>
            <span className="studio-canvas-controls__divider" />
          </>
        ) : null}
        <StudioCanvasPopover
          trigger="click"
          placement="top"
          open={zoomOpen}
          onOpenChange={setZoomOpen}
          arrow={false}
          classNames={{ root: 'studio-canvas-controls__zoom-popover' }}
          content={zoomPanel}
        >
          <StudioCanvasTooltip title="缩放选项" open={zoomOpen ? false : undefined}>
            <button
              type="button"
              className={`studio-canvas-controls__zoom${zoomOpen ? ' is-active' : ''}`}
              aria-label={`当前缩放 ${Math.round(zoom * 100)}%，打开缩放选项`}
              aria-expanded={zoomOpen}
            >
              <MinusOutlined className="studio-canvas-controls__zoom-glyph" aria-hidden />
              <span>{Math.round(zoom * 100)}%</span>
              <PlusOutlined className="studio-canvas-controls__zoom-glyph" aria-hidden />
            </button>
          </StudioCanvasTooltip>
        </StudioCanvasPopover>
      </div>
    </div>
  )
}

export const StudioCanvasControls = memo(StudioCanvasControlsComponent)
