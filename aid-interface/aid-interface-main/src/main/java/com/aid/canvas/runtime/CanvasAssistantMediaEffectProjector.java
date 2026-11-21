package com.aid.canvas.runtime;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.effect.AssistantRuntimeToolEffectProjector;
import com.aid.assistant.effect.AssistantTargetEffectProjection;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Locate projection for real completed media on its original Canvas target. Never invents node/artifact revisions. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantMediaEffectProjector implements AssistantRuntimeToolEffectProjector {
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final CanvasAssistantMediaBindingVerifier bindings;

    @Override
    public Set<String> supportedToolCodes() { return CanvasAssistantMediaToolProvider.CODES; }

    @Override
    public AssistantTargetEffectProjection project(VerifiedRuntimeToolEffect effect) {
        if (effect == null || !supportedToolCodes().contains(effect.toolCode())) invalid();
        var output = JSON.parseObject(effect.outputJson());
        var run = runs.selectById(effect.runId());
        var step = steps.selectById(effect.stepId());
        if (step == null || !"SUCCEEDED".equals(step.getStatus()) || output == null
                || !"FINALIZED".equals(output.getString("status"))
                || !Objects.equals(output.getLong("mediaTaskId"), step.getMediaTaskId())) invalid();
        var proof = bindings.requireHistoricalCompleted(effect.userId(), run, step, step.getMediaTaskId());
        if (!"SUCCEEDED".equals(proof.binding().getStatus())
                || !Objects.equals(proof.contextManifestJson(), effect.contextManifestJson())) invalid();
        var artifacts = output.getJSONArray("artifacts");
        if (artifacts == null || artifacts.isEmpty()) invalid();
        List<String> refs = artifacts.stream().map(value -> {
            var row = JSON.parseObject(JSON.toJSONString(value));
            if (!"FINALIZED".equals(row.getString("status")) || row.getString("ref") == null
                    || !row.getString("ref").startsWith("artifact:")) invalid();
            return row.getString("ref");
        }).toList();
        if (!Objects.equals(refs, effect.outputArtifactRefs())) invalid();
        var target = new AssistantTargetViews.TargetRef();
        target.setKind("FREE_CANVAS");target.setProjectId(String.valueOf(proof.target().projectId()));
        target.setWorkspaceId(String.valueOf(proof.target().workspaceId()));
        target.setObjectId(proof.target().objectId());target.setVersion(String.valueOf(proof.target().objectVersion()));
        var identity = new AssistantRequests.ContextRef();
        identity.setKind("FREE_CANVAS");identity.setProjectId(proof.target().projectId());
        identity.setWorkspaceId(proof.target().workspaceId());identity.setObjectId(proof.target().objectId());
        return new AssistantTargetEffectProjection("OBSERVED", target, identity);
    }

    private static void invalid() {
        throw new ServiceException("画布媒体结果暂不可定位").setDetailMessage("CANVAS_MEDIA_EFFECT_INVALID");
    }
}
