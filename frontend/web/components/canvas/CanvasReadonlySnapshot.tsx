'use client'

/* eslint-disable @next/next/no-img-element -- 分享快照直接渲染服务端返回的媒体地址。 */

import '@xyflow/react/dist/style.css'
import { Background, Controls, ReactFlow, type Edge, type Node } from '@xyflow/react'
import { useMemo } from 'react'
import type { CanvasShareDetail } from '@/types/canvas-api'
import { StudioFreeThemeSwitch } from '@/components/studio/theme/StudioFreeThemeSwitch'

type PreviewNodeData = { title: string; prompt: string; mediaUrl?: string; mediaType?: string; nodeType: string; group?: boolean }

function objectRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function PreviewNode({ data }: { data: PreviewNodeData }) {
  return (
    <article className={`canvas-readonly-node${data.group ? ' canvas-readonly-node--group' : ''}`}>
      <header><span>{data.nodeType}</span><strong>{data.title}</strong></header>
      {data.group ? null : data.mediaUrl ? (
        data.mediaType === 'VIDEO'
          ? <video src={data.mediaUrl} controls playsInline preload="metadata" />
          : data.mediaType === 'AUDIO'
            ? <div className="canvas-readonly-node__audio"><audio src={data.mediaUrl} controls preload="metadata" /></div>
            : <img src={data.mediaUrl} alt="" />
      ) : <p>{data.prompt || '暂无内容'}</p>}
    </article>
  )
}

const nodeTypes = { readonlyCanvasNode: PreviewNode }

export function CanvasReadonlySnapshot({ detail }: { detail: CanvasShareDetail }) {
  const { nodes, edges } = useMemo(() => {
    const resourceById = new Map((detail.snapshot.resources || []).map((item) => [String(item.resourceId), item]))
    const currentByObject = new Map((detail.snapshot.currentResults || []).map((item) => [String(item.objectId), item]))
    const taskByObject = new Map((detail.snapshot.tasks || [])
      .filter((item) => item.objectId != null)
      .map((item) => [String(item.objectId), item]))
    const previewNodes: Node<PreviewNodeData>[] = []
    const previewEdges: Edge[] = []
    for (const item of detail.snapshot.objects || []) {
      if (item.deleted || !item.content) continue
      const content = objectRecord(item.content)
      if (item.objectType === 'NODE') {
        const position = objectRecord(content.position)
        const size = objectRecord(content.size)
        const ids = Array.isArray(content.resourceIds) ? content.resourceIds.map(String) : []
        const current = currentByObject.get(String(item.objectId))
        const task = taskByObject.get(String(item.objectId))
        const resource = ids.map((id) => resourceById.get(id)).find(Boolean)
        const mediaUrl = current?.resultUrl || task?.resultUrl || resource?.resourceUrl
        previewNodes.push({
          id: String(item.objectId),
          type: 'readonlyCanvasNode',
          position: { x: Number(position.x) || 0, y: Number(position.y) || 0 },
          draggable: false,
          selectable: true,
          style: { width: Number(size.width) || 320, height: Number(size.height) || 220 },
          data: {
            title: String(content.title || '未命名节点'),
            prompt: String(content.prompt || ''),
            mediaUrl,
            mediaType: String(current?.mediaType || task?.mediaType || resource?.mediaType || content.nodeType || '').toUpperCase(),
            nodeType: String(content.nodeType || 'NODE')
          }
        })
      } else if (item.objectType === 'GROUP') {
        const position = objectRecord(content.position)
        const size = objectRecord(content.size)
        previewNodes.push({
          id: String(item.objectId),
          type: 'readonlyCanvasNode',
          position: { x: Number(position.x) || 0, y: Number(position.y) || 0 },
          draggable: false,
          selectable: false,
          zIndex: -1,
          style: { width: Number(size.width) || 640, height: Number(size.height) || 420 },
          data: { title: String(content.title || '分组'), prompt: '', nodeType: 'GROUP', group: true }
        })
      } else if (item.objectType === 'EDGE') {
        previewEdges.push({
          id: String(item.objectId),
          source: String(content.sourceObjectId || ''),
          target: String(content.targetObjectId || ''),
          label: String(content.label || ''),
          animated: false
        })
      }
    }
    return { nodes: previewNodes, edges: previewEdges }
  }, [detail])

  return (
    <main className="canvas-readonly-preview">
      <header className="canvas-readonly-preview__topbar">
        <div className="canvas-access-page__brand">AI·D <span>STUDIO</span></div>
        <strong>{detail.projectName || '自由画布快照'}</strong>
        <div className="canvas-readonly-preview__status"><span>只读快照 · 版本 {detail.snapshotRevision}</span><StudioFreeThemeSwitch /></div>
      </header>
      <div className="canvas-readonly-preview__stage">
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView minZoom={0.2} maxZoom={2} nodesDraggable={false} nodesConnectable={false} elementsSelectable>
          <Background color="var(--studio-theme-grid)" gap={24} size={1} />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
    </main>
  )
}
