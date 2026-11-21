"use client";
import { memo, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  CameraOutlined,
  DeleteOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  LockOutlined,
  UnlockOutlined,
} from "@ant-design/icons";
import type { PrevizNode, PrevizCommand, PrevizAsset } from "@/types/previz";
import { glbObjectsForAsset, glbObjectsForNode } from "@/utils/previz/glbObjects";
import { BoxMark, Tool } from "./PrevizControls";
import { StudioCanvasTooltip } from "@/components/studio/canvas/StudioCanvasTooltip";

/** Only visible rows mount tooltips and controls; scene size must not scale UI work per frame. */
export const PrevizObjectList = memo(function PrevizObjectList({
  nodes,
  assets = [],
  selectedId,
  selectedObjectId,
  onSelect,
  execute,
  onDelete,
  search,
  viewport = 'panel',
}: {
  nodes: PrevizNode[];
  assets?: PrevizAsset[];
  selectedId: string;
  selectedObjectId?: string;
  onSelect: (id: string, objectId?: string) => void;
  execute: (commands: PrevizCommand[]) => void;
  onDelete?: (id: string) => void;
  search?: string;
  /** Embedded trees own one bounded virtual scroll area instead of inheriting the full editor height. */
  viewport?: 'panel' | 'embedded';
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [localQuery, setQuery] = useState(""),
    [top, setTop] = useState(0),
    [height, setHeight] = useState(320);
  const query = search ?? localQuery;
  useEffect(() => {
    setTop(0);
    if (scroller.current) scroller.current.scrollTop = 0;
  }, [query]);
  const filtered = useMemo(() => {
    const result: { node: PrevizNode; objectId?: string; name: string; key: string; depth: number; children: boolean }[] = [];
    const term = query.toLocaleLowerCase();
    const visit = (node: PrevizNode, depth: number) => {
      let parts: ReturnType<typeof glbObjectsForAsset> = [];
      try { parts = glbObjectsForNode(node,assets.find(a => a.id === node.assetId)); } catch { /* Whole-model editing stays available. */ }
      const children = nodes.filter(n => n.parentId === node.id);
      if (!term || `${node.name} ${node.kind}`.toLocaleLowerCase().includes(term) || parts.some(p => p.name.toLocaleLowerCase().includes(term)))
        result.push({ node, name: node.name, key: node.id, depth, children: Boolean(parts.length || children.length) });
      if (expanded.has(node.id) || term || selectedId === node.id && selectedObjectId) {
        const open = new Set<string>();
        if (selectedId === node.id && selectedObjectId) {
          let part = parts.find(p => p.id === selectedObjectId);
          while (part) { open.add(`${node.id}/${part.id}`); part = parts.find(p => p.id === part!.parentId); }
        }
        const parentMap = new Map(parts.map(p => [p.id,p]));
        for (const part of parts) {
          const key = `${node.id}/${part.id}`;
          let visible = true, parent = part.parentId;
          while (parent) { if (!expanded.has(`${node.id}/${parent}`) && !open.has(`${node.id}/${parent}`)) visible = false; parent = parentMap.get(parent)?.parentId ?? null; }
          if (term ? !part.name.toLocaleLowerCase().includes(term) : !visible) continue;
          result.push({ node, objectId: part.id, name: part.name, key, depth: depth + 1 + part.depth, children: parts.some(p => p.parentId === part.id) });
        }
        children.forEach(child => visit(child,depth+1));
      }
    };
    nodes.filter(n => !n.parentId).forEach(n => visit(n,0));
    return result;
  }, [nodes, assets, query, expanded, selectedId, selectedObjectId]);
  const start = Math.max(0, Math.floor(top / 40) - 5),
    end = Math.min(filtered.length, Math.ceil((top + height) / 40) + 5);
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const observer = new ResizeObserver(() => setHeight(el.clientHeight));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    const el = scroller.current,
      index = filtered.findIndex((row) => row.node.id === selectedId && row.objectId === selectedObjectId);
    if (!el || index < 0) return;
    const y = index * 40;
    if (y < el.scrollTop) el.scrollTop = y;
    else if (y + 40 > el.scrollTop + el.clientHeight)
      el.scrollTop = y + 40 - el.clientHeight;
  }, [selectedId, selectedObjectId, filtered]);
  return (
    <>
      {search === undefined && (nodes.some(n => n.kind === "GLB") || nodes.length > 30 || query) ? (
        <div className="pv-object-search">
          <input
            aria-label="搜索场景对象"
            placeholder="搜索场景对象…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      ) : null}
      <div
        className={`pv-object-list${viewport === 'embedded' ? ' pv-object-list--embedded' : ''}`}
        style={viewport === 'embedded' ? { '--pv-object-list-content-height': `${filtered.length ? filtered.length * 40 : 64}px` } as CSSProperties : undefined}
        aria-label="场景对象列表"
        tabIndex={0}
        ref={scroller}
        onScroll={(e) => setTop(e.currentTarget.scrollTop)}
        onKeyDown={(e) => {
          if (!["ArrowUp", "ArrowDown"].includes(e.key) || !filtered.length)
            return;
          e.preventDefault();
          const index = filtered.findIndex(row => row.node.id === selectedId && row.objectId === selectedObjectId);
          const next = filtered[
              Math.max(
                0,
                Math.min(
                  filtered.length - 1,
                  index + (e.key === "ArrowDown" ? 1 : -1),
                ),
              )
            ];
          onSelect(next.node.id, next.objectId);
          e.currentTarget.focus();
        }}
      >
        <div style={{ height: filtered.length * 40, position: "relative" }}>
          <div
            style={{ position: "absolute", top: start * 40, left: 0, right: 0 }}
          >
            {filtered.slice(start, end).map(({ node, objectId, name, key, depth, children }) => (
              <div
                key={key}
                className={`pv-object${selectedId === node.id && selectedObjectId === objectId ? " is-selected" : ""}${node.visible && (!objectId || node.assetOverrides?.[objectId]?.visible !== false) ? "" : " is-hidden"}`}
                style={{ paddingLeft: 8 + Math.min(depth,12)*14 }}
              >
                {children ? <button type="button" className="pv-object-expand" aria-label={`${expanded.has(key) ? "收起" : "展开"} ${name}`} aria-expanded={expanded.has(key)} onClick={() => setExpanded(current => { const next = new Set(current); if(next.has(key))next.delete(key);else next.add(key); return next; })}>{expanded.has(key) ? "▾" : "▸"}</button> : <span className="pv-object-indent" />}
                <StudioCanvasTooltip title={name || "未命名对象"}>
                  <button
                    className="pv-object-main"
                    aria-pressed={selectedId === node.id && selectedObjectId === objectId}
                    onClick={() => onSelect(node.id, objectId)}
                  >
                    <span className="pv-object-glyph">
                      {node.kind === "CAMERA" ? (
                        <CameraOutlined />
                      ) : node.kind === "ACTOR" ? (
                        <span className="pv-actor-glyph" />
                      ) : (
                        <BoxMark small />
                      )}
                    </span>
                    <span>{name || "未命名对象"}</span>
                  </button>
                </StudioCanvasTooltip>
                {!objectId ? <><Tool
                  label={`${node.visible ? "隐藏" : "显示"} ${node.name}`}
                  icon={
                    node.visible ? <EyeOutlined /> : <EyeInvisibleOutlined />
                  }
                  onClick={() =>
                    execute([
                      {
                        type: "node.update",
                        id: node.id,
                        patch: { visible: !node.visible },
                      },
                    ])
                  }
                />
                <Tool
                  label={`${node.locked ? "解锁" : "锁定"} ${node.name}`}
                  icon={node.locked ? <LockOutlined /> : <UnlockOutlined />}
                  onClick={() =>
                    execute([
                      {
                        type: "node.update",
                        id: node.id,
                        patch: { locked: !node.locked },
                      },
                    ])
                  }
                />
                {onDelete && <Tool
                  label={node.locked ? "请先解锁 " + name : "删除 " + name}
                  icon={<DeleteOutlined />}
                  disabled={node.locked}
                  onClick={() => onDelete(node.id)}
                />}
                </> : <Tool label={`${node.assetOverrides?.[objectId]?.visible === false ? "显示" : "隐藏"} ${name}`} icon={node.assetOverrides?.[objectId]?.visible === false ? <EyeInvisibleOutlined/> : <EyeOutlined/>} disabled={node.locked} onClick={() => execute([{type:"node.update",id:node.id,patch:{assetOverrides:{...node.assetOverrides,[objectId]:{...node.assetOverrides?.[objectId],visible:node.assetOverrides?.[objectId]?.visible === false}}}}])}/>}
              </div>
            ))}
          </div>
        </div>
        {!filtered.length ? (
          <p className="pv-object-empty">没有匹配的对象</p>
        ) : null}
      </div>
    </>
  );
});
