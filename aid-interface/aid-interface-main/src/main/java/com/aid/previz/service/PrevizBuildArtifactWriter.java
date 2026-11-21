package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.service.AssistantArtifactWriter;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Archives the verified build manifest; GLB and authored source remain in the project workspace. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PrevizBuildArtifactWriter {
    private final AssistantArtifactWriter writer;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimePlanMapper plans;

    @Transactional(rollbackFor = Exception.class)
    public RuntimeToolResult archive(RuntimeToolPreparedInvocation prepared, RuntimeToolResult result) {
        if (!"SUCCEEDED".equals(result.getStatus())) return result;
        JSONObject receipt = JSON.parseObject(result.getOutputJson());
        boolean preview = PrevizRuntimeToolProvider.RENDER_PREVIEW.equals(prepared.getToolCode());
        JSONObject artifact = receipt == null ? null : receipt.getJSONObject("artifact");
        if (preview ? receipt == null || !digest(receipt.getString("imageId")) || !digest(receipt.getString("sha256"))
                || !receipt.getString("imageId").equals(receipt.getString("sha256"))
                : artifact == null || !"BUILT".equals(receipt.getString("state"))
                || !digest(artifact.getString("sha256")) || !digest(receipt.getString("sourceSha256")))
            throw new ServiceException("PREVIZ_BUILD_RECEIPT_INVALID");
        var run = runs.selectById(prepared.getRunId());
        if (run == null || !Objects.equals(run.getUserId(), prepared.getUserId())
                || !Objects.equals(run.getPlanRevision(), prepared.getPlanRevision()))
            throw new ServiceException("PREVIZ_BUILD_IDENTITY_CHANGED");
        var plan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, run.getId())
                .eq(AidRuntimePlan::getRevision, prepared.getPlanRevision())
                .eq(AidRuntimePlan::getDelFlag, "0"));
        String logicalId = null;
        if (plan != null) {
            var steps = JSON.parseObject(plan.getDefinitionJson()).getJSONArray("steps");
            if (steps != null) for (Object item : steps) {
                if (!(item instanceof JSONObject step)
                        || !Objects.equals(prepared.getStableStepId(), step.getString("stableStepId"))) continue;
                if (!prepared.getToolCode().equals(step.getString("bindingCode")))
                    throw new ServiceException("PREVIZ_BUILD_PLAN_CHANGED");
                var ids = step.getJSONArray("outputLogicalArtifactIds");
                if (ids != null && ids.size() == 1) logicalId = ids.getString(0);
            }
        }
        if (logicalId == null || !logicalId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))
            return result.failOutputContract("PREVIZ_BUILD_OUTPUT_REQUIRED","生成已完成，但结果整理未完成；原结果已保留");
        receipt.put("runtimeStepId",String.valueOf(prepared.getRuntimeStepId()));
        JSONObject context = JSON.parseObject(prepared.getContextManifestJson());
        var references = context == null ? null : context.getJSONArray("refs");
        if (!preview && references != null) for (Object raw : references) {
            if (!(raw instanceof JSONObject reference) || !"PREVIZ_EDITOR".equals(reference.getString("kind"))) continue;
            JSONObject canonical = reference.getJSONObject("canonicalRef");
            if (canonical == null || !Objects.equals(receipt.getString("projectId"), canonical.getString("previzProjectId"))
                    || receipt.containsKey("editorVersion")) throw new ServiceException("PREVIZ_EDITOR_CONTEXT_INVALID");
            receipt.put("editorVersion", canonical.getString("objectId"));
        }
        String content = receipt.toJSONString();
        String hash = SecureUtil.sha256(content);
        var create = new ArtifactWriteRequests.CreateCandidate();
        create.setRunId(run.getId()); create.setStepId(prepared.getRuntimeStepId());
        create.setLogicalArtifactId(logicalId); create.setArtifactType(preview ? "PREVIZ_PREVIEW" : "PREVIZ_SCENE");
        create.setSchemaVersion("1.0"); create.setAgentVersionId(run.getVersionId());
        create.setPlanRevision(prepared.getPlanRevision());
        create.setSourceContextDigest(run.getContextDigest()); create.setSourceContextRefsJson("[]");
        create.setContentHash(hash); create.setExpectedChunkCount(1);
        create.setSummary(preview ? "真实单帧预览归档；画面质量由实际图像核验" : "三维场景构建清单；视觉预览与采用状态单独核验");
        create.setMetadataJson(JSON.toJSONString(java.util.Map.of("technicalStatus", "SUCCEEDED",
                "creativeQualityVerified", false, "projectId", receipt.getString("projectId"),
                preview ? "imageSha256" : "sourceSha256", preview ? receipt.getString("sha256") : receipt.getString("sourceSha256"))));
        var ref = writer.createCandidate(create, prepared.getRunLease(), prepared.getStepLease());
        if (!"FINALIZED".equals(ref.getStatus())) {
            var chunk = new ArtifactWriteRequests.AppendChunk();
            chunk.setRunId(run.getId()); chunk.setStepId(prepared.getRuntimeStepId());
            chunk.setArtifactId(ref.getArtifactId()); chunk.setChunkIndex(0); chunk.setCharStart(0);
            chunk.setContent(content); chunk.setContentHash(hash);
            writer.appendChunk(chunk, prepared.getRunLease(), prepared.getStepLease());
            var finish = new ArtifactWriteRequests.FinalizeCandidate();
            finish.setRunId(run.getId()); finish.setStepId(prepared.getRuntimeStepId());
            finish.setArtifactId(ref.getArtifactId()); finish.setExpectedChunkCount(1); finish.setContentHash(hash);
            ref = writer.finalizeCandidate(finish, prepared.getRunLease(), prepared.getStepLease());
        }
        receipt.put("artifactRef", ref.getRef());
        result.setOutputJson(receipt.toJSONString()); result.setOutputArtifactRefs(List.of(ref.getRef()));
        return result;
    }

    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
