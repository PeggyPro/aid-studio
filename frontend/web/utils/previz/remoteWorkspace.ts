import { mainAssistantTransport } from "@/utils/business/assistant";
import { assistantDigest, coordinateAssistantRequest } from "@/utils/assistant/assistantRequestCoordinator";
import { request } from "@/utils/api";
import type { PrevizNode, PrevizAsset } from "@/types/previz";
import type {ModelBillingDetailVO} from "@/types/business-api";
import type { PrevizBuildOperation } from "./buildAdoption";

export interface PrevizEditorSnapshot {
  sceneDigest: string;
  frame: number;
  targetNodeId: string | null;
  selectedObjects?: {nodeId:string;objectId:string}[];
  selectedNodeIds?:string[];
  documentVersion?: string;
  nodes: (Pick<PrevizNode, "id" | "name" | "kind" | "localTransform" | "visible" | "locked" | "assetOverrides" | "assetPlayback" | "parentId" | "color" | "assetObjectIds" | "assetPivotId"> & { buildOrigin?: PrevizAsset["buildOrigin"] })[];
}

export interface PrevizProjectMetadata {
  accessRole?: 'OWNER'|'EDITOR'|'VIEWER';
  id: string;
  ownerUserId: string | number;
  title: string;
  revision: number;
  preferredEngine?: "threejs" | "blender" | null;
  modelCode?: string | null;
}

export interface PrevizTextModelOptions {
  agentVersionId: string;
  modelCode: string;
  bindingRevision?:string;
  models: { code: string; name: string; billing?:ModelBillingDetailVO }[];
}
export interface PrevizBuildArtifact {
  operation?: PrevizBuildOperation;
  editorVersion?: string;
    runtimeRunId?: string;
    entry?: string;
  runtimeStepId: string;
  jobId: string;
  projectId: string;
  sceneId: string;
  sourceVersionId: string;
  engine: "threejs" | "blender";
  contentBase64: string;
  mimeType: "model/gltf-binary";
  artifact: { name: string; sha256: string; glbBytes: number };
  warnings: { code: string; objectId: string; type: string }[];
  previewSettings?: { exposure: number; lightingUnits: "PHOTOMETRIC"; sourceExposureEV: number };
  nativeArtifacts?: { name: string; mimeType: string; sizeBytes: number; sha256: string }[];
}
export interface PrevizSceneEditReceipt {state:'APPLIED';projectId:string;runtimeStepId:string;documentVersion:string;baseDocumentVersion:string;editorVersion:string;rebuilt:false;}

