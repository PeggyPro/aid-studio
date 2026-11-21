'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useRef, useState } from 'react'
import { message } from 'antd'
import { CheckOutlined, TagOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons'
import type { StudioCanvasNode, StudioNodeData } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioLabels } from '@/stores/studioLabels'
import { STUDIO_LABELS, studioLabel, studioNodeHasLabelContent } from '@/utils/studio/studioNodeLabels'
import { downloadMediaFile } from '@/utils/mediaFileDownload'
import { triggerBrowserBlobDownload } from '@/utils/business/compose'
import { StudioNodeIcon } from './StudioNodeIcon'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import './studio-node-labels.css'

const popover = { root: 'studio-composer-popover studio-composer-popover--canvas studio-label-popover' }

export function StudioNodeLabelPicker({ node }: { node: StudioCanvasNode }) {
  const [open, setOpen] = useState(false)
  const setLabels = useStudioUiStore((state) => state.setNodeLabels)
  const label = studioLabel(node.data.labelColor)
  if (!studioNodeHasLabelContent(node.data) && !label) return null
  return <StudioCanvasPopover trigger="click" placement="bottom" arrow={false} open={open} onOpenChange={setOpen} classNames={popover}
    content={<div className="studio-label-palette" aria-label="节点标签颜色">
      {STUDIO_LABELS.map((item) => <button key={item.id} type="button" aria-label={item.name} aria-pressed={label?.id === item.id}
        style={{ backgroundColor: item.color }} onClick={() => { setLabels([node.id], item.id); setOpen(false) }}>
        {label?.id === item.id && <CheckOutlined />}
      </button>)}
      {label && <button className="studio-label-clear" type="button" aria-label="移除标签" onClick={() => { setLabels([node.id]); setOpen(false) }}>×</button>}
    </div>}>
    <button type="button" className="studio-canvas-glass-toolbar__btn studio-canvas-glass-toolbar__btn--labeled" aria-label="标签" aria-expanded={open}>
      <span className="studio-label-icon"><TagOutlined />{label && <i style={{ backgroundColor: label.color }} />}</span><span>标签</span>
    </button>
  </StudioCanvasPopover>
}

export function StudioNodeLabelDecoration({ data }: { data: StudioNodeData }) {
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const mode = useStudioUiStore((state) => state.studioMode)
  const active = useStudioLabels((state) => state.scopeKey === scopeKey ? state.active : null)
  const label = studioLabel(data.labelColor)
  if (mode !== 'free' || data.flowBinding || !label) return null
  return <>
    <StudioCanvasTooltip title={`${label.name}标签`}><span className="studio-node-label-dot" style={{ backgroundColor: label.color }} /></StudioCanvasTooltip>
    {active === label.id && <span className="studio-node-label-outline" style={{ borderColor: label.color, boxShadow: `0 0 12px ${label.color}88, inset 0 0 5px ${label.color}55` }} />}
  </>
}

export function StudioCanvasLabels() {
  const nodes = useStudioUiStore((state) => state.nodes)
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const mode = useStudioUiStore((state) => state.studioMode)
  const active = useStudioLabels((state) => state.scopeKey === scopeKey ? state.active : null)
  const activate = useStudioLabels((state) => state.activate)
  const [opened, setOpened] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const downloading = useRef(false)
  const download = async (group: StudioCanvasNode[]) => {
    if (downloading.current) return
    downloading.current = true
    setBusy(true)
    let failed = 0
    try {
      for (const [index, node] of group.entries()) {
        try {
          const name = `${index + 1}-${node.data.title}`.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
          if (node.data.mediaUrl) await downloadMediaFile(node.data.mediaUrl, name)
          else if (node.data.directorSceneId || node.data.gridStoryboard) {
            const scene = node.data.directorSceneId ? useStudioUiStore.getState().directorScenes[node.data.directorSceneId] : undefined
            triggerBrowserBlobDownload(new Blob([JSON.stringify({ node: node.data, scene }, null, 2)], { type: 'application/json' }), `${name}.json`)
          } else triggerBrowserBlobDownload(new Blob([resolveStudioScriptNodeText(node.data)], { type: 'text/plain;charset=utf-8' }), `${name}.txt`)
        } catch { failed++ }
      }
      if (failed) message.warning(`${failed} 项下载失败，其余已发起下载`)
      else message.success('已发起全部下载，请允许浏览器保存多个文件')
    } finally { downloading.current = false; setBusy(false) }
  }
  if (mode !== 'free') return null
  return <div className="studio-canvas-labels nodrag nopan nowheel" aria-label="标签分组" onPointerDown={(event) => event.stopPropagation()}>
    {STUDIO_LABELS.map((label) => {
      const group = nodes.filter((node) => !node.data.flowBinding && node.data.labelColor === label.id)
      if (!group.length) return null
      return <StudioCanvasPopover key={label.id} trigger="click" placement="top" arrow={false} classNames={popover}
        open={opened === `${scopeKey}:${label.id}`} onOpenChange={(open) => { setOpened(open ? `${scopeKey}:${label.id}` : null); if (open) activate(scopeKey, label.id) }}
        content={<div className="studio-label-group">
          <header><i style={{ backgroundColor: label.color }} />{label.name}<span>{group.length} 个节点</span></header>
          <div className="studio-label-group__list">
            {group.map((node) => <StudioCanvasTooltip key={node.id} title={node.data.title}><button type="button" onClick={() => {
              useStudioUiStore.getState().setNodeFilter('all')
              useStudioUiStore.getState().selectNodes([node.id])
              window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: node.id } }))
            }}><StudioNodeIcon kind={node.data.kind} /><span>{node.data.title}</span></button></StudioCanvasTooltip>)}
          </div>
          <button type="button" disabled={busy} onClick={() => { void download(group) }}><DownloadOutlined />{busy ? '正在下载…' : '全部下载'}</button>
          <button type="button" className="is-danger" onClick={() => { useStudioUiStore.getState().setNodeLabels(group.map((node) => node.id)); setOpened(null); activate(scopeKey, null) }}><DeleteOutlined />全部移除标签</button>
        </div>}>
        <button type="button" className="studio-canvas-labels__color" style={{ backgroundColor: label.color }} aria-label={`${label.name}，${group.length} 个节点`} aria-pressed={active === label.id} />
      </StudioCanvasPopover>
    })}
    {active && <button className="studio-canvas-labels__close" type="button" aria-label="取消标签高亮" onClick={() => { activate(scopeKey, null); setOpened(null) }}>×</button>}
  </div>
}
