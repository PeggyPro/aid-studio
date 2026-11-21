"use client";

import type { PrevizScene, PrevizTrack } from "@/types/previz";
import { useRef, useState } from "react";
import { StudioCanvasTooltip } from "@/components/studio/canvas/StudioCanvasTooltip";
import { frameProgress } from "@/utils/previz/interaction";
import { glbObjectsForNode } from "@/utils/previz/glbObjects";
import { PrevizShotClip } from './PrevizShotClip';
import type { PrevizShotEdge } from '@/utils/previz/shotEditing';

const channelNames = {
  position: "位置",
  rotation: "旋转",
  scale: "缩放",
  target: "瞄准点",
};

function Keyframe({
  track,
  keyFrame,
  frameCount,
  scaleFrames,
  locked,
  onFrame,
  onMove,
}: {
  track: PrevizTrack;
  keyFrame: number;
  frameCount: number;
  scaleFrames: number;
  locked: boolean;
  onFrame: (frame: number) => void;
  onMove: (to: number) => void;
}) {
  const [preview, setPreview] = useState<number | null>(null);
  const suppressClick = useRef(false);
  const drag = useRef<{
    pointer: number;
    left: number;
    width: number;
    startX: number;
    moved: boolean;
    to: number;
    commit: (to: number) => void;
  } | null>(null);
  const cancel = () => {
    drag.current = null;
    setPreview(null);
  };
  return (
    <StudioCanvasTooltip
      title={`${channelNames[track.channel]} · 第 ${preview ?? keyFrame} 帧${locked ? " · 对象已锁定" : " · 拖动调整时间，方向键微调，Shift 十帧"}`}
    >
      <span
        className="pv-keyframe-anchor"
        style={{
          left: `${frameProgress(preview ?? keyFrame, scaleFrames) * 100}%`,
        }}
      >
        <button
          className={`pv-keyframe${preview !== null ? " is-dragging" : ""}`}
          aria-label={`定位 ${track.channel} 第 ${keyFrame} 帧`}
          data-frame={keyFrame}
          aria-disabled={locked || undefined}
          onPointerDown={(e) => {
            if (e.button !== 0 || locked) return;
            const rect = e.currentTarget
              .closest(".pv-keyframes")!
              .getBoundingClientRect();
            suppressClick.current = false;
            drag.current = {
              pointer: e.pointerId,
              left: rect.left,
              width: rect.width,
              startX: e.clientX,
              moved: false,
              to: keyFrame,
              commit: onMove,
            };
            e.currentTarget.setPointerCapture(e.pointerId);
            onFrame(keyFrame);
          }}
          onPointerMove={(e) => {
            const d = drag.current;
            if (
              !d ||
              d.pointer !== e.pointerId ||
              (!d.moved && Math.abs(e.clientX - d.startX) < 3)
            )
              return;
            d.moved = true;
            d.to = Math.max(
              0,
              Math.min(
                frameCount - 1,
                Math.round(((e.clientX - d.left) / d.width) * (scaleFrames - 1)),
              ),
            );
            setPreview(d.to);
            onFrame(d.to);
          }}
          onPointerUp={(e) => {
            const d = drag.current;
            if (!d || d.pointer !== e.pointerId) return;
            suppressClick.current = d.to !== keyFrame;
            cancel();
            if (e.currentTarget.hasPointerCapture(e.pointerId))
              e.currentTarget.releasePointerCapture(e.pointerId);
            if (d.to !== keyFrame) d.commit(d.to);
          }}
          onPointerCancel={cancel}
          onLostPointerCapture={cancel}
          onClick={() => {
            if (suppressClick.current) suppressClick.current = false;
            else onFrame(keyFrame);
          }}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              cancel();
              return;
            }
            if (!locked && ["ArrowLeft", "ArrowRight"].includes(e.key)) {
              e.preventDefault();
              const target = Math.max(
                0,
                Math.min(
                  frameCount - 1,
                  keyFrame +
                    (e.key === "ArrowLeft" ? -1 : 1) * (e.shiftKey ? 10 : 1),
                ),
              );
              const lane = e.currentTarget.closest(".pv-keyframes");
              onMove(target);
              requestAnimationFrame(() =>
                lane
                  ?.querySelector<HTMLButtonElement>(
                    `button[data-frame="${target}"]`,
                  )
                  ?.focus(),
              );
            }
          }}
        />
      </span>
    </StudioCanvasTooltip>
  );
}

export function PrevizTimelineRows({
  scene,
  frameCount,
  selectedShot,
  onFrame,
  onShot,
  onMoveKey,
  onResizeShot,
  onInteractionStart,
}: {
  scene: PrevizScene;
  frameCount: number;
  selectedShot: string;
  onFrame: (frame: number) => void;
  onShot: (id: string) => void;
  onMoveKey: (trackId: string, from: number, to: number, source: PrevizScene) => void;
  onResizeShot: (shotId: string, edge: PrevizShotEdge, frame: number, source: PrevizScene) => void;
  onInteractionStart?: () => void;
}) {
  const scaleFrames = Math.max(frameCount, scene.timeline.frameCount);
  return <>
    <div className="director-timeline__row">
      <div className="director-timeline__label">拍摄镜头</div>
      <div className="director-timeline__lane pv-shot-lane">
        {scene.shots.map((shot, index) => (
          <PrevizShotClip key={shot.id} scene={scene} shot={shot} index={index} scaleFrames={scaleFrames} selected={selectedShot === shot.id}
            onSelect={() => { onShot(shot.id); onFrame(shot.startFrame); }} onResize={onResizeShot} onInteractionStart={onInteractionStart} />
        ))}
      </div>
    </div>
    {scene.timeline.tracks.map(track => {
      const node = scene.nodes.find(item => item.id === track.nodeId);
      let name = node?.name ?? "对象";
      if (node && track.objectId) {
        try {
          name = glbObjectsForNode(node, scene.assets.find(asset => asset.id === node.assetId)).find(part => part.id === track.objectId)?.name ?? name;
        } catch { /* The asset inspector reports invalid model data. */ }
      }
      return <div className="director-timeline__row" key={track.id}>
        <div className="director-timeline__label"><span className="director-timeline__lane-name">{name} · {channelNames[track.channel]}</span></div>
        <div className="director-timeline__lane pv-keyframes">
          {track.keys.map(key => <Keyframe key={key.frame} track={track} keyFrame={key.frame} frameCount={scene.timeline.frameCount}
            scaleFrames={scaleFrames} locked={!!node?.locked} onFrame={onFrame} onMove={to => onMoveKey(track.id, key.frame, to, scene)} />)}
        </div>
      </div>;
    })}
  </>;
}
