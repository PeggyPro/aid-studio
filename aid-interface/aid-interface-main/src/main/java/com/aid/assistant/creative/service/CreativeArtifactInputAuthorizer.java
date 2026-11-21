package com.aid.assistant.creative.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Resolves exactly one artifact reference frozen into the current persisted child step. */
@Component
public class CreativeArtifactInputAuthorizer {
    private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC");

    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;

    public AidRuntimeArtifact requireTextInput(RuntimeToolInvocation invocation, Long artifactId) {
        if (invocation == null) throw new IllegalArgumentException("Artifact invocation is invalid");
        return require(invocation.getUserId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                invocation.getPlanRevision(), invocation.getStableStepId(), invocation.getAttemptNo(),
                invocation.getToolCode(), artifactId);
    }

    public AidRuntimeArtifact requireTextInput(RuntimeToolPreparedInvocation invocation, Long artifactId) {
        if (invocation == null) throw new IllegalArgumentException("Artifact invocation is invalid");
        return require(invocation.getUserId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                invocation.getPlanRevision(), invocation.getStableStepId(), invocation.getAttemptNo(),
                invocation.getToolCode(), artifactId);
    }

    private AidRuntimeArtifact require(Long userId, Long runId, Long runtimeStepId, Long planRevision,
                                       String stableStepId, Integer attemptNo, String toolCode, Long artifactId) {
        if (userId == null || userId < 1 || runId == null || runId < 1
                || runtimeStepId == null || runtimeStepId < 1 || artifactId == null || artifactId < 1) {
            throw new IllegalArgumentException("Artifact input identity is invalid");
        }
        AidRuntimeArtifact artifact = artifactMapper.selectAuthorized(userId, artifactId);
        String artifactRef = artifact == null ? null
                : "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision();
        AidRuntimeRunStep step = stepMapper.selectById(runtimeStepId);
        JSONArray inputs;
        try { inputs = step == null ? null : JSON.parseArray(step.getInputArtifactRef()); }
        catch (RuntimeException invalid) { inputs = null; }
        if (artifact == null || !"FINALIZED".equals(artifact.getStatus())
                || artifact.getRevision() == null || artifact.getRevision() < 1
                || StrUtil.isBlank(artifact.getLogicalArtifactId())
                || artifact.getContentHash() == null || !artifact.getContentHash().matches("[0-9a-f]{64}")
                || MEDIA_TYPES.contains(artifact.getArtifactType())
                || !Objects.equals(artifact.getProducerRunId(), runId)
                || step == null || !Objects.equals(step.getRunId(), runId)
                || !Objects.equals(step.getPlanRevision(), planRevision)
                || !Objects.equals(step.getStableStepId(), stableStepId)
                || !Objects.equals(step.getAttemptNo(), attemptNo)
                || !"TOOL".equals(step.getStepKind())
                || !Objects.equals(step.getToolCode(), toolCode)
                || inputs == null || inputs.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(artifactRef::equals).count() != 1) {
            throw new IllegalArgumentException("Artifact is not an authorized frozen plan input");
        }
        return artifact;
    }
}