/** Only authenticated business-service endpoints; no canvas gateway fallback. */
export function createPrevizWorkspaceApi() {
  const transport = mainAssistantTransport;
  const identity = transport.identityKey();
  async function post<T>(path: string, body: unknown): Promise<T> {
    if (identity !== transport.identityKey()) throw new Error("账号已变化，请重新打开工程");
    const response = await transport.post<T>(`/api/user/previz/${path}`, body);
    if (identity !== transport.identityKey()) throw new Error("账号已变化，请重新打开工程");
    if ((response.code !== 0 && response.code !== 200) || response.data == null) throw response;
    return response.data;
  }
  function read<T>(path: string, body: unknown, force = false, cacheMs = 0): Promise<T> {
    return coordinateAssistantRequest<T>({
      key: `previz:${identity}:${path}:${assistantDigest(body)}`,
      body, force, cacheMs, execute: () => post<T>(path, body),
    });
  }
  function write<T>(path: string, body: unknown): Promise<T> {
    return coordinateAssistantRequest<T>({
      key: `previz:${identity}:${path}:${assistantDigest(body)}`,
      body, execute: () => post<T>(path, body),
    });
  }
  return {
    projectPage: (before?:string) => read<{items:{id:string;title:string;role:'OWNER'|'EDITOR'|'VIEWER';revision:string;documentVersion?:string}[];nextCursor?:string}>("project/page",{before,limit:20},true),
    projectTrashPage: (before?:string) => read<{items:{id:string;title:string;role:'OWNER';revision:string;documentVersion?:string}[];nextCursor?:string}>("project/trash/page",{before,limit:20},true),
    projectLifecycle: (projectId:string,operation:'DELETE'|'RESTORE',expectedRevision:string,clientRequestId:string) => write<{projectId:string;operation:string;appliedRevision:string;currentRevision:string;deleted:boolean;replay:boolean}>("project/lifecycle",{projectId,operation,expectedRevision,clientRequestId}),
    members: (projectId:string) => read<{ownerUserId:string;revision:string;members:{userId:string;role:'EDITOR'|'VIEWER'}[]}>("project/members",{projectId},true),
    changeMember: (projectId:string,userId:string,role:'EDITOR'|'VIEWER'|'REMOVE',expectedRevision:string) => write<{ownerUserId:string;revision:string;members:{userId:string;role:'EDITOR'|'VIEWER'}[]}>("project/member/change",{projectId,userId,role,expectedRevision}),
    documentCurrent: async (projectId: string) => {const result=await read<{documentVersion?:string;currentSourceVersion?:string;workspaceRevision:string}>("document/current",{projectId},true);return {...result,documentVersion:result.documentVersion??null,currentSourceVersion:result.currentSourceVersion??null};},
    documentSave: (projectId:string,expectedVersion:string|null,expectedSourceVersion:string|null,document:unknown) => write<{documentVersion:string}>("document/save",{projectId,expectedVersion,expectedSourceVersion,document}),
    documentRead: async (projectId:string,version?:string) => {const result=await read<{documentVersion:string;currentVersion:string;sourceVersion?:string;documentJson:string}>("document/read",{projectId,version},true);return {...result,sourceVersion:result.sourceVersion??null,document:JSON.parse(result.documentJson) as unknown};},
    documentHistory: (projectId:string,before?:string) => read<{items:{documentVersion:string;workspaceRevision:string;title:string;savedAt:string;operation:string}[];nextCursor:string|null}>("document/history",{projectId,before,limit:20},true),
    documentRestore: (projectId:string,expectedVersion:string|null,expectedSourceVersion:string|null,version:string) => write<{documentVersion:string;currentSourceVersion:string|null}>("document/restore",{projectId,expectedVersion,expectedSourceVersion,version}),
    assetBegin: (projectId:string,sha256:string,sizeBytes:number,mimeType:string) => write<{uploadId:string;complete:boolean;chunkBytes:number;chunkCount:number;uploadedChunks:number[]}>("asset/upload/begin",{projectId,operationId:`asset-${sha256}`,sha256,sizeBytes,mimeType}),
    assetChunk: (projectId:string,uploadId:string,index:number,contentBase64:string,chunkSha256:string) => write<unknown>("asset/upload/chunk",{projectId,uploadId,index,contentBase64,chunkSha256}),
    assetComplete: (projectId:string,uploadId:string) => write<unknown>("asset/upload/complete",{projectId,uploadId}),
    assetRead: (projectId:string,sha256:string,offset:number,limit:number) => read<{contentBase64:string;chunkSha256:string}>("asset/read-chunk",{projectId,sha256,offset,limit},true),
    create: (title: string, clientRequestId: string) => {
      if (!clientRequestId) return Promise.reject(new Error("缺少操作标识"));
      return write<PrevizProjectMetadata>("project/create", { title, clientRequestId });
    },
    detail: (projectId: string, force = false) => read<PrevizProjectMetadata>("project/detail", { projectId }, force),
    sceneAssistantAvailability: () => read<{available: boolean}>("scene-assistant/availability", {}, false, 1000),
    modelCatalog: (force=false) => read<PrevizTextModelOptions>("model/catalog", {}, force, 30000),
    models: (projectId: string, force = false) => read<PrevizTextModelOptions>("model/options", { projectId }, force),
    artifact: (projectId: string, stepId: string) => read<PrevizBuildArtifact>("build/artifact", { projectId, stepId }),
    sceneEditReceipt:(projectId:string,stepId:string)=>read<PrevizSceneEditReceipt>('scene-edit/receipt',{projectId,stepId}),
    buildManifest: (projectId: string, stepId: string) => read<Omit<PrevizBuildArtifact, "contentBase64" | "mimeType">>("build/manifest", { projectId, stepId }, false, 1000),
    nativeFile: (projectId: string, stepId: string) => coordinateAssistantRequest<Blob>({
      key: `previz:${identity}:native-file:${projectId}:${stepId}`, body: { projectId, stepId },
      execute: async () => {
        if (identity !== transport.identityKey()) throw new Error("账号已变化");
        const blob = await request.post<Blob>("/api/user/previz/build/native-file", { projectId, stepId }, { responseType: "blob" });
        if (identity !== transport.identityKey()) throw new Error("账号已变化");
        return blob;
      },
    }),
    saveReferenceImage: (projectId: string, contentBase64: string) => write<{ objectId: string; contentHash: string; width: number; height: number; mimeType: string }>("reference-image/save", { projectId, contentBase64 }),
    currentEditor: (projectId: string) => read<{ editorVersion: string | null }>("editor/current", { projectId }, true),
    previewResult: (projectId: string, stepId: string) => read<{projectId:string;imageId:string;sha256:string;width:number;height:number;actualTime:number;cameraId?:string;addedPreviewLight:boolean}>("preview/result",{projectId,stepId}),
    referenceImage: (projectId:string, version:string) => read<{contentBase64:string;mimeType:string;sha256:string}>("reference-image/read",{projectId,version}),
    readEditor: (projectId: string, version: string) => read<PrevizEditorSnapshot>("editor/read", { projectId, version }),
    saveEditor: (projectId: string, expectedVersion: string | null, state: PrevizEditorSnapshot) => write<{ editorVersion: string }>("editor/save", { projectId, expectedVersion, state }),
    currentSource: (projectId: string, force = false) => read<{ sourceVersionId: string | null }>("source/current", { projectId }, force),
    readSource: (projectId: string, version: string) => read<{ sourceVersionId: string; files: Record<string, string> }>("source/read", { projectId, version }),
    saveSource: (projectId: string, expectedVersion: string | null, files: Record<string, string>) => write<{ sourceVersionId: string }>("source/save", { projectId, expectedVersion, files }),
  };
}
