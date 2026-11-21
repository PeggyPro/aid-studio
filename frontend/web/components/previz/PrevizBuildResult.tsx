"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { AssistantArtifact } from "@/types/assistant-api";
import { createPrevizWorkspaceApi, type PrevizBuildArtifact } from "@/utils/previz/remoteWorkspace";
import { assistantErrorMessage } from "@/utils/assistant/assistantRequestCoordinator";
import { PrevizBuildFiles } from "./PrevizBuildFiles";
import { previzBuildArtifactBytes } from "@/utils/previz/buildArtifactBytes";
import { downloadPreviz } from "@/utils/previz/export";

export function PrevizBuildResult({ projectId, artifact }: { projectId: string; artifact: AssistantArtifact }) {
  const api = useMemo(createPrevizWorkspaceApi, [projectId]);
  const [manifest, setManifest] = useState<Omit<PrevizBuildArtifact, "contentBase64" | "mimeType"> | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [downloading,setDownloading]=useState(false),[downloadError,setDownloadError]=useState("");
  const downloadingRef=useRef(false),identity=useRef(""),downloadGeneration=useRef(0);
  const stepId = artifact.producerStepId;
  const currentIdentity=projectId+":"+stepId;identity.current=currentIdentity;
  useEffect(()=>{identity.current=currentIdentity;++downloadGeneration.current;downloadingRef.current=false;setDownloading(false);setDownloadError("");
    return()=>{identity.current="";++downloadGeneration.current;};},[currentIdentity]);
  const steps = useMemo(() => stepId ? [stepId] : [], [stepId]);
  useEffect(() => {
    let active = true;
    setManifest(null);setError("");
    if (!stepId) { setError("此结果缺少构建步骤，暂时无法读取"); return; }
    void api.buildManifest(projectId, stepId).then(value => {
      if (value.runtimeStepId !== stepId || value.projectId !== projectId) throw new Error("场景结果与工程不一致");
      if (active) { setManifest(value); setError(""); }
    }).catch(failure => { if (active) setError(assistantErrorMessage(failure)); });
    return () => { active = false; };
  }, [api, projectId, stepId, retry]);
  const download=async()=>{
    if(downloadingRef.current||!stepId||!manifest||manifest.runtimeStepId!==stepId)return;
    const expected=currentIdentity,generation=downloadGeneration.current;downloadingRef.current=true;setDownloading(true);setDownloadError("");
    try{
      const value=await api.artifact(projectId,stepId);
      if(value.projectId!==projectId||value.runtimeStepId!==stepId||value.sourceVersionId!==manifest.sourceVersionId
        ||value.artifact.sha256!==manifest.artifact.sha256)throw new Error("下载产物与构建记录不一致");
      const bytes=await previzBuildArtifactBytes(value);
      if(identity.current===expected&&generation===downloadGeneration.current)downloadPreviz(new Blob([bytes],{type:"model/gltf-binary"}),`scene-${stepId}.glb`);
    }catch(failure){if(identity.current===expected&&generation===downloadGeneration.current)setDownloadError(assistantErrorMessage(failure));}
    finally{if(identity.current===expected&&generation===downloadGeneration.current){downloadingRef.current=false;setDownloading(false);}}
  };
  return <article className="assistant-inline-artifact" aria-label="三维场景构建结果">
    <header>三维场景构建结果</header>
    {manifest ? <>
      <p>{manifest.engine === "blender" ? "Blender" : "Three.js"} 场景已构建并归档。</p>
      <p>可在当前工程查看载入结果；构建成功不代表已经完成画面预览验收。</p>
      <div className="pv-reference-images"><button type="button" disabled={downloading} onClick={()=>void download()}>{downloading?"正在校验下载…":"下载本次 GLB"}</button></div>
      {downloadError?<p role="alert">{downloadError}</p>:null}
      {manifest.warnings.length ? <details><summary>导出兼容提示（{manifest.warnings.length}）</summary>
        <ul>{manifest.warnings.map((warning, index) => <li key={index}>{warning.code === "LIGHT_NOT_EXPORTED"
          ? `${warning.type === "AREA" ? "面光源" : warning.type === "HemisphereLight" ? "半球环境光" : warning.type}不支持导出到 GLB，仍保留在源码${manifest.nativeArtifacts?.length ? "和原生工程" : ""}中。`
          : `导出提示：${warning.code}`}</li>)}</ul></details> : null}
      <PrevizBuildFiles projectId={projectId} stepIds={steps} />
    </> : !error ? <p role="status">正在读取场景清单…</p> : null}
    {error ? <p role="alert">{error} <button type="button" onClick={() => setRetry(value => value + 1)}>重新读取</button></p> : null}
  </article>;
}
