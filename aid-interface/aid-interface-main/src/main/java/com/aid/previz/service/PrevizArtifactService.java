package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Only persisted successful build steps may supply an automatically adopted model. */
@Service
@RequiredArgsConstructor
public class PrevizArtifactService
{
    private final PrevizProjectService projects;
    private final AidRuntimeRunStepMapper steps;
    private final ObjectProvider<PrevizLocalBuildService> workers;
    private final com.aid.previz.mapper.PrevizArtifactMapper approvals;
    public JSONObject previewReceipt(Long actor,String projectId,Long stepId) {
        projects.requireRead(actor,projectId);
        var step=steps.selectById(stepId);
        if(step==null||!"0".equals(step.getDelFlag())||!"SUCCEEDED".equals(step.getStatus())
                ||!java.util.Set.of(PrevizRuntimeToolProvider.PREVIEW,PrevizRuntimeToolProvider.RENDER_PREVIEW).contains(step.getToolCode()))throw new ServiceException("预览尚未完成");
        JSONObject receipt=JSON.parseObject(step.getOutputJson());
        if(receipt==null||!projectId.equals(receipt.getString("projectId")))throw new ServiceException("预览项目不一致");
        JSONObject result=new JSONObject();
        for(String key:java.util.List.of("projectId","buildStepId","imageId","sha256","width","height","cameraId","view","actualTime","requestedTime","animationIndex","inspection","addedPreviewLight","visualReviewed","reviewScope"))
            if(receipt.containsKey(key))result.put(key,receipt.get(key));
        return result;
    }

    public JSONObject manifest(Long actor, String projectId, Long stepId) {
        projects.requireRead(actor, projectId);
        var step = stepId == null ? null : steps.selectById(stepId);
        if (step == null || !"0".equals(step.getDelFlag()) || !"SUCCEEDED".equals(step.getStatus())
                || !PrevizRuntimeToolProvider.isBuild(step.getToolCode())) throw new ServiceException("构建结果尚未确认");
        JSONObject receipt = JSON.parseObject(step.getOutputJson());
        if (receipt == null || !projectId.equals(receipt.getString("projectId")) || !"BUILT".equals(receipt.getString("state")))
            throw new ServiceException("构建结果与工程不一致");
        JSONObject result = new JSONObject();
        for (String key : java.util.List.of("engine", "sceneId", "sourceVersionId", "artifact", "nativeArtifacts", "warnings", "previewSettings", "editorVersion"))
            if (receipt.containsKey(key)) result.put(key, receipt.get(key));
        result.put("runtimeStepId", String.valueOf(stepId)); result.put("projectId", projectId);
        result.put("runtimeRunId", String.valueOf(step.getRunId()));
        addApprovedEntry(result, stepId, step.getRunId());
        return result;
    }

    public PrevizLocalBuildService.NativeFile nativeFile(Long actor, String projectId, Long stepId) throws IOException {
        projects.requireRead(actor, projectId);
        var step = stepId == null ? null : steps.selectById(stepId);
        if (step == null || !"0".equals(step.getDelFlag()) || !"SUCCEEDED".equals(step.getStatus())
                || !PrevizRuntimeToolProvider.isBuild(step.getToolCode()) || step.getIdempotencyKey() == null)
            throw new ServiceException("构建结果尚未确认或无权读取");
        JSONObject receipt = JSON.parseObject(step.getOutputJson());
        if (receipt == null || !projectId.equals(receipt.getString("projectId")) || !"BUILT".equals(receipt.getString("state")))
            throw new ServiceException("构建结果与工程不一致");
        var worker = workers.getIfAvailable();
        if (worker == null) throw new ServiceException("原生工程读取节点不可用");
        var file = worker.nativeArtifact(actor, projectId, "runtime-" + SecureUtil.sha256(step.getIdempotencyKey()));
        var metadata = receipt.getJSONArray("nativeArtifacts");
        if (metadata == null || metadata.size() != 1 || !file.sha256().equals(metadata.getJSONObject(0).getString("sha256")))
            throw new ServiceException("原生工程与归档回执不一致");
        return file;
    }

    public JSONObject read(Long actor, String projectId, Long stepId) throws IOException
    {
        projects.requireRead(actor, projectId);
        var step = stepId == null ? null : steps.selectById(stepId);
        if (step == null || !"0".equals(step.getDelFlag()) || !"SUCCEEDED".equals(step.getStatus())
                || !PrevizRuntimeToolProvider.isBuild(step.getToolCode())
                || step.getIdempotencyKey() == null || step.getIdempotencyKey().isBlank())
            throw new ServiceException("构建结果尚未确认或无权读取");
        JSONObject receipt = JSON.parseObject(step.getOutputJson());
        if (receipt == null || !projectId.equals(receipt.getString("projectId")) || !"BUILT".equals(receipt.getString("state")))
            throw new ServiceException("构建结果与当前工程不一致");
        PrevizLocalBuildService worker = workers.getIfAvailable();
        if (worker == null) throw new ServiceException("当前计算节点尚未配置产物读取");
        String jobId = "runtime-" + SecureUtil.sha256(step.getIdempotencyKey());
        JSONObject result = worker.artifact(actor, projectId, jobId);
        if (!java.util.Objects.equals(receipt.getString("sourceSha256"), result.getString("sourceSha256")))
            throw new ServiceException("构建产物版本不一致");
        result.put("runtimeStepId", String.valueOf(stepId));
        result.put("runtimeRunId", String.valueOf(step.getRunId()));
        addApprovedEntry(result, stepId, step.getRunId());
        if (receipt.containsKey("editorVersion")) result.put("editorVersion",receipt.getString("editorVersion"));
        return result;
    }

    private void addApprovedEntry(JSONObject result, Long stepId, Long runId) {
        String payload = approvals.approvedBuildPayload(stepId, runId);
        if (payload == null) return; // Legacy receipts keep their existing explicit scene identity.
        if (payload.length() > 2 * 1024 * 1024) throw new ServiceException("构建来源回执无效");
        JSONObject prepared = JSON.parseObject(payload);
        String entry = prepared.getString("entry");
        if (entry == null) return;
        if (entry.isBlank() || entry.length() > 240 || entry.startsWith("/")
                || entry.contains("\\") || entry.contains(":")
                || java.util.Arrays.stream(entry.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new ServiceException("构建入口来源无效");
        if (!java.util.Objects.equals(prepared.getString("engine"), result.getString("engine"))
                || !java.util.Objects.equals(prepared.getString("sourceVersion"), result.getString("sourceVersionId")))
            throw new ServiceException("构建入口与源码回执不一致");
        result.put("entry", entry);
        if(prepared.get("operation") instanceof JSONObject operation)result.put("operation",operation);
    }
}
