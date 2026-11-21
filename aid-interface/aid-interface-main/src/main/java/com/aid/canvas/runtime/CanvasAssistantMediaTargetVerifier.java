package com.aid.canvas.runtime;

import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasGenerationService;
import com.aid.runtime.assistant.execution.AssistantMediaTaskTargetVerifier;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Waits for the real Canvas binding/results; leaves late results in history without overwriting a newer node. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantMediaTargetVerifier implements AssistantMediaTaskTargetVerifier {
    private final CanvasGenerationService generation;
    private final CanvasAssistantMediaBindingVerifier bindings;
    private final CanvasMapper canvas;

    @Override
    public Set<String> supportedToolCodes() { return CanvasAssistantMediaToolProvider.CODES; }

    @Override
    public boolean isTargetPersisted(AidRuntimeRun run, AidRuntimeRunStep step, Long taskId) {
        var proof = bindings.require(run.getUserId(), run, step, taskId, false);
        // taskDetail uses the existing idempotent task settlement/storage and automatic selection path.
        if (proof.binding().getTaskId() == null) return false;
        generation.taskDetail(proof.target().workspaceId(), taskId, run.getUserId());
        proof = bindings.require(run.getUserId(), run, step, taskId, false);
        if (!"SUCCEEDED".equals(proof.binding().getStatus())) return false;
        var results = canvas.selectMediaResultsByTaskIds(List.of(taskId));
        if (results == null || results.isEmpty()) return false;
        String mediaType = proof.binding().getMediaType();
        return results.stream().allMatch(result -> Objects.equals(mediaType, result.get("mediaType"))
                && result.get("ossUrl") instanceof String url && !url.isBlank());
    }
}
