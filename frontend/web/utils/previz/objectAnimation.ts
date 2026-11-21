import type { PrevizNode, PrevizTrack } from "@/types/previz";
import { valueAt } from "./core";

/** Authored clips evaluate first; editor tracks then replace the corresponding editor delta. */
export function objectEditsAt(node: PrevizNode, tracks: PrevizTrack[], frame: number): PrevizNode["assetOverrides"] {
  if (!tracks.length) return node.assetOverrides;
  const result = { ...node.assetOverrides };
  for (const track of tracks) {
    if (!track.objectId) continue;
    const key = track.channel === "position" ? "positionOffset" : track.channel === "rotation" ? "rotationOffset" : "scaleMultiplier";
    result[track.objectId] = { ...result[track.objectId], [key]: valueAt(track, frame) };
  }
  return result;
}
