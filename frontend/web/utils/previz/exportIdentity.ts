import type { Object3D } from "three";

/** Scope repeated asset identities only in the detached export runtime. */
export function prepareExportIdentities(root: Object3D, signal: AbortSignal) {
  const records: { object: Object3D; owner: string; ordinal: number; source?: string }[] = [];
  const counts = new Map<string, number>(), ordinals = new Map<string, number>();
  const stack = [{ object: root, owner: "scene" }];
  while (stack.length) {
    signal.throwIfAborted();
    const item = stack.pop()!, object = item.object;
    const owner = typeof object.userData.previzNodeId === "string" ? object.userData.previzNodeId : item.owner;
    const ordinal = ordinals.get(owner) ?? 0; ordinals.set(owner, ordinal + 1);
    const source = typeof object.userData.hf_id === "string" && object.userData.hf_id.trim() ? object.userData.hf_id : undefined;
    if (source) counts.set(source, (counts.get(source) ?? 0) + 1);
    records.push({ object, owner, ordinal, source });
    for (let i = object.children.length - 1; i >= 0; i--) stack.push({ object: object.children[i], owner });
  }
  const used = new Set(counts.keys());
  for (const { object, owner, ordinal, source } of records) {
    if (source && counts.get(source) === 1) continue;
    const base = `aid_export:${encodeURIComponent(owner)}:${source ? encodeURIComponent(source) : `object-${ordinal}`}`;
    let id = base, collision = 0; while (used.has(id)) id = `${base}:${++collision}`;
    used.add(id);
    if (source) object.userData.previzOriginalHfId ??= source;
    object.userData.hf_id = id;
  }
}
