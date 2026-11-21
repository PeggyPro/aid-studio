'use client'

import { memo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useStudioChromeTarget } from '../shell/StudioChromeSlot'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { Button, ConfigProvider, Dropdown, message, type MenuProps } from 'antd'
import {
  AppstoreAddOutlined,
  ApartmentOutlined,
  BorderOutlined,
  CheckOutlined,
  CompressOutlined,
  DeploymentUnitOutlined,
  DragOutlined,
  DatabaseOutlined,
  FilterOutlined,
  MoreOutlined,
  RedoOutlined,
  SafetyCertificateOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { useReactFlow } from '@xyflow/react'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioEdgeKind, StudioNodeData, StudioNodeKind, StudioSavedAsset } from '@/types/studio'
import { useUserStore } from '@/stores/user'
import { hasSignedStudioDisclaimer, signStudioDisclaimer } from '@/utils/studio/studioDisclaimer'
import { StudioMaterialLibraryModal, type StudioMaterialPick } from './StudioMaterialLibraryModal'
import { StudioDisclaimerModal } from '../composer/StudioDisclaimerModal'
import { StudioAddNodeMenuLabel } from './StudioAddNodeMenuLabel'
import { STUDIO_NODE_DEFAULT_DIMENSIONS, studioNodeDimensions } from '@/utils/studio/studioNodeLod'
import { defaultZoneByKind } from '@/utils/studio/studioNodeKind'
import { STUDIO_PANE_ADD_MENU } from '@/utils/studio/studioPaneMenu'
import { requestStudioFlowEditor } from '@/utils/studio/studioFlowEvents'
import { StudioCanvasPopover } from './StudioCanvasPopover'
import { StudioCanvasShortcutSheet } from './StudioCanvasShortcutSheet'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { useCanvasProjectRuntime } from '../free/CanvasProjectRuntimeContext'
import { apiErrorMessage } from '@/utils/apiError'
import { resolveStudioCatalogMaterial } from '@/utils/studio/studioMaterialLibrary'

const EMPTY_LIBRARY_ASSETS: StudioSavedAsset[] = []

const STUDIO_CANVAS_SUBMENU_CLASS =
  'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas studio-canvas-menu-overlay--submenu'

/** 与右键菜单一致的灰态，避免 antd Menu 主题青/蓝覆盖 */
const STUDIO_TOOLBAR_MENU_THEME = {
  components: {
    Menu: {
      itemHoverBg: 'rgba(255,255,255,0.05)',
      itemActiveBg: 'rgba(255,255,255,0.05)',
      itemSelectedBg: 'transparent',
      itemSelectedColor: '#d7dde6',
      itemHoverColor: '#edf1f6',
      darkItemHoverBg: 'rgba(255,255,255,0.05)',
      darkItemSelectedBg: 'transparent',
      darkItemSelectedColor: '#d7dde6'
    }
  }
} as const

