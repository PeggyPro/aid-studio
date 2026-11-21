import * as THREE from "three";
import { aspectNumber, validateScene } from "./core";
import { buildRuntime } from "./runtime";
import type { PrevizProject, PrevizScene } from "@/types/previz";
import { applyThreePrevizProject, serializeThreePreviz } from "@/utils/studio/threePrevizDocument";
import type { IClip } from "@webav/av-cliper";

export function downloadPreviz(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob),
    anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename.replace(/[<>:"/\\|?*]/g, "_");
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
export function projectPackage(project: PrevizProject): Blob {
  validateScene(project.scene);
  if (project.scene.directorLayer) return new Blob([serializeThreePreviz(applyThreePrevizProject(project.scene.directorLayer.scene, project))], {type:"application/json"});
  return new Blob(
    [
      JSON.stringify(
        {
          format: "AID-3D-BOX",
          packageVersion: 1,
          project: { ...project, versions: [], thumbnail: undefined },
        },
        null,
        2,
      ),
    ],
    { type: "application/json" },
  );
}
export async function readPackage(
  file: File,
): Promise<
  Pick<PrevizProject, "title" | "scene" | "messages" | "draft" | "annotations">
> {
  if (file.size > 80 * 1024 * 1024) throw new Error("工程包超过 80MB");
  const value = JSON.parse(await file.text());
  if (value.format !== "AID-3D-BOX" || value.packageVersion !== 1)
    throw new Error("请选择 AID 3D-HUB 工程包；旧导演台格式不直接混用");
  if(value.project?.scene?.directorLayer)throw new Error("统一3D预演增强数据必须使用完整工程格式导入");
  validateScene(value.project?.scene);
  const p = value.project as PrevizProject;
  if (
    typeof p.title !== "string" ||
    p.title.length > 120 ||
    typeof p.draft !== "string" ||
    p.draft.length > 4000 ||
    !Array.isArray(p.messages) ||
    p.messages.length > 200 ||
    p.messages.some(
      (m) =>
        !m ||
        typeof m.id !== "string" ||
        !["user", "assistant"].includes(m.role) ||
        typeof m.text !== "string" ||
        m.text.length > 12000 ||
        typeof m.createdAt !== "string" ||
        (m.references &&
          (!Array.isArray(m.references) ||
            m.references.some((r) => typeof r !== "string"))),
    ) ||
    !Array.isArray(p.annotations) ||
    p.annotations.length > 5 ||
    p.annotations.some(
      (a) =>
        !a ||
        typeof a.id !== "string" ||
        !Number.isInteger(a.frame) ||
        a.frame < 0 ||
        a.frame > 1799 ||
        typeof a.note !== "string" ||
        a.note.length > 4000 ||
        !Array.isArray(a.nodeIds) ||
        a.nodeIds.some((id) => typeof id !== "string") ||
        typeof a.image !== "string" ||
        a.image.length > 10_000_000 ||
        !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(a.image) ||
        !Array.isArray(a.points) ||
        a.points.length > 10000 ||
        a.points.some(
          (point) =>
            !Array.isArray(point) ||
            point.length !== 2 ||
            point.some((v) => !Number.isFinite(v) || Math.abs(v) > 10),
        ),
    )
  )
    throw new Error("工程对话或标注格式无效");
  return {
    title: p.title,
    scene: p.scene,
    messages: p.messages,
    draft: p.draft,
    annotations: p.annotations,
  };
}
export function fileDataUrl(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error("文件读取失败"));
    reader.readAsDataURL(file);
  });
}
export async function exportScreenshot(
  document: PrevizScene,
  frame: number,
  cameraId?: string,
): Promise<Blob> {
  const runtime = await buildRuntime(document);
  let renderer: THREE.WebGLRenderer | undefined;
  try {
    renderer = new THREE.WebGLRenderer({
      antialias: true,
      preserveDrawingBuffer: true,
    });
    renderer.setSize(
      1280,
      Math.round(1280 / aspectNumber(document.aspectRatio)),
    );
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = document.environment.exposure;
    renderer.shadowMap.enabled = true;
    const active = runtime.frame(frame);
    renderer.render(
      runtime.scene,
      runtime.cameras.get(cameraId ?? "") ?? active,
    );
    return await new Promise<Blob>((resolve, reject) =>
      renderer!.domElement.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error("截图为空"))),
        "image/png",
      ),
    );
  } finally {
    runtime.dispose();
    renderer?.dispose();
    renderer?.forceContextLoss();
  }
}
/** Local exact-frame MP4 encoding. Formal server rendering is a separate backend capability. */
export async function exportVideo(
  document: PrevizScene,
  signal: AbortSignal,
  onProgress: (progress: number) => void,
): Promise<Blob> {
  validateScene(document);
  if (
    typeof VideoEncoder === "undefined" ||
    typeof OffscreenCanvas === "undefined"
  )
    throw new Error(
      "此浏览器不支持本地 MP4 编码，请使用支持 WebCodecs 的 Chrome／Edge 或导出工程包",
    );
  const aspect = aspectNumber(document.aspectRatio),
    width = aspect >= 1 ? Math.round((720 * aspect) / 2) * 2 : 720,
    height = aspect >= 1 ? 720 : Math.round(720 / aspect / 2) * 2;
  const supported = await VideoEncoder.isConfigSupported({
    codec: "avc1.42E032",
    width,
    height,
    framerate: 30,
    bitrate: 4_000_000,
  });
  if (!supported.supported)
    throw new Error("当前设备不支持 H.264 编码，请更换设备或导出工程包");
  signal.throwIfAborted();
  const runtime = await buildRuntime(document),
    duration = Math.round((document.timeline.frameCount / 30) * 1e6);
  let renderer: THREE.WebGLRenderer | undefined,
    compositor:
      | InstanceType<typeof import("@webav/av-cliper").Combinator>
      | undefined,
    sprite:
      | InstanceType<typeof import("@webav/av-cliper").OffscreenSprite>
      | undefined;
  try {
    const { Combinator, OffscreenSprite } = await import("@webav/av-cliper");
    signal.throwIfAborted();
    renderer = new THREE.WebGLRenderer({
      antialias: true,
      preserveDrawingBuffer: true,
    });
    renderer.setSize(width, height);
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = document.environment.exposure;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    const clip: IClip = {
      meta: { width, height, duration },
      ready: Promise.resolve({ width, height, duration }),
      tick: async (time) => {
        signal.throwIfAborted();
        if (time >= duration) return { state: "done" as const };
        // WebAV advances by rounded microseconds (33333 at 30fps). The time
        // just before duration can already be frameCount; stop by canonical
        // frame index rather than clamping it and encoding the last frame twice.
        const frame = Math.round((time / 1e6) * 30);
        if (frame >= document.timeline.frameCount)
          return { state: "done" as const };
        renderer!.render(runtime.scene, runtime.frame(frame));
        onProgress(frame / document.timeline.frameCount);
        return {
          state: "success" as const,
          video: await createImageBitmap(renderer!.domElement),
        };
      },
      clone: async () => clip,
      destroy: () => {},
    };
    sprite = new OffscreenSprite(clip);
    await sprite.ready;
    sprite.time = { offset: 0, duration };
    compositor = new Combinator({ width, height, fps: 30, audio: false });
    await compositor.addSprite(sprite, { main: true });
    const reader = compositor.output({ maxTime: duration }).getReader(),
      chunks: Uint8Array[] = [];
    const abort = () => {
      void reader.cancel().catch(() => undefined);
      compositor?.destroy();
    };
    signal.addEventListener("abort", abort, { once: true });
    try {
      while (true) {
        signal.throwIfAborted();
        const { done, value } = await reader.read();
        if (done) break;
        if (value) chunks.push(value);
      }
    } finally {
      signal.removeEventListener("abort", abort);
      reader.releaseLock();
    }
    signal.throwIfAborted();
    const blob = new Blob(chunks as BlobPart[], { type: "video/mp4" });
    if (!blob.size) throw new Error("编码结果为空，请重试");
    onProgress(1);
    return blob;
  } finally {
    compositor?.destroy();
    sprite?.destroy();
    runtime.dispose();
    renderer?.dispose();
    renderer?.forceContextLoss();
  }
}
