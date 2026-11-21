import * as THREE from "three";
import type { PrevizNode } from "@/types/previz";
type State = { mixer: THREE.AnimationMixer; clips: THREE.AnimationClip[]; key?: string; actions: THREE.AnimationAction[] };
const states = new WeakMap<THREE.Object3D, State>();

export function initializeGlbAnimation(root: THREE.Object3D, clips: THREE.AnimationClip[]) {
  const mixer = new THREE.AnimationMixer(root);
  states.set(root, { mixer, clips, actions: [] });
  return mixer;
}

export function validateGlbPlayback(root: THREE.Object3D, playback: PrevizNode["assetPlayback"]) {
  if (!playback) return;
  const state = states.get(root), clip = state?.clips[playback.clipIndex];
  if (!clip || playback.clipName !== undefined && playback.clipName !== clip.name)
    throw new Error("模型动画片段已变化，请重新选择后再采用");
}

/** Deterministic seeking, including backwards seeks after a non-looping clip reaches its end. */
export function evaluateGlbAnimation(root: THREE.Object3D, playback: PrevizNode["assetPlayback"], seconds: number) {
  const state = states.get(root); if (!state) { validateGlbPlayback(root, playback); return; }
  validateGlbPlayback(root, playback);
  const key = JSON.stringify(playback || null);
  if (state.key !== key) {
    state.mixer.stopAllAction();
    const clips = playback ? [state.clips[playback.clipIndex]] : state.clips;
    state.actions = clips.map(clip => {
      const action = state.mixer.clipAction(clip).reset();
      action.setLoop(playback && !playback.loop ? THREE.LoopOnce : THREE.LoopRepeat, playback && !playback.loop ? 1 : Infinity);
      action.clampWhenFinished = Boolean(playback && !playback.loop);
      action.play(); return action;
    });
    state.key = key;
  }
  state.actions.forEach(action => { action.paused = false; action.enabled = true; });
  const time = playback && !playback.loop ? Math.min(seconds, state.clips[playback.clipIndex].duration) : seconds;
  state.mixer.setTime(Math.max(0, time));
}

export function releaseGlbAnimation(root: THREE.Object3D) { states.delete(root); }
