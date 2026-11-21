"use client";

import { useMemo, useState } from "react";
import { Color } from "three";
import type { PrevizAsset, PrevizNode, Vec3 } from "@/types/previz";
import { dataUrlBytes, inspectGlb } from "@/utils/previz/runtime";
import { Choice, NumberField } from "./PrevizControls";
import { PrevizKeyframeAction } from './PrevizKeyframeAction';
import type { PrevizScene, PrevizCommand } from "@/types/previz";
import { setKey } from "@/utils/previz/core";
import { objectEditsAt } from "@/utils/previz/objectAnimation";
import { glbObjectsForNode } from "@/utils/previz/glbObjects";

export function PrevizGlbObjectInspector({ node, asset, update, selectedId, onSelect, scene, frame = 0, execute }: {
  node: PrevizNode; asset?: PrevizAsset; update: (patch: Partial<Omit<PrevizNode, "id">>) => void;
  selectedId?: string; onSelect?: (id?: string) => void;
  scene?: PrevizScene; frame?: number; execute?: (commands: PrevizCommand[]) => void;
}) {
  const [selected, setSelected] = useState("");
  const model = useMemo(() => {
    try {
      if (!asset) return { parts: [], clips: [], error: "模型资产不存在" };
      const json = inspectGlb(dataUrlBytes(asset.dataUrl));
      type SourceNode = { name?: string; mesh?: number; camera?: number; extras?: { hf_id?: unknown } };
      const nodes = (json.nodes || []) as SourceNode[];
      const counts = new Map<string, number>();
      nodes.forEach(part => { if (typeof part.extras?.hf_id === "string") counts.set(part.extras.hf_id, (counts.get(part.extras.hf_id) || 0) + 1); });
      const allowed=new Set(glbObjectsForNode(node,asset).map(part=>part.id));
      const parts = nodes.flatMap((part, index) => {
        const id = part.extras?.hf_id;
        if (typeof id !== "string" || !allowed.has(id) || !id.trim() || id.length > 200 || counts.get(id) !== 1) return [];
        const materialIndex = part.mesh === undefined ? undefined : json.meshes?.[part.mesh]?.primitives?.[0]?.material;
        const rgba = materialIndex === undefined ? undefined : json.materials?.[materialIndex]?.pbrMetallicRoughness?.baseColorFactor;
        const color = Array.isArray(rgba) && rgba.length >= 3 ? new Color(rgba[0], rgba[1], rgba[2]).getHexString() : "ffffff";
        return [{ id, name: part.name || `对象 ${index + 1}`, mesh: part.mesh !== undefined, color: `#${color}` }];
      });
      const clips = ((json.animations || []) as { name?: string }[]).map((clip, index) => ({ index, name: clip.name?.slice(0,120) || `片段 ${index + 1}`, sourceName: clip.name && clip.name.length<=200 ? clip.name : undefined }));
      return { parts, clips, error: "" };
    } catch { return { parts: [], clips: [], error: "模型内部对象暂时无法读取，整体编辑仍可使用" }; }
  }, [asset?.dataUrl,node.assetObjectIds]);
  const part = model.parts.find(item => item.id === (onSelect ? selectedId : selected));
  const tracks = scene?.timeline.tracks.filter(t => t.nodeId === node.id && t.objectId) || [];
  const value = part ? objectEditsAt(node, tracks, frame)?.[part.id] || {} : {};
  const patch = (next: NonNullable<PrevizNode["assetOverrides"]>[string]) => {
    if (!part || node.locked) return;
    const animated: PrevizCommand[] = [];
    let evolving = scene;
    const staticPatch = { ...next };
    for (const [key, channel] of [["positionOffset","position"],["rotationOffset","rotation"],["scaleMultiplier","scale"]] as const) {
      if (next[key] && evolving && execute && tracks.some(t => t.objectId === part.id && t.channel === channel)) {
        const command = setKey(evolving,node.id,channel,frame,next[key]!,part.id);
        animated.push(command); if(command.type === "timeline.replace")evolving={...evolving,timeline:command.timeline};
        delete staticPatch[key];
      }
    }
    if (animated.length) execute?.(animated);
    if (Object.keys(staticPatch).length) update({ assetOverrides: { ...node.assetOverrides, [part.id]: { ...node.assetOverrides?.[part.id], ...staticPatch } } });
  };
  return <>
    {model.clips.length ? <details className="pv-glb-object-editor">
      <summary>模型动画（{model.clips.length}）</summary>
      <Choice showLabel label="模型动画片段" disabled={node.locked} value={node.assetPlayback?.clipIndex === undefined ? 'all' : String(node.assetPlayback.clipIndex)}
        items={[{key:'all',label:'全部片段混合（兼容原工程）'},...model.clips.map(clip=>({key:String(clip.index),label:clip.name}))]} onChange={value => {
          const clip = model.clips.find(item => String(item.index) === value);
          update({ assetPlayback: clip ? { clipIndex: clip.index, ...(clip.sourceName ? { clipName: clip.sourceName } : {}), loop: false } : undefined });
        }} />
      {node.assetPlayback ? <label className="pv-color-field">循环播放
        <input type="checkbox" aria-label="循环播放模型片段" disabled={node.locked} checked={node.assetPlayback.loop}
          onChange={event => update({ assetPlayback: { ...node.assetPlayback!, loop: event.target.checked } })} />
      </label> : null}
      <p className="pv-muted">跟随下方时间轴播放。单片段关闭循环时停在末帧；切换不会修改原始动画。</p>
    </details> : null}
    <details className="pv-glb-object-editor" open={Boolean(selectedId)}>
    <summary>模型内部对象（{model.parts.length}）</summary>
    {model.error ? <p role="alert">{model.error}</p> : !model.parts.length ? <p>此模型没有可用的稳定对象标识，可继续整体编辑。</p> : <>
      <Choice showLabel label="模型内部对象" value={part?.id || ''} disabled={node.locked}
        items={[{key:'',label:'整个模型'},...model.parts.map(item=>({key:item.id,label:item.name}))]}
        onChange={value=>{setSelected(value);onSelect?.(value||undefined)}} />
      {part ? <>
      <p className="pv-muted">在原始姿态与动画上叠加调整，不改写源模型。</p>
      {([['positionOffset', '内部位移', [0, 0, 0]], ['rotationOffset', '内部旋转', [0, 0, 0]], ['scaleMultiplier', '内部缩放', [1, 1, 1]]] as const).map(([key, label, fallback]) => <div key={key}>
        <div className="pv-property-label"><span>{label}</span>{scene && execute ? <PrevizKeyframeAction label={label} disabled={node.locked} onClick={() => execute([setKey(scene,node.id,key === "positionOffset" ? "position" : key === "rotationOffset" ? "rotation" : "scale",frame,[...(value[key] || fallback)] as Vec3,part.id)])} /> : null}</div><div className="pv-vector">{[0, 1, 2].map(axis => <NumberField key={axis}
          label={`${label} ${"XYZ"[axis]}`} value={(value[key]?.[axis] ?? fallback[axis]) * (key === "rotationOffset" ? 180 / Math.PI : 1)}
          min={key === "scaleMultiplier" ? 0.01 : -1000} max={key === "scaleMultiplier" ? 100 : 1000} step={key === "rotationOffset" ? 1 : 0.1}
          onChange={number => { const vector = [...(value[key] || fallback)] as Vec3; vector[axis] = number * (key === "rotationOffset" ? Math.PI / 180 : 1); patch({ [key]: vector }); }} />)}</div>
      </div>)}
      <label className="pv-color-field">显示此对象<input type="checkbox" aria-label="显示内部对象" checked={value.visible ?? true} onChange={event => patch({ visible: event.target.checked })} /></label>
      <label className="pv-color-field">材质颜色<input type="color" aria-label="内部对象颜色" value={value.color || part.color} onChange={event => patch({ color: event.target.value })} /></label>
      <button type="button" disabled={node.locked || !node.assetOverrides?.[part.id]&&!tracks.some(t=>t.objectId===part.id)} onClick={() => {
        const remaining = { ...node.assetOverrides }; delete remaining[part.id];
        if(scene&&execute)execute([{type:"node.update",id:node.id,patch:{assetOverrides:remaining}},
          {type:"timeline.replace",timeline:{...scene.timeline,tracks:scene.timeline.tracks.filter(t=>t.nodeId!==node.id||t.objectId!==part.id)},shots:scene.shots}]);
        else update({ assetOverrides: remaining });
      }}>恢复此对象原始状态</button>
      </> : <p className="pv-muted">从左侧对象树或视口选择人物、房屋、车辆等组合；按住 Alt 点击可选择内部零件。</p>}
    </>}
  </details></>;
}
