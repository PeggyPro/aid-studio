"use client";

import { useMemo, useState } from "react";
import { Button, Modal } from "antd";
import type { GlbObject } from "@/utils/previz/glbObjects";
import type { PrevizScene } from "@/types/previz";
import type { PrevizBuildOperation } from "@/utils/previz/buildAdoption";

export function PrevizAdoptionScopeDialog({objects,scene,editorVersion,targetNodeId,onChoose}: {
  objects:GlbObject[];scene:PrevizScene;editorVersion:string;targetNodeId?:string;
  onChoose:(operation:PrevizBuildOperation|null)=>void;
}){
  const [query,setQuery]=useState(""),[selected,setSelected]=useState<string[]>([]);
  const [limit,setLimit]=useState(150);
  const parentIds=useMemo(()=>new Set(objects.map(o=>o.parentId)),[objects]);
  const matches=objects.filter(o=>!o.bone&&(o.mesh||parentIds.has(o.id))&&(query?`${o.name} ${o.id}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()):o.depth<2));
  const choices=matches.slice(0,limit);
  const target=scene.nodes.find(n=>n.id===targetNodeId&&n.kind==="GLB"&&!n.locked);
  const choose=(kind:PrevizBuildOperation["kind"])=>onChoose({version:1,kind,editorVersion,sceneDigest:"manual-selection",
    targetNodeIds:kind==="MODIFY"&&target?[target.id]:kind==="REBUILD_SCENE"?scene.nodes.filter(n=>!n.parentId&&!["CAMERA","LIGHT"].includes(n.kind)).map(n=>n.id):[],
    outputObjectIds:kind==="MODIFY"?[]:selected,baseNodes:scene.nodes});
  return <Modal title="选择生成结果的采用范围" open centered rootClassName="pv-modal" footer={null} onCancel={()=>onChoose(null)}>
    <p>这个历史构建没有记录新增或替换范围。请选择要加入的组合或物体，原场景会保留；组合已经包含它的零件。</p>
    <input aria-label="搜索生成对象" placeholder="搜索人物、房屋或零件…" value={query} onChange={e=>{setQuery(e.target.value);setLimit(150);}}/>
    <div className="pv-adoption-objects">{choices.map(object=><label key={object.id} style={{paddingLeft:12+Math.min(object.depth,8)*12}}>
      <input type="checkbox" checked={selected.includes(object.id)} onChange={event=>setSelected(current=>event.target.checked?[...current,object.id]:current.filter(id=>id!==object.id))}/><span>{object.name}</span>
    </label>)}</div>
    {matches.length>limit?<Button onClick={()=>setLimit(value=>value+150)}>显示更多对象（{choices.length}/{matches.length}）</Button>:null}
    <p className="pv-muted">已选择 {selected.length} 个对象。选中的组合可整体拖动，也能展开编辑内部零件。</p>
    <div className="pv-adoption-actions">
      <Button type="primary" disabled={!selected.length} onClick={()=>choose("ADD")}>加入所选对象</Button>
      {target?<Button onClick={()=>choose("MODIFY")}>继续修改「{target.name}」，保留编辑</Button>:null}
      <Button danger disabled={!selected.length||scene.nodes.some(n=>n.locked&&!["CAMERA","LIGHT"].includes(n.kind))} onClick={()=>choose("REBUILD_SCENE")}>用所选结果重建场景</Button>
      <Button onClick={()=>onChoose(null)}>暂不采用</Button>
    </div>
  </Modal>;
}
