import React, { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Collapse, Empty, Select, Space, Spin, Table, Tabs } from 'antd';
import { type AuditId, type CanvasResource } from '@/api/aid/canvasAudit';
import InspectData from './InspectData';
import type { ResourceState } from './auditResources';

const ScenePreview = lazy(() => import('./ThreePreview'));
function Layout({ canvas, select }: { canvas: Record<string, any>; select: (id: string) => void }) {
  const [zoom, setZoom] = useState(1);
  const objects = canvas.objects || [];
  const nodes = objects.filter((o: any) => o.objectType !== 'EDGE' && o.objectType !== 'CONNECTION');
  const rects = nodes.map((o: any) => {
    const c = o.content || {};
    return {
      object: o,
      x: Number(c.position?.x ?? c.position?.[0] ?? 0),
      y: Number(c.position?.y ?? c.position?.[1] ?? 0),
      w: Number(c.size?.width ?? c.size?.[0] ?? 260),
      h: Number(c.size?.height ?? c.size?.[1] ?? 160)
    };
  });
  const minX = Math.min(0, ...rects.map((r: any) => r.x)) - 30,
    minY = Math.min(0, ...rects.map((r: any) => r.y)) - 30;
  const width = Math.max(600, ...rects.map((r: any) => r.x + r.w)) - minX + 30,
    height = Math.max(400, ...rects.map((r: any) => r.y + r.h)) - minY + 30;
  const byId = new Map(rects.map((r: any) => [String(r.object.objectId), r]));
  const renderRect = (r: any) => (
    <g
      key={r.object.objectId}
      tabIndex={0}
      role="button"
      aria-label={`检查 ${r.object.content?.title || r.object.objectId}`}
      onClick={() => select(String(r.object.objectId))}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          select(String(r.object.objectId));
        }
      }}
      className={r.object.objectType === 'GROUP' ? 'snapshot-group' : 'snapshot-node'}
    >
      <rect x={r.x} y={r.y} width={r.w} height={r.h} rx={6} />
      <text x={r.x + 12} y={r.y + 24}>
        {String(r.object.content?.title || r.object.objectId)}
      </text>
      <text x={r.x + 12} y={r.y + 48}>
        {String(r.object.content?.nodeType || r.object.objectType)}
      </text>
      <title>{String(r.object.content?.textContent || r.object.content?.prompt || '')}</title>
    </g>
  );
  return (
    <>
      <Space wrap>
        <span>完整画布（含折叠分组与视野外节点）</span>
        <Button onClick={() => setZoom((z) => Math.max(0.1, z / 1.4))} aria-label="缩小画布">
          缩小
        </Button>
        <Button onClick={() => setZoom((z) => Math.min(3, z * 1.4))} aria-label="放大画布">
          放大
        </Button>
        <Button onClick={() => setZoom(1)}>原比例</Button>
        <Button onClick={() => setZoom(Math.min(1, 900 / width, 500 / height))}>适合完整画布</Button>
      </Space>
      <div className="canvas-audit-layout">
        <svg
          width={width * zoom}
          height={height * zoom}
          viewBox={`${minX} ${minY} ${width} ${height}`}
          role="img"
          aria-label="固定快照节点、分组与连线布局"
        >
          {rects.filter((r: any) => r.object.objectType === 'GROUP').map(renderRect)}
          {objects
            .filter((o: any) => ['EDGE', 'CONNECTION'].includes(o.objectType))
            .map((o: any) => {
              const c = o.content || {};
              const a: any = byId.get(String(c.sourceObjectId ?? c.sourceNodeId)),
                b: any = byId.get(String(c.targetObjectId ?? c.targetNodeId));
              return a && b ? (
                <g key={o.objectId}>
                  <title>
                    {String(o.objectId)}：{String(c.label || '')}
                  </title>
                  <path
                    d={`M ${a.x + a.w} ${a.y + a.h / 2} C ${a.x + a.w + 80} ${a.y + a.h / 2}, ${b.x - 80} ${b.y + b.h / 2}, ${b.x} ${b.y + b.h / 2}`}
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={2}
                  />
                </g>
              ) : null;
            })}
          {rects.filter((r: any) => r.object.objectType !== 'GROUP').map(renderRect)}
        </svg>
      </div>
    </>
  );
}

