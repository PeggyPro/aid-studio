"use client";
import { RightOutlined } from "@ant-design/icons";
import { useMemo, type ReactNode } from "react";

import type {
  PrevizCommand,
  PrevizNode,
  PrevizScene,
  Vec3,
} from "@/types/previz";
import { copy, nodeAt, setKey, uid, PREVIZ_KINDS } from "@/utils/previz/core";
import { Choice, NumberField } from "./PrevizControls";
import { PrevizGlbObjectInspector } from "./PrevizGlbObjectInspector";
import { PrevizKeyframeAction } from './PrevizKeyframeAction';
import './previz-inspector.css';
import { cameraChoices, nativeCameraOwner } from "@/utils/previz/nativeCameras";
import { Matrix4, Euler, Quaternion, Vector3 } from "three";

function PropertyGroup({ title, children, open = false }: { title: string; children: ReactNode; open?: boolean }) {
  return <details className="pv-property-group" open={open}>
    <summary><span className="pv-property-group__title">{title}</span><span className="pv-property-group__toggle" aria-hidden="true"><RightOutlined /></span></summary>
    <div className="pv-property-group__body">{children}</div>
  </details>;
}

function worldPosition(scene: PrevizScene, id: string, frame: number) {
  const matrix = new Matrix4();
  const chain: PrevizNode[] = [];
  let current = scene.nodes.find((n) => n.id === id);
  while (current) {
    chain.unshift(current);
    current = scene.nodes.find((n) => n.id === current!.parentId);
  }
  for (const node of chain) {
    const t = nodeAt(scene, node, frame).localTransform;
    matrix.multiply(
      new Matrix4().compose(
        new Vector3(...t.position),
        new Quaternion().setFromEuler(new Euler(...t.rotation)),
        new Vector3(...t.scale),
      ),
    );
  }
  return new Vector3().setFromMatrixPosition(matrix);
}

