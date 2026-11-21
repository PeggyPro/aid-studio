package com.aid.runtime.assistant.execution;

import com.aid.assistant.service.AssistantToolReceiptProjector;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Verifies completed Tool authority before projecting a non-navigable business receipt. */
@Service
@RequiredArgsConstructor
public class AssistantToolReceiptContextService {
    private static final int MAX_RECEIPT_BYTES = 8192;
    private final AssistantCompletedToolScopeVerifier completedTools;
    @Autowired(required = false)
    private List<AssistantToolReceiptProjector> projectors = List.of();

    public JSONObject project(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        var proof = completedTools.require(userId, run, step);
        if (proof.outputArtifactRefs() == null || !proof.outputArtifactRefs().isEmpty()) invalid();
        var matches = projectors.stream()
                .filter(projector -> projector.supportedToolCodes().contains(proof.toolCode())).toList();
        if (matches.size() != 1) invalid();
        JSONObject projected = matches.get(0).projectCurrentReceipt(proof);
        if (projected == null || projected.isEmpty()) invalid();
        String json = projected.toJSONString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RECEIPT_BYTES) invalid();
        // Return an independent JSON value; source domain entities and mutable adapter state
        // cannot be used as runtime authority or mutate the assembled context afterward.
        return JSON.parseObject(json);
    }
    public JSONObject projectRetained(Long userId,AidRuntimeRun run,AidRuntimeRunStep step){
        if(!completedTools.belongsToCurrentContext(userId,run,step))return null;
        return project(userId,run,step);
    }

    private static void invalid() {
        throw new ServiceException("ASSISTANT_RESULT_RECEIPT_INVALID");
    }
}
