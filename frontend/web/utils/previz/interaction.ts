import type { PrevizTrack, Vec3 } from "@/types/previz";

/** One frame coordinate system for the ruler, slider, playhead and keyframes. */
export function frameProgress(frame: number, frameCount: number) {
  return Math.max(0, Math.min(1, frame / Math.max(1, frameCount - 1)));
}

/** Hand sampling frequency must not determine movement speed. */
export function positionPathKeys(
  points: Vec3[],
  height: number,
  frameCount: number,
  spatial = false,
) {
  if (
    !Number.isInteger(frameCount) ||
    frameCount < 2 ||
    !Number.isFinite(height) ||
    points.some((p) => p.some((v) => !Number.isFinite(v)))
  )
    throw new Error("路径参数无效");
  const filtered: Vec3[] = [];
  for (const p of points) {
    const previous = filtered.at(-1);
    if (!previous || Math.hypot(p[0] - previous[0], spatial ? p[1]-previous[1] : 0, p[2] - previous[2]) > 0.001)
      filtered.push(p);
  }
  if (filtered.length < 2) throw new Error("请绘制一段有长度的路径");
  const distances = [0];
  for (let i = 1; i < filtered.length; i++)
    distances.push(
      distances[i - 1] +
        Math.hypot(
          filtered[i][0] - filtered[i - 1][0],
          spatial ? filtered[i][1] - filtered[i - 1][1] : 0,
          filtered[i][2] - filtered[i - 1][2],
        ),
    );
  const total = distances.at(-1)!;
  if (total < 0.01) throw new Error("路径太短，请重新绘制");
  const keys = new Map<number, { frame: number; value: Vec3 }>();
  filtered.forEach((p, i) => {
    const frame = Math.round((distances[i] / total) * (frameCount - 1));
    if (frame === 0 && keys.has(0)) return;
    keys.set(frame, { frame, value: [p[0], spatial ? p[1] : height, p[2]] });
  });
  return [...keys.values()];
}

export function moveTrackKey(
  track: PrevizTrack,
  from: number,
  to: number,
  frameCount: number,
): PrevizTrack {
  if (!Number.isInteger(to) || to < 0 || to >= frameCount)
    throw new Error("关键帧位置超出时间轴");
  if (!track.keys.some((k) => k.frame === from))
    throw new Error("关键帧已变化，请重新选择");
  if (from === to) return track;
  if (track.keys.some((k) => k.frame === to))
    throw new Error("目标帧已有关键帧，请选择其他位置");
  return {
    ...track,
    keys: track.keys
      .map((k) => (k.frame === from ? { ...k, frame: to } : k))
      .sort((a, b) => a.frame - b.frame),
  };
}