export default function Snapshot({
  publicationId,
  canvas,
  resources,
  onPreviewReady
}: {
  publicationId: AuditId;
  canvas: Record<string, any>;
  resources: ResourceState;
  onPreviewReady?: (ready: boolean) => void;
}) {
  const [selected, setSelected] = useState<string>();
  const [sceneId, setSceneId] = useState<string>();
  const [sceneStates, setSceneStates] = useState<Record<string, boolean>>({});
  const scenes = useMemo(() => canvas.sceneDocument?.scenes || [], [canvas]);
  const scene = scenes.find((s: any) => s.id === sceneId) || scenes[0];
  const object = canvas.objects?.find((o: any) => String(o.objectId) === selected);
  const resourceRows = useMemo(() => (canvas.resources || []) as CanvasResource[], [canvas]);
  const [mediaFailed, setMediaFailed] = useState(false);
  useEffect(() => {
    setMediaFailed(false);
    setSceneStates({});
  }, [canvas]);
  useEffect(() => {
    onPreviewReady?.(!mediaFailed && resources.ready && scenes.every((s: any) => sceneStates[String(s.id)]));
  }, [scenes, sceneStates, mediaFailed, resources.ready, onPreviewReady]);
  const media = (r: CanvasResource) => {
    const url = resources.urls[String(r.resourceId)];
    if (!url) return <span>待加载</span>;
    const type = r.mediaType || r.resourceType;
    const fail = () => {
      setMediaFailed(true);
      onPreviewReady?.(false);
    };
    if (type === 'IMAGE') return <img src={url} alt={`审核资源 ${r.resourceId}`} loading="lazy" onError={fail} />;
    if (type === 'VIDEO') return <video src={url} controls preload="metadata" onError={fail} />;
    if (type === 'AUDIO') return <audio src={url} controls preload="metadata" onError={fail} />;
    return <span>{type || r.mimeType || '文件'} · 在模型/场景中检查</span>;
  };
  return (
    <div className="canvas-audit-snapshot">
      {resources.error && <Alert type="error" showIcon message={resources.error} />}{' '}
      {mediaFailed && <Alert type="error" showIcon message="部分媒体无法解码，请核查固定文件；当前不能通过审核" />}
      <Tabs
        items={[
          {
            key: 'layout',
            label: `画布布局与节点（${canvas.objects?.length || 0}）`,
            children: (
              <>
                <Layout canvas={canvas} select={setSelected} />
                <Select
                  aria-label="选择检查对象"
                  style={{ width: '100%' }}
                  value={selected}
                  onChange={setSelected}
                  placeholder="选择任意节点、分组或连线查看全部正文与参数"
                  options={(canvas.objects || []).map((o: any) => ({
                    value: String(o.objectId),
                    label: `${o.content?.title || o.objectId} · ${o.objectType}`
                  }))}
                />
                {object && (
                  <Card size="small" title="固定对象全部字段">
                    <InspectData value={object} />
                  </Card>
                )}
                <Collapse
                  items={[
                    {
                      key: 'results',
                      label: `当前选中结果（${canvas.currentResults?.length || 0}）`,
                      children: <InspectData value={canvas.currentResults} />
                    }
                  ]}
                />
              </>
            )
          },
          {
            key: 'media',
            label: `全部资源（${resourceRows.length}）`,
            children: (
              <Table
                rowKey={(r) => String(r.resourceId)}
                pagination={{ pageSize: 8 }}
                scroll={{ x: 650 }}
                dataSource={resourceRows}
                columns={[
                  { title: '资源', dataIndex: 'resourceId' },
                  { title: '类型', dataIndex: 'mediaType' },
                  { title: '固定文件预览', render: (_, r) => <div className="canvas-audit-media">{media(r)}</div> },
                  {
                    title: '资源资料及完整依赖',
                    render: (_, r) => (
                      <details>
                        <summary>查看字段</summary>
                        <InspectData value={r} />
                      </details>
                    )
                  }
                ]}
              />
            )
          },
          {
            key: 'scene',
            label: `完整场景（${scenes.length}）`,
            forceRender: true,
            children: scenes.length ? (
              <>
                <Select
                  aria-label="选择场景"
                  value={scene?.id}
                  style={{ width: '100%' }}
                  onChange={setSceneId}
                  options={scenes.map((s: any) => ({ value: s.id, label: s.name || s.id }))}
                />
                {scenes.map((current: any) => (
                  <div
                    key={`${publicationId}:${current.id}`}
                    style={{ display: current.id === scene?.id ? 'block' : 'none' }}
                  >
                    <Suspense fallback={<Spin tip="加载只读场景查看器" />}>
                      <ScenePreview
                        scene={current}
                        visible={current.id === scene?.id}
                        resources={resourceRows}
                        files={resources}
                        onReady={(ready) =>
                          setSceneStates((previous) =>
                            previous[String(current.id)] === ready
                              ? previous
                              : { ...previous, [String(current.id)]: ready }
                          )
                        }
                      />
                    </Suspense>
                    <Collapse
                      items={[
                        {
                          key: 'all',
                          label: '完整场景字段：对象、姿态、相机、灯光、时间线、模型及场景包',
                          children: <InspectData value={current} />
                        }
                      ]}
                    />
                  </div>
                ))}
              </>
            ) : (
              <Empty description="此固定版本没有场景对象" />
            )
          }
        ]}
      />
    </div>
  );
}
