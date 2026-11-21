import * as THREE from "three";

/** Derive a runtime subgraph only. Immutable source assets, shared geometry and textures stay intact. */
export function pruneGlbObjectSubset(root:THREE.Object3D,ids:string[]|undefined,clips:THREE.AnimationClip[]) {
  const counts=new Map<string,number>(),byId=new Map<string,THREE.Object3D>();
  root.traverse(object=>{const id=object.userData.hf_id;if(typeof id==="string"&&id.trim()){counts.set(id,(counts.get(id)||0)+1);byId.set(id,object);}});
  const knownIds=new Set([...counts].filter(([,count])=>count===1).map(([id])=>id));
  if(!ids)return {knownIds,clips};
  const geometry=new Set<THREE.Object3D>(),allowed=new Set<THREE.Object3D>([root]);
  const ancestors=(object:THREE.Object3D)=>{for(let current:THREE.Object3D|null=object;current;current=current.parent)allowed.add(current);};
  for(const id of ids){const object=knownIds.has(id)?byId.get(id):undefined;if(!object)throw new Error("采用范围中的对象不存在或标识不唯一");
    object.traverse(child=>{geometry.add(child);allowed.add(child);});ancestors(object);
  }
  for(const object of geometry)if(object instanceof THREE.SkinnedMesh)for(const bone of object.skeleton.bones)ancestors(bone);
  const prune=(parent:THREE.Object3D)=>{
    for(const child of [...parent.children]){
      if(!allowed.has(child)){child.removeFromParent();child.traverse(o=>{if(o instanceof THREE.Light)o.dispose();});continue;}
      let retained=child;
      // A mesh can itself parent a selected part. Retain its transform, without rendering its geometry.
      if(child instanceof THREE.Mesh&&!geometry.has(child)){
        const group=new THREE.Group();group.name=child.name;group.uuid=child.uuid;group.userData={...child.userData};
        group.position.copy(child.position);group.quaternion.copy(child.quaternion);group.scale.copy(child.scale);
        group.matrix.copy(child.matrix);group.matrixAutoUpdate=child.matrixAutoUpdate;group.visible=child.visible;
        parent.add(group);for(const descendant of [...child.children])group.add(descendant);child.removeFromParent();retained=group;
      }
      prune(retained);
    }
  };
  prune(root);
  const names=new Set<string>();root.traverse(object=>{names.add(object.name);names.add(object.uuid);});
  return {knownIds,clips:clips.map(clip=>new THREE.AnimationClip(clip.name,clip.duration,clip.tracks.filter(track=>{
    const name=THREE.PropertyBinding.parseTrackName(track.name).nodeName;
    return !name||name==="."||names.has(name);
  }),clip.blendMode))};
}