function StudioCanvasToolbarComponent() {
  const canvasRuntime = useCanvasProjectRuntime()
  const chromeTarget = useStudioChromeTarget()
  const addNode = useStudioUiStore((state) => state.addNode)
  const addUserNode = useStudioUiStore((state) => state.addUserNode)
  const nodes = useStudioUiStore((state) => state.nodes)
  const zones = useStudioUiStore((state) => state.zones)
  const historyPast = useStudioUiStore((state) => state.historyPast)
  const historyFuture = useStudioUiStore((state) => state.historyFuture)
  const undo = useStudioUiStore((state) => state.undo)
  const redo = useStudioUiStore((state) => state.redo)
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const [historyBusy, setHistoryBusy] = useState(false)
  const historyInFlight = useRef(false)
  const edgeKind = useStudioUiStore((state) => state.edgeKind)
  const setEdgeKind = useStudioUiStore((state) => state.setEdgeKind)
  const nodeFilter = useStudioUiStore((state) => state.nodeFilter)
  const setNodeFilter = useStudioUiStore((state) => state.setNodeFilter)
  const runDiagnostics = useStudioUiStore((state) => state.runDiagnostics)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const setRightCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
  const addMessage = useStudioUiStore((state) => state.addMessage)
  const canvasTool = useStudioUiStore((state) => state.canvasTool)
  const setCanvasTool = useStudioUiStore((state) => state.setCanvasTool)
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const { fitView, fitBounds, screenToFlowPosition, setCenter, getViewport } = useReactFlow()
  const [shortcutsOpen, setShortcutsOpen] = useState(false)
  const [libraryOpen, setLibraryOpen] = useState(false)
  const [disclaimerOpen, setDisclaimerOpen] = useState(false)
  const assets = useStudioUiStore((state) => libraryOpen ? state.savedAssets : EMPTY_LIBRARY_ASSETS)
  const userId = useUserStore((state) => state.user?.id)

  // Project operations use server-owned undo receipts and leases; local graph history cannot undo shared writes.
  const undoReason = canvasRuntime
    ? !canvasRuntime.canMutate ? '当前画布不可编辑'
      : historyBusy ? '正在撤销'
      : dirtyRevision > 0 ? '请等待当前修改保存后撤销'
      : !canvasRuntime.undoAvailable ? '本页暂无可撤销的已保存操作'
      : ''
    : !historyPast.length ? '暂无可撤销的操作' : ''
  const redoReason = canvasRuntime ? '共享画布暂不支持重做已提交操作'
    : !historyFuture.length ? '暂无可重做的操作' : ''
  const undoFromToolbar = async () => {
    if (undoReason || historyInFlight.current) return
    if (!canvasRuntime) { undo(); return }
    historyInFlight.current = true
    setHistoryBusy(true)
    try { await canvasRuntime.undoLast() }
    catch (error) { message.error(apiErrorMessage(error, '撤销失败')) }
    finally { historyInFlight.current = false; setHistoryBusy(false) }
  }

  const fitAll = () => {
    if (!nodes.length) {
      void fitView({ padding: 0.14, duration: 220 })
      return
    }
    const left = Math.min(...nodes.map((node) => node.position.x))
    const top = Math.min(...nodes.map((node) => node.position.y))
    const right = Math.max(...nodes.map((node) => node.position.x + studioNodeDimensions(node).width))
    const bottom = Math.max(...nodes.map((node) => node.position.y + studioNodeDimensions(node).height))
    void fitBounds({ x: left, y: top, width: right - left, height: bottom - top }, { padding: 0.14, duration: 220 })
  }

  const addAtZone = (kind: StudioNodeKind, data?: Partial<StudioNodeData>) => {
    if (studioMode === 'free') {
      const pane = document.querySelector<HTMLElement>('.studio-canvas .react-flow__pane')
      const paneRect = pane?.getBoundingClientRect()
      const center = paneRect
        ? { x: paneRect.left + paneRect.width / 2, y: paneRect.top + paneRect.height / 2 }
        : { x: window.innerWidth / 2, y: window.innerHeight / 2 }
      const flowCenter = screenToFlowPosition(center)
      const size = STUDIO_NODE_DEFAULT_DIMENSIONS[kind]
      const current = useStudioUiStore.getState()
      const position = current.nodes.length > 0 ? undefined
        : { x: flowCenter.x - size.width / 2, y: flowCenter.y - size.height / 2 }
      const nodeId = addUserNode(kind, position, {
        title: STUDIO_NODE_META[kind].label,
        zoneId: defaultZoneByKind(kind),
        ...data
      })
      // addNode resolves overlap against the full workspace, including virtualized nodes.
      const added = useStudioUiStore.getState().nodes.find((node) => node.id === nodeId)
      if (added) {
        const actualSize = studioNodeDimensions(added)
        void setCenter(added.position.x + actualSize.width / 2,
          added.position.y + actualSize.height / 2,
          { zoom: getViewport().zoom, duration: 220 })
      }
      return nodeId
    }
    const zoneId = studioMode === 'flow' ? defaultZoneByKind(kind) : undefined
    const zone = zones.find((item) => item.id === zoneId)
    const peers = zoneId
      ? nodes.filter((node) => node.data.zoneId === zoneId).length
      : nodes.length
    return addNode(kind, {
      x: (zone?.position.x ?? 80) + 32 + (peers % 2) * 250,
      y: (zone?.position.y ?? 80) + 72 + Math.floor(peers / 2) * 190
    }, {
      title: `${STUDIO_NODE_META[kind].label} ${peers + 1}`,
      zoneId,
      ...data
    })
  }

  const openLibrary = () => {
    if (hasSignedStudioDisclaimer(window.localStorage, userId)) setLibraryOpen(true)
    else setDisclaimerOpen(true)
  }

  const placeLibraryPick = (pick: StudioMaterialPick) => {
    if (pick.kind === 'node') {
      if (!canvasRuntime) { addAtZone(pick.nodeKind, pick.data); return }
      if (!canvasRuntime.canMutate) { message.warning('当前画布不可编辑'); return }
      const { scopeKey } = useStudioUiStore.getState()
      const nodeId = addAtZone(pick.nodeKind, { ...pick.data, status: 'queued', canvasTransient: true })
      if (!nodeId) return
      const isCurrent = () => {
        const state = useStudioUiStore.getState()
        return state.scopeKey === scopeKey && state.nodes.some(({ id, data }) =>
          id === nodeId && data.canvasTransient && data.status === 'queued'
            && data.kind === pick.nodeKind && data.mediaUrl === pick.data.mediaUrl
        )
      }
      void resolveStudioCatalogMaterial(pick, canvasRuntime.resolveResourceReference, isCurrent)
        .then((data) => {
          if (isCurrent()) useStudioUiStore.getState().updateNodeData(nodeId, data)
        })
        .catch((error) => {
          if (!isCurrent()) return
          useStudioUiStore.getState().updateNodeData(nodeId, {
            status: 'failed',
            errorMessage: apiErrorMessage(error, '素材登记失败，请重新选择')
          })
        })
      return
    }
    const ref = pick.ref
    if (ref.nodeId && nodes.some((node) => node.id === ref.nodeId)) {
      selectNodes([ref.nodeId])
      window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: ref.nodeId } }))
      return
    }
    if (!ref.previewUrl) return
    const kind: StudioNodeKind = ref.mediaKind === 'video' ? 'video' : ref.mediaKind === 'audio' ? 'voice' : 'image'
    addAtZone(kind, {
      title: ref.title,
      status: 'success',
      mediaUrl: ref.previewUrl,
      mediaKind: ref.mediaKind,
      ...(ref.assetId && /^\d+$/.test(ref.assetId) ? { canvasResourceIds: [ref.assetId] } : {})
    })
  }

  const addMenu: MenuProps = {
    className: 'studio-add-menu studio-add-menu--canvas',
    items: STUDIO_PANE_ADD_MENU.map(({ kind }) => ({
      key: kind,
      label: <StudioAddNodeMenuLabel kind={kind} />,
      onClick: () => addAtZone(kind)
    }))
  }

  const flowAddMenu: MenuProps = {
    className: 'studio-add-menu studio-add-menu--canvas',
    items: [
      {
        key: 'project-config',
        icon: <DeploymentUnitOutlined />,
        label: '项目配置',
        onClick: () => requestStudioFlowEditor('global-setting')
      },
      ...([
        ['story-script', '新建 / 编辑剧本'],
        ['scene-character', '新增 / 编辑素材'],
        ['storyboard-script', '新增 / 编辑分镜'],
        ['storyboard-video', '生成 / 编辑视频'],
        ['dubbing', '生成 / 编辑配音'],
        ['preview', '预览 / 导出成片']
      ] as const).map(([step, label]) => ({
        key: step,
        icon: <DeploymentUnitOutlined />,
        label,
        onClick: () => requestStudioFlowEditor(step)
      }))
    ]
  }

  const moreMenu: MenuProps = {
    className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
    // 不使用 selectedKeys：打开菜单时不要出现「默认选中/假 hover」灰底
    selectable: false,
    items: [
      {
        key: 'edge',
        icon: <DeploymentUnitOutlined />,
        label: '连线类型',
        popupClassName: STUDIO_CANVAS_SUBMENU_CLASS,
        children: ([
          ['edge:flow', '流程连接（1）', edgeKind === 'flow'],
          ['edge:reference', '引用关系（2）', edgeKind === 'reference'],
          ['edge:sequence', '镜头顺序（3）', edgeKind === 'sequence']
        ] as const).map(([key, label, checked]) => ({
          key,
          label: (
            <span className="studio-toolbar-more-option">
              <span>{label}</span>
              {checked ? <CheckOutlined aria-hidden /> : null}
            </span>
          )
        }))
      },
      {
        key: 'filter',
        icon: <FilterOutlined />,
        label: '节点筛选',
        popupClassName: STUDIO_CANVAS_SUBMENU_CLASS,
        children: ([
          ['filter:all', '显示全部节点', nodeFilter === 'all'],
          ['filter:rerun', '只看待处理节点', nodeFilter === 'rerun']
        ] as const).map(([key, label, checked]) => ({
          key,
          label: (
            <span className="studio-toolbar-more-option">
              <span>{label}</span>
              {checked ? <CheckOutlined aria-hidden /> : null}
            </span>
          )
        }))
      },
      { type: 'divider' },
      ...(studioMode === 'flow' ? [{ key: 'zones', icon: <ApartmentOutlined />, label: '流程分区已开启', disabled: true } as const] : []),
      { key: 'diagnostics', icon: <SafetyCertificateOutlined />, label: '画布体检' }
    ],
    onClick: ({ key }) => {
      if (key.startsWith('edge:')) setEdgeKind(key.slice(5) as StudioEdgeKind)
      else if (key.startsWith('filter:')) setNodeFilter(key.slice(7) as 'all' | 'rerun')
      else if (key === 'diagnostics') {
        const result = runDiagnostics()
        const relatedNodeIds = [...new Set(result.map((item) => item.nodeId).filter(Boolean))] as string[]
        if (relatedNodeIds.length) selectNodes(relatedNodeIds)
        if (!result.length) {
          message.success('画布体检通过，暂无可改进项')
          return
        }
        message.warning(`画布体检：发现 ${result.length} 项可改进内容`)
        if (studioMode === 'flow') {
          setRightCollapsed(false)
          addMessage({
            role: 'assistant',
            content: [
              `画布体检完成，发现 ${result.length} 项：`,
              ...result.map((item, index) =>
                `${index + 1}. [${item.severity}] ${item.title} — ${item.reason}${item.action ? `（建议：${item.action}）` : ''}`
              )
            ].join('\n')
          })
        }
      }
    }
  }

  const dropdownClassNames = { root: 'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas' }

  const addControl = (
    <StudioCanvasTooltip title={studioMode === 'flow' ? '打开流程步骤编辑器' : '添加节点'} key="add">
      <span className="studio-canvas-tool-trigger"><Dropdown menu={studioMode === 'flow' ? flowAddMenu : addMenu} trigger={['click']} placement="bottomLeft" classNames={dropdownClassNames}><Button className="studio-canvas-tool studio-canvas-tool--primary" type="text" icon={<StudioChromeIcon name="addNode" fallback={<AppstoreAddOutlined />} />} aria-label={studioMode === 'flow' ? '流程步骤' : '添加节点'} /></Dropdown></span>
    </StudioCanvasTooltip>
  )
  const addFirst = studioToolbarControlOrder(studioMode)[0] === 'add'

  const toolbar = (
    <ConfigProvider theme={studioMode === 'free' ? undefined : STUDIO_TOOLBAR_MENU_THEME}>
      <div className="studio-canvas-toolbar" aria-label="画布工具栏">
        {addFirst ? addControl : null}
        {addFirst ? <span className="studio-canvas-toolbar__divider" /> : null}
        <StudioCanvasTooltip title="选择：空白拖拽框选">
          <Button className={`studio-canvas-tool${canvasTool === 'select' ? ' is-active' : ''}`} type="text" icon={<StudioChromeIcon name="select" fallback={<BorderOutlined />} />} aria-label="选择工具" onClick={() => setCanvasTool('select')} />
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title="抓手：拖动画布（Space 临时启用）">
          <Button className={`studio-canvas-tool${canvasTool === 'pan' ? ' is-active' : ''}`} type="text" icon={<StudioChromeIcon name="hand" fallback={<DragOutlined />} />} aria-label="抓手工具" onClick={() => setCanvasTool('pan')} />
        </StudioCanvasTooltip>
        <span className="studio-canvas-toolbar__divider" />
        <StudioCanvasTooltip title="素材库">
          <Button className={`studio-canvas-tool${libraryOpen ? ' is-active' : ''}`} type="text" icon={<StudioChromeIcon name="library" fallback={<DatabaseOutlined />} />} aria-label="素材库" aria-expanded={libraryOpen} onClick={openLibrary} />
        </StudioCanvasTooltip>
        <StudioCanvasTooltip title={undoReason || '撤销 (Ctrl/⌘ Z)'}><Button className="studio-canvas-tool" type="text" icon={<StudioChromeIcon name="undo" fallback={<UndoOutlined />} />} aria-label="撤销" disabled={Boolean(undoReason)} onClick={() => void undoFromToolbar()} /></StudioCanvasTooltip>
        <StudioCanvasTooltip title={redoReason || '重做 (Ctrl/⌘ Shift Z)'}><Button className="studio-canvas-tool" type="text" icon={<StudioChromeIcon name="redo" fallback={<RedoOutlined />} />} aria-label="重做" disabled={Boolean(redoReason)} onClick={redo} /></StudioCanvasTooltip>
        <StudioCanvasTooltip title="适应全部内容"><Button className="studio-canvas-tool" type="text" icon={<StudioChromeIcon name="expand" fallback={<CompressOutlined />} />} aria-label="适应全部内容" onClick={fitAll} /></StudioCanvasTooltip>
        <span className="studio-canvas-toolbar__divider" />
        {!addFirst ? addControl : null}
        <StudioCanvasPopover content={<StudioCanvasShortcutSheet onClose={() => setShortcutsOpen(false)} />} open={shortcutsOpen} onOpenChange={setShortcutsOpen} trigger="click" placement="bottom" classNames={{ root: 'studio-canvas-shortcuts-popover' }}>
          <span className="studio-canvas-tool-trigger">
            <StudioCanvasTooltip title="快捷键">
              <Button className={`studio-canvas-tool${shortcutsOpen ? ' is-active' : ''}`} type="text" icon={<StudioChromeIcon name="shortcuts" fallback={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true"><path d="M3 6h18a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Zm2 3h2m2 0h2m2 0h2m2 0h2M6 12h2m2 0h2m2 0h2m2 0h2M8 15h8" /></svg>} />} aria-label="快捷键" aria-expanded={shortcutsOpen} />
            </StudioCanvasTooltip>
          </span>
        </StudioCanvasPopover>
        <StudioCanvasTooltip title="更多"><span className="studio-canvas-tool-trigger"><Dropdown menu={moreMenu} trigger={['click']} placement="bottomLeft" classNames={dropdownClassNames}><Button className="studio-canvas-tool" type="text" icon={<MoreOutlined />} aria-label="更多画布设置" /></Dropdown></span></StudioCanvasTooltip>
      </div>
    </ConfigProvider>
  )
  // Portal 只移动视觉位置，保留 React Flow 上下文和工具事件。
  return (
    <>
      {studioMode === 'free' && chromeTarget ? createPortal(toolbar, chromeTarget) : toolbar}
      <StudioDisclaimerModal
        open={disclaimerOpen}
        variant={studioMode}
        onCancel={() => setDisclaimerOpen(false)}
        onAgree={() => {
          signStudioDisclaimer(window.localStorage, userId)
          setDisclaimerOpen(false)
          setLibraryOpen(true)
        }}
      />
      <StudioMaterialLibraryModal
        open={libraryOpen}
        onOpenChange={setLibraryOpen}
        nodes={nodes}
        assets={assets}
        onPick={placeLibraryPick}
      />
    </>
  )
}

export const StudioCanvasToolbar = memo(StudioCanvasToolbarComponent)

export function studioToolbarControlOrder(mode: 'flow' | 'free'): string[] {
  const primary = ['select', 'pan', 'library', 'undo', 'redo', 'fit']
  return mode === 'flow' ? ['add', ...primary, 'shortcuts', 'more'] : [...primary, 'add', 'shortcuts', 'more']
}
