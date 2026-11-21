import * as THREE from 'three';

// glTF encodes a punctual light's -Z direction, whereas Three lights aim at a
// separate target. Resolve that difference in the exporter, not in model text.
export function prepareLightTargets(root, { fps = 30, maximumSampleValues = 4_000_000 } = {}) {
  if (!Number.isInteger(fps) || fps < 1 || fps > 120 || !Number.isSafeInteger(maximumSampleValues) || maximumSampleValues < 1)
    throw new Error('ANIMATION_EXPORT_LIMIT: 灯光动画采样配置无效');
  const lights = [], ids = new Set();
  root.traverse(object => {
    if (object.userData?.hf_id) ids.add(object.userData.hf_id);
    if ((object.isDirectionalLight || object.isSpotLight) && object.target &&
        !(object.target.parent === object && object.target.position.equals(new THREE.Vector3(0, 0, -1)))) lights.push(object);
  });
  if (!lights.length) return [];
  const affected = [...new Set(lights.flatMap(light => [light, ...light.children]))];
  const save = () => affected.map(object => ({ object, position: object.position.clone(), quaternion: object.quaternion.clone(), scale: object.scale.clone(), matrix: object.matrix.clone(), auto: object.matrixAutoUpdate }));
  const restore = states => {
    for (const state of states) {
      state.object.position.copy(state.position); state.object.quaternion.copy(state.quaternion); state.object.scale.copy(state.scale);
      state.object.matrix.copy(state.matrix); state.object.matrixAutoUpdate = state.auto;
    }
    root.updateMatrixWorld(true);
  };
  const orient = () => {
    root.updateMatrixWorld(true);
    const targets = lights.map(light => light.target.getWorldPosition(new THREE.Vector3()));
    for (let i = 0; i < lights.length; i++) {
      const light = lights[i], children = light.children.map(child => [child, child.matrixWorld.clone()]);
      const origin = light.getWorldPosition(new THREE.Vector3());
      if (origin.distanceToSquared(targets[i]) < 1e-16) throw new Error('INVALID_LIGHT_TARGET: 灯光目标不能与灯光位置重合');
      const localTarget = targets[i].clone();
      if (light.parent) {
        if (Math.abs(light.parent.matrixWorld.determinant()) < 1e-16) throw new Error('INVALID_LIGHT_TARGET: 灯光父级变换不可逆');
        localTarget.applyMatrix4(light.parent.matrixWorld.clone().invert());
      }
      light.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, -1), localTarget.sub(light.position).normalize());
      light.updateMatrix(); root.updateMatrixWorld(true);
      const inverse = light.matrixWorld.clone().invert();
      for (const [child, world] of children) {
        child.matrix.multiplyMatrices(inverse, world);
        child.matrix.decompose(child.position, child.quaternion, child.scale);
        const reconstructed = new THREE.Matrix4().compose(child.position, child.quaternion, child.scale);
        if (child.matrix.elements.some((v, index) => Math.abs(v - reconstructed.elements[index]) > 1e-5))
          throw new Error('UNSUPPORTED_LIGHT_HIERARCHY: 灯光子级包含无法无损导出的剪切变换');
        child.updateMatrix();
      }
      root.updateMatrixWorld(true);
    }
  };
  const initial = save(), clips = root.animations ?? [], baked = [];
  let values = 0;
  for (const clip of clips) {
    if (!Number.isFinite(clip.duration) || clip.duration < 0) throw new Error('INVALID_ANIMATION: 动画时长无效');
    const frameCount = Math.ceil(clip.duration * fps);
    if (!Number.isSafeInteger(frameCount) || (values += (frameCount + 1) * affected.length * 10) > maximumSampleValues)
      throw new Error('ANIMATION_EXPORT_LIMIT: 灯光动画采样超过执行预算');
    const times = new Set([0, clip.duration]);
    for (let frame = 0; frame < frameCount; frame++) times.add(frame / fps);
    for (const track of clip.tracks) for (const time of track.times) if (time >= 0 && time <= clip.duration) times.add(time);
    const ordered = [...times].sort((a, b) => a - b);
    values += (ordered.length - frameCount - 1) * affected.length * 10;
    if (values > maximumSampleValues) throw new Error('ANIMATION_EXPORT_LIMIT: 灯光动画采样超过执行预算');
    restore(initial);
    const mixer = new THREE.AnimationMixer(root), action = mixer.clipAction(clip);
    action.setLoop(THREE.LoopOnce, 1); action.clampWhenFinished = true; action.play();
    const samples = affected.map(object => ({ object, position: [], quaternion: [], scale: [] }));
    try {
      for (const time of ordered) {
        mixer.setTime(time); root.updateMatrixWorld(true);
        const authoredFrame = save();
        orient();
        for (const sample of samples) {
          sample.position.push(...sample.object.position); sample.quaternion.push(...sample.object.quaternion); sample.scale.push(...sample.object.scale);
        }
        restore(authoredFrame);
      }
    } finally { mixer.stopAllAction(); mixer.uncacheRoot(root); restore(initial); }
    const tracks = clip.tracks.filter(track => {
      const binding = THREE.PropertyBinding.parseTrackName(track.name);
      return !(['position', 'quaternion', 'scale'].includes(binding.propertyName) &&
        !binding.objectName && affected.includes(THREE.PropertyBinding.findNode(root, binding.nodeName)));
    }).map(track => track.clone());
    for (const sample of samples) {
      tracks.push(new THREE.VectorKeyframeTrack(sample.object.uuid + '.position', ordered, sample.position),
        new THREE.QuaternionKeyframeTrack(sample.object.uuid + '.quaternion', ordered, sample.quaternion),
        new THREE.VectorKeyframeTrack(sample.object.uuid + '.scale', ordered, sample.scale));
    }
    baked.push(new THREE.AnimationClip(clip.name, clip.duration, tracks, clip.blendMode));
  }
  restore(initial); orient();
  for (const light of lights) {
    const target = new THREE.Object3D(); target.position.set(0, 0, -1);
    const base = `aid_export_light_target:${light.userData.hf_id}`;
    let id = base, suffix = 0; while (ids.has(id)) id = `${base}:${++suffix}`;
    ids.add(id); target.userData.hf_id = id; light.add(target); light.target = target;
  }
  root.animations = baked;
  // All exporter warnings use the same object-scoped receipt contract. An
  // aggregate {fps, lightCount} warning has no identity and is rejected by the
  // build validator, even though the GLB was exported successfully.
  return clips.length ? lights.map(light => ({
    code: 'LIGHT_TARGET_ANIMATION_BAKED', objectId: light.userData.hf_id, type: light.type,
  })) : [];
}
