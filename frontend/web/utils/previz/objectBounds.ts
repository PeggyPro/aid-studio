import * as THREE from "three";

/** Box3.setFromObject includes hidden subtrees, which makes a scoped object frame the whole source scene. */
export function visibleObjectBounds(root:THREE.Object3D,target=new THREE.Box3()) {
  target.makeEmpty();root.updateWorldMatrix(true,true);
  const box=new THREE.Box3();
  root.traverseVisible(object=>{
    if(!(object instanceof THREE.Mesh))return;
    let bounds:THREE.Box3|null;
    if(object instanceof THREE.SkinnedMesh||object instanceof THREE.InstancedMesh){object.computeBoundingBox();bounds=object.boundingBox;}
    else {if(!object.geometry.boundingBox)object.geometry.computeBoundingBox();bounds=object.geometry.boundingBox;}
    if(bounds)target.union(box.copy(bounds).applyMatrix4(object.matrixWorld));
  });
  return target;
}
