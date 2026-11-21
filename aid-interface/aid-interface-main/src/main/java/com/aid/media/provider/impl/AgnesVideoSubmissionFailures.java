package com.aid.media.provider.impl;

import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmissionFailures;
import com.aid.media.provider.ProviderSubmitResult;
import java.util.Set;

/** Admission rejection from the submit endpoint; never applies to a task-status query. */
public final class AgnesVideoSubmissionFailures {
    private AgnesVideoSubmissionFailures() { }

    public static ProviderSubmitResult classify(int status, String body, String model) {
        var result = ProviderSubmissionFailures.httpResponse(status, body, model);
        if (status != 503) return result;
        var root = ProviderResponseHelper.readTree(body);
        if (root == null || !root.isObject()) return result;
        String code = ProviderResponseHelper.readText(root, "error.code", "code");
        if (!"video_queue_full".equals(code)) return result;
        String accepted = ProviderResponseHelper.readText(root,
                "video_id", "task_id", "id", "data.video_id", "data.task_id", "data.id",
                "video_url", "url", "data.video_url", "data.url", "metadata.url", "output.video_url");
        if (accepted != null && !accepted.isBlank()) return result;
        String state = ProviderResponseHelper.readText(root, "status", "data.status", "task_status");
        if (state != null && !Set.of("failed", "error", "rejected").contains(state.toLowerCase(java.util.Locale.ROOT))) return result;
        for (String field : Set.of("data", "output", "result")) {
            if (!root.path(field).isMissingNode() && !root.path(field).isNull()) return result;
        }
        if (!root.path("usage").isMissingNode() && !root.path("usage").isNull()
                || !root.path("data").path("usage").isMissingNode() && !root.path("data").path("usage").isNull()) return result;
        result.setConfirmedFailureCode("PROVIDER_REQUEST_REJECTED");
        result.setCapacityRejected(true);
        return result;
    }
}
