package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import com.aid.runtime.assistant.execution.AssistantTechnicalArchiveVerifier;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Checks sealed manifest, original build identity, project authority and actual GLB bytes. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PrevizSceneArchiveCompletionVerifier implements AssistantTechnicalArchiveVerifier {
    private final AidRuntimeArtifactChunkMapper chunks;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final PrevizSourceStore sources;
    private final ObjectProvider<PrevizLocalBuildService> workers;

    @Override public String artifactType() { return "PREVIZ_SCENE"; }
    @Override public void requireArchived(AidRuntimeArtifact artifact) {
        try {
            if (artifact == null || !artifactType().equals(artifact.getArtifactType())
                    || !"FINALIZED".equals(artifact.getStatus()) || !"0".equals(artifact.getDelFlag())) invalid();
            var run = runs.selectById(artifact.getProducerRunId());
            var step = steps.selectById(artifact.getProducerStepId());
            if (run == null || step == null || !Objects.equals(step.getRunId(), run.getId())
                    || !"SUCCEEDED".equals(step.getStatus()) || !PrevizRuntimeToolProvider.isBuild(step.getToolCode())
                    || step.getIdempotencyKey() == null) invalid();
            var rows = chunks.selectAllActive(artifact.getId());
            if (rows.size() != 1 || rows.get(0).getChunkIndex() != 0 || rows.get(0).getCharStart() != 0) invalid();
            String content = rows.get(0).getContentText();
            if (content == null || content.length() > 65536 || !SecureUtil.sha256(content).equals(artifact.getContentHash())
                    || !Objects.equals(rows.get(0).getContentHash(), artifact.getContentHash())) invalid();
            var manifest = JSON.parseObject(content);
            String job = "runtime-" + SecureUtil.sha256(step.getIdempotencyKey());
            if (!"BUILT".equals(manifest.getString("state")) || !job.equals(manifest.getString("jobId"))) invalid();
            String project = manifest.getString("projectId"), version = manifest.getString("sourceSha256");
            sources.read(run.getUserId(), project, version);
            var worker = workers.getIfAvailable();
            if (worker == null) invalid();
            var current = worker.artifact(run.getUserId(), project, job);
            if (!Objects.equals(version, current.getString("sourceSha256"))
                    || !Objects.equals(manifest.getJSONObject("artifact"), current.getJSONObject("artifact"))
                    || !Objects.equals(manifest.getString("engine"), current.getString("engine"))) invalid();
        } catch (java.io.IOException | RuntimeException error) {
            throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID");
        }
    }
    private static void invalid() { throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID"); }
}
