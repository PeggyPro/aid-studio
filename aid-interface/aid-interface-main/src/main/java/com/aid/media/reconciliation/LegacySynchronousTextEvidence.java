package com.aid.media.reconciliation;

import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.media.MediaExecutionPermit;
import java.util.Objects;
import java.util.Set;

/** Recognizes an ended imported chat-completions execution, not an upstream rejection. */
final class LegacySynchronousTextEvidence {
    private static final Set<String> PROTOCOLS = Set.of("openai-compatible-text", "deepseek:chat-completions");

    static boolean matches(AidMediaTask task, MediaExecutionPermit permit) {
        if (task == null || permit == null || !"TEXT".equals(task.getMediaType())
                || !"LEGACY".equals(permit.getExecutionMode())
                || !"CONFIRMED_LOCAL_PROCESS_ENDED".equals(permit.getReleaseReason())
                || !PROTOCOLS.contains(Objects.toString(task.getProtocol(), ""))
                || task.getResponseJson() != null && !task.getResponseJson().isBlank()
                || !JSONUtil.isTypeJSONObject(task.getRequestJson())
                || !JSONUtil.isTypeJSONObject(task.getProviderRouteSnapshotJson())) return false;
        try {
            var request = JSONUtil.parseObj(task.getRequestJson());
            var route = JSONUtil.parseObj(task.getProviderRouteSnapshotJson());
            String suffix = route.getStr("apiSuffix");
            return Objects.equals(task.getProtocol(), route.getStr("protocol"))
                    && Set.of("/chat/completions", "/v1/chat/completions").contains(Objects.toString(suffix, ""))
                    && Objects.equals(task.getModelName(), request.getStr("modelName"))
                    && Objects.equals(task.getUserId(), request.getLong("userId"))
                    && request.getJSONArray("messages") != null && !request.getJSONArray("messages").isEmpty()
                    && !request.getBool("background", false)
                    && (request.getJSONObject("options") == null || !request.getJSONObject("options").getBool("background", false));
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }
}