export function PrevizInspector({
  scene,
  selectedId,
  selectedObjectId,
  onSelectObject,
  selectedShotId,
  frame,
  execute,
  onSelect,
  onShot,
  onCreateCamera,
  onEnterCamera,
  getCameraSnapshot,
  followCameraControl,
}: {
  scene: PrevizScene;
  selectedId: string;
  selectedObjectId?: string;
  onSelectObject?: (id?: string) => void;
  selectedShotId: string;
  frame: number;
  execute: (commands: PrevizCommand[]) => void;
  onSelect: (id: string) => void;
  onShot: (id: string) => void;
  onCreateCamera: (shotId: string) => void;
  onEnterCamera: (cameraId: string) => void;
  followCameraControl?: ReactNode;
  getCameraSnapshot: (
    id: string,
  ) => Pick<PrevizNode, "localTransform" | "camera"> | undefined;
}) {
  const node = scene.nodes.find((n) => n.id === selectedId),
    shot = scene.shots.find((s) => s.id === selectedShotId);
  const followSubjects = useMemo(() => {
    const byId = new Map(scene.nodes.map((n) => [n.id, n]));
    return scene.nodes.filter((n) => {
      if (["CAMERA", "LIGHT", "PLANE"].includes(n.kind)) return false;
      let ancestor: PrevizNode | undefined = n;
      while (ancestor) {
        if (ancestor.id === selectedId) return false;
        ancestor = ancestor.parentId ? byId.get(ancestor.parentId) : undefined;
      }
      return true;
    });
  }, [scene.nodes, selectedId]);
  const update = (patch: Partial<Omit<PrevizNode, "id">>) => {
    if (!node) return;
    const commands: PrevizCommand[] = [
      { type: "node.update", id: node.id, patch },
    ];
    if (
      node.camera &&
      patch.localTransform &&
      scene.timeline.tracks.some(
        (t) => t.nodeId === node.id && t.channel === "position",
      )
    )
      commands.push(
        setKey(
          scene,
          node.id,
          "position",
          frame,
          patch.localTransform.position,
        ),
      );
    execute(commands);
  };
  const updateShot = (patch: Partial<typeof shot>) => {
    if (!shot) return;
    execute([
      {
        type: "timeline.replace",
        timeline: scene.timeline,
        shots: scene.shots.map((s) =>
          s.id === shot.id ? { ...s, ...patch } : s,
        ),
      },
    ]);
  };
  const changeBoundary = (value: number) => {
    if (!shot) return;
    const index = scene.shots.indexOf(shot),
      next = scene.shots[index + 1];
    if (!next) return;
    const end = Math.round(value * 30);
    if (end <= shot.startFrame || end >= next.endFrameExclusive) return;
    const shots = copy(scene.shots);
    shots[index].endFrameExclusive = end;
    shots[index + 1].startFrame = end;
    execute([{ type: "timeline.replace", timeline: scene.timeline, shots }]);
  };
  const vector = (
    channel: "position" | "rotation" | "scale" | "target",
    label: string,
  ) => {
    if (!node) return null;
    const evaluated = nodeAt(scene, node, frame),
      value =
        channel === "target"
          ? evaluated.camera!.target
          : evaluated.localTransform[channel];
    const animated = scene.timeline.tracks.some(
      (t) => t.nodeId === node.id && !t.objectId && t.channel === channel,
    );
    if (node.camera?.followPosition && channel === "position") return null;
    if (node.camera && (channel === "rotation" || channel === "scale"))
      return null;
    return (
      <div className="pv-property">
        <div className="pv-property-label">
          <span>
            {label}
            {animated ? " · 当前帧" : ""}
          </span>
          <PrevizKeyframeAction label={label}
            disabled={node.locked}
            onClick={() =>
              execute([setKey(scene, node.id, channel, frame, value)])
            }
          />
        </div>
        <div className="pv-vector">
          {(["X", "Y", "Z"] as const).map((axis, i) => (
            <NumberField
              key={axis}
              label={`${label} ${axis}`}
              value={
                channel === "rotation" ? (value[i] * 180) / Math.PI : value[i]
              }
              min={channel === "scale" ? 0.01 : -1000}
              max={channel === "scale" ? 100 : 1000}
              step={channel === "rotation" ? 1 : 0.1}
              onChange={(v) => {
                const next = [...value] as Vec3;
                next[i] = channel === "rotation" ? (v * Math.PI) / 180 : v;
                if (animated)
                  execute([setKey(scene, node.id, channel, frame, next)]);
                else if (channel === "target")
                  update({ camera: { ...node.camera!, target: next } });
                else
                  update({
                    localTransform: { ...node.localTransform, [channel]: next },
                  });
              }}
            />
          ))}
        </div>
      </div>
    );
  };
  return (
    <div className="pv-inspector">
      {node ? (
        <>
          <div className="pv-section-heading">
            <span>对象属性</span>
            <small>{PREVIZ_KINDS[node.kind]}</small>
          </div>
          <label className="pv-field">
            {selectedObjectId ? "所在模型实例名称" : "名称"}
            <input
              aria-label="对象名称"
              maxLength={120}
              value={node.name}
              disabled={node.locked || Boolean(selectedObjectId)}
              onChange={(e) => update({ name: e.target.value })}
            />
          </label>
          {followCameraControl}
          <fieldset disabled={node.locked}>
            <legend className="pv-sr-only">对象编辑</legend>
            {node.kind === "GLB" ? <PrevizGlbObjectInspector key={node.id} node={node} asset={scene.assets.find(asset => asset.id === node.assetId)} update={update} selectedId={selectedObjectId} onSelect={onSelectObject} scene={scene} frame={frame} execute={execute} /> : null}
            <PropertyGroup key={`transform:${node.id}:${selectedObjectId || ""}`} title={selectedObjectId ? "整个模型变换" : "变换"} open={!selectedObjectId}>
            {vector("position", "位置")}
            {node.kind !== "CAMERA" ? vector("rotation", "旋转") : null}
            {node.kind !== "CAMERA" && node.kind !== "LIGHT"
              ? vector("scale", "缩放")
              : null}
            </PropertyGroup>
            <PropertyGroup key={`appearance:${node.id}`} title={node.kind === "ACTOR" ? "外观与动作" : "外观"}>
            <label className="pv-color-field">
              白模颜色
              <input
                type="color"
                aria-label="白模颜色"
                value={node.color}
                onChange={(e) => update({ color: e.target.value })}
              />
            </label>
            {node.kind === "ACTOR" ? (
              <>
                <div className="pv-property">

                  <Choice showLabel
                    label="人物动作"
                    value={node.action ?? "idle"}
                    items={[
                      { key: "idle", label: "站立" },
                      { key: "walk", label: "行走" },
                      { key: "run", label: "跑步" },
                      { key: "wave", label: "挥手" },
                      { key: "sit", label: "坐姿" },
                    ]}
                    onChange={(action) =>
                      update({ action: action as PrevizNode["action"] })
                    }
                  />
                </div>
                <button
                  className="pv-secondary pv-wide"
                  onClick={() => {
                    const p = [...node.localTransform.position] as Vec3,
                      end = [...p] as Vec3;
                    end[0] += Math.sin(node.localTransform.rotation[1]) * 3;
                    end[2] += Math.cos(node.localTransform.rotation[1]) * 3;
                    const timeline = copy(scene.timeline);
                    timeline.tracks = timeline.tracks.filter(
                      (t) =>
                        !(t.nodeId === node.id && t.channel === "position"),
                    );
                    timeline.tracks.push({
                      id: uid(),
                      nodeId: node.id,
                      channel: "position",
                      interpolation: "linear",
                      keys: [
                        { frame: 0, value: p },
                        { frame: scene.timeline.frameCount - 1, value: end },
                      ],
                    });
                    execute([
                      {
                        type: "node.update",
                        id: node.id,
                        patch: { action: "walk" },
                      },
                      {
                        type: "timeline.replace",
                        timeline,
                        shots: scene.shots,
                      },
                    ]);
                  }}
                >
                  建立向前 3 米走位
                </button>
                <Choice showLabel
                  label="人物朝向"
                  value={node.motionFacing ?? "path"}
                  items={[
                    { key: "path", label: "沿走位方向" },
                    { key: "manual", label: "手动旋转" },
                  ]}
                  onChange={(value) =>
                    update({ motionFacing: value as "path" | "manual" })
                  }
                />
                <p className="pv-help">
                  行走和跑步随位置轨道运动，抵达终点后停止摆臂。坐姿需要手动匹配座椅位置。
                </p>
              </>
            ) : null}
            </PropertyGroup>
            {node.camera ? (
              <PropertyGroup key={`camera:${node.id}`} title="镜头与跟随" open>
                {node.camera.projection === "orthographic" ? <NumberField label="正交取景高度（米）" value={node.camera.orthoHeight!} min={0.01} max={1000000} step={0.1}
                  onChange={orthoHeight=>update({camera:{...node.camera!,orthoHeight}})} /> : <NumberField
                  label="视野 FOV（度）"
                  value={node.camera.fov}
                  min={1}
                  max={179}
                  step={1}
                  onChange={(fov) =>
                    update({ camera: { ...node.camera!, fov } })
                  }
                />}
                <NumberField label="画面滚转（度）" value={(node.camera.roll||0)*180/Math.PI} min={-180} max={180} step={1}
                  onChange={roll=>update({camera:{...node.camera!,roll:roll*Math.PI/180}})} />
                <Choice showLabel
                  label="镜头瞄准方式"
                  value={node.camera.followNodeId ?? "fixed"}
                  items={[
                    { key: "fixed", label: "固定瞄准点" },
                    ...followSubjects.map((n) => ({
                      key: n.id,
                      label: `跟随 ${n.name}`,
                    })),
                  ]}
                  onChange={(id) => {
                    const snapshot = getCameraSnapshot(node.id);
                    update({
                      localTransform:
                        snapshot?.localTransform ?? node.localTransform,
                      camera: {
                        ...node.camera!,
                        target: snapshot?.camera?.target ?? node.camera!.target,
                        followNodeId: id === "fixed" ? undefined : id,
                        followPosition: false,
                        followOffset: undefined,
                      },
                    });
                  }}
                />
                {node.camera.followNodeId ? (
                  <>
                    <label className="pv-checkbox">
                      <input
                        type="checkbox"
                        aria-label="机位随主体移动"
                        checked={Boolean(node.camera.followPosition)}
                        onChange={(e) => {
                          const offset = worldPosition(
                            scene,
                            node.id,
                            frame,
                          ).sub(
                            worldPosition(
                              scene,
                              node.camera!.followNodeId!,
                              frame,
                            ),
                          );
                          const snapshot = !e.target.checked
                            ? getCameraSnapshot(node.id)
                            : undefined;
                          update({
                            ...(snapshot
                              ? { localTransform: snapshot.localTransform }
                              : {}),
                            camera: {
                              ...node.camera!,
                              followPosition: e.target.checked,
                              followOffset: e.target.checked
                                ? (offset.toArray() as Vec3)
                                : undefined,
                            },
                          });
                        }}
                      />{" "}
                      机位随主体移动
                    </label>
                    {node.camera.followPosition && node.camera.followOffset ? (
                      <div className="pv-property">
                        <div className="pv-property-label">
                          跟随偏移（世界米）
                        </div>
                        <div className="pv-vector">
                          {(["X", "Y", "Z"] as const).map((axis, i) => (
                            <NumberField
                              key={axis}
                              label={`跟随偏移 ${axis}`}
                              value={node.camera!.followOffset![i]}
                              min={-1000}
                              max={1000}
                              step={0.1}
                              onChange={(v) => {
                                const offset = [
                                  ...node.camera!.followOffset!,
                                ] as Vec3;
                                offset[i] = v;
                                update({
                                  camera: {
                                    ...node.camera!,
                                    followOffset: offset,
                                  },
                                });
                              }}
                            />
                          ))}
                        </div>
                      </div>
                    ) : null}
                    <p className="pv-help">
                      机位朝向由瞄准点或主体中心决定。启用移动后请调整跟随偏移；关闭时保留当前机位位置。预演与导出共用此机位。
                    </p>
                  </>
                ) : (
                  vector("target", "瞄准点")
                )}
                <button
                  className="pv-secondary pv-wide"
                  onClick={() => onEnterCamera(node.id)}
                >
                  进入此机位预览
                </button>
              </PropertyGroup>
            ) : null}
            {node.light ? (
              <>
                <Choice showLabel
                  label="灯光类型"
                  value={node.light.kind}
                  items={[
                    { key: "directional", label: "方向光" },
                    { key: "point", label: "点光源" },
                    { key: "spot", label: "聚光灯" },
                  ]}
                  onChange={(kind) =>
                    update({
                      light: {
                        ...node.light!,
                        kind: kind as "directional" | "point" | "spot",
                      },
                    })
                  }
                />
                <NumberField
                  label="灯光强度"
                  value={node.light.intensity}
                  min={0}
                  max={20}
                  onChange={(intensity) =>
                    update({ light: { ...node.light!, intensity } })
                  }
                />
              </>
            ) : null}
            <PropertyGroup key={`hierarchy:${node.id}`} title="层级关系">
            <div className="pv-field">
              <Choice showLabel
                label="对象父级"
                value={node.parentId ?? "root"}
                items={[
                  { key: "root", label: "场景根级" },
                  ...scene.nodes
                    .filter(
                      (n) =>
                        n.id !== node.id &&
                        !["CAMERA", "LIGHT"].includes(n.kind),
                    )
                    .map((n) => ({ key: n.id, label: n.name })),
                ]}
                onChange={(parentId) =>
                  update({ parentId: parentId === "root" ? null : parentId })
                }
              />
            </div>
            </PropertyGroup>
          </fieldset>
          {node.locked ? (
            <p className="pv-help">对象已锁定，请在对象列表解锁后编辑。</p>
          ) : null}
          {scene.timeline.tracks
            .filter((t) => t.nodeId === node.id && t.objectId === selectedObjectId)
            .map((t) => (
              <div className="pv-track-property" key={t.id}>
                <div className="pv-property-label">
                  <span>
                    {
                      {
                        position: "位置",
                        rotation: "旋转",
                        scale: "缩放",
                        target: "瞄准点",
                      }[t.channel]
                    }
                    轨道 · {t.keys.length} 帧
                  </span>
                  <button
                    disabled={node.locked}
                    onClick={() =>
                      execute([
                        {
                          type: "timeline.replace",
                          timeline: {
                            ...scene.timeline,
                            tracks: scene.timeline.tracks.filter(
                              (track) => track.id !== t.id,
                            ),
                          },
                          shots: scene.shots,
                        },
                      ])
                    }
                  >
                    移除轨道
                  </button>
                </div>
                <Choice showLabel
                  label="关键帧插值"
                  value={t.interpolation}
                  items={[
                    { key: "linear", label: "线性" },
                    { key: "step", label: "阶跃" },
                  ]}
                  disabled={node.locked}
                  onChange={(interpolation) =>
                    execute([
                      {
                        type: "timeline.replace",
                        timeline: {
                          ...scene.timeline,
                          tracks: scene.timeline.tracks.map((track) =>
                            track.id === t.id
                              ? {
                                  ...track,
                                  interpolation: interpolation as
                                    | "linear"
                                    | "step",
                                }
                              : track,
                          ),
                        },
                        shots: scene.shots,
                      },
                    ])
                  }
                />
                <div className="pv-key-list">
                  {t.keys.map((k) => (
                    <button
                      key={k.frame}
                      disabled={node.locked}
                      aria-label={`删除 ${t.channel} 第 ${k.frame} 帧关键帧`}
                      onClick={() => {
                        const tracks = scene.timeline.tracks
                          .map((track) =>
                            track.id === t.id
                              ? {
                                  ...track,
                                  keys: track.keys.filter(
                                    (key) => key.frame !== k.frame,
                                  ),
                                }
                              : track,
                          )
                          .filter((track) => track.keys.length);
                        execute([
                          {
                            type: "timeline.replace",
                            timeline: { ...scene.timeline, tracks },
                            shots: scene.shots,
                          },
                        ]);
                      }}
                    >
                      {k.frame}f ×
                    </button>
                  ))}
                </div>
              </div>
            ))}
        </>
      ) : (
        <div className="pv-property-empty">
          <span>选择场景中的对象</span>
          <p>调整位置、动作和机位，或在智能体中描述你的想法。</p>
        </div>
      )}
      {!node && shot ? (
        <PropertyGroup key={`shot:${shot.id}:${Boolean(node)}`} title="当前镜头 · 硬切" open={!node}>
          <label className="pv-field">
            镜头名称
            <input
              aria-label="镜头名称"
              value={shot.name}
              maxLength={120}
              onChange={(e) => updateShot({ name: e.target.value })}
            />
          </label>
          <Choice showLabel
            label="镜头拍摄机位"
            value={shot.cameraId}
            items={cameraChoices(scene)
              .map((n) => ({ key: n.id, label: n.name }))}
            onChange={(cameraId) => updateShot({ cameraId })}
          />
          <button
            className="pv-text-button"
            onClick={() => nativeCameraOwner(shot.cameraId) ? onCreateCamera(shot.id) : onSelect(shot.cameraId)}
          >
            {nativeCameraOwner(shot.cameraId) ? "复制为可编辑机位" : "编辑此机位"}
          </button>
          <p className="pv-help">
            {(shot.startFrame / 30).toFixed(2)}s —{" "}
            {(shot.endFrameExclusive / 30).toFixed(2)}s ·{" "}
            {shot.endFrameExclusive - shot.startFrame} 帧
          </p>
          {scene.shots.indexOf(shot) < scene.shots.length - 1 ? (
            <NumberField
              label="切镜时间（秒）"
              value={shot.endFrameExclusive / 30}
              min={(shot.startFrame + 1) / 30}
              max={
                (scene.shots[scene.shots.indexOf(shot) + 1].endFrameExclusive -
                  1) /
                30
              }
              step={1 / 30}
              onChange={changeBoundary}
            />
          ) : null}
          <div className="pv-row">
            <button
              className="pv-secondary"
              disabled={shot.endFrameExclusive - shot.startFrame < 2}
              onClick={() => {
                const middle = Math.floor(
                    (shot.startFrame + shot.endFrameExclusive) / 2,
                  ),
                  next = {
                    ...shot,
                    id: uid(),
                    name: `${shot.name} · 后半`,
                    startFrame: middle,
                  };
                const shots = scene.shots.flatMap((s) =>
                  s.id === shot.id
                    ? [{ ...s, endFrameExclusive: middle }, next]
                    : [s],
                );
                execute([
                  { type: "timeline.replace", timeline: scene.timeline, shots },
                ]);
                onShot(next.id);
              }}
            >
              拆分镜头
            </button>
            <button
              className="pv-secondary"
              disabled={scene.shots.length === 1}
              onClick={() => {
                const shots = copy(scene.shots),
                  index = shots.findIndex((s) => s.id === shot.id);
                if (index > 0)
                  shots[index - 1].endFrameExclusive = shot.endFrameExclusive;
                else shots[1].startFrame = 0;
                shots.splice(index, 1);
                execute([
                  { type: "timeline.replace", timeline: scene.timeline, shots },
                ]);
                onShot(shots[Math.max(0, index - 1)].id);
              }}
            >
              合并移除
            </button>
          </div>
          <div className="pv-row">
            <button
              className="pv-secondary"
              disabled={scene.shots.indexOf(shot) === 0}
              onClick={() => {
                const shots = copy(scene.shots),
                  i = shots.findIndex((s) => s.id === shot.id),
                  a = shots[i - 1],
                  b = shots[i],
                  durationA = a.endFrameExclusive - a.startFrame,
                  durationB = b.endFrameExclusive - b.startFrame,
                  start = a.startFrame;
                shots[i - 1] = {
                  ...b,
                  startFrame: start,
                  endFrameExclusive: start + durationB,
                };
                shots[i] = {
                  ...a,
                  startFrame: start + durationB,
                  endFrameExclusive: start + durationB + durationA,
                };
                execute([
                  { type: "timeline.replace", timeline: scene.timeline, shots },
                ]);
              }}
            >
              镜头前移
            </button>
            <button
              className="pv-secondary"
              onClick={() => onCreateCamera(shot.id)}
            >
              新建此镜头机位
            </button>
          </div>
        </PropertyGroup>
      ) : null}
    </div>
  );
}
