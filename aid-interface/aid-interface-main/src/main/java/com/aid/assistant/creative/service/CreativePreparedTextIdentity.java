package com.aid.assistant.creative.service;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.runtime.tool.RuntimeToolBillingIntent;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binds server-only model identity to one frozen Runtime call and billing quote. */
public final class CreativePreparedTextIdentity {
    private static final String SCHEMA_VERSION = "creative-text-server-identity/v1";

    private CreativePreparedTextIdentity() {
    }

    public static String digest(Long userId, Long runId, Long runtimeStepId, String stableStepId,
                                Integer attemptNo, String logicalCallKey, String modelInvocationIdentity,
                                String submissionPromptDigest, RuntimeToolBillingIntent intent,
                                AssistantBudgetOperationScope scope) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schemaVersion", SCHEMA_VERSION);
        identity.put("userId", userId == null ? null : String.valueOf(userId));
        identity.put("runId", runId == null ? null : String.valueOf(runId));
        identity.put("runtimeStepId", runtimeStepId == null ? null : String.valueOf(runtimeStepId));
        identity.put("stableStepId", stableStepId);
        identity.put("attemptNo", attemptNo);
        identity.put("logicalCallKey", logicalCallKey);
        identity.put("modelInvocationIdentity", modelInvocationIdentity);
        identity.put("submissionPromptDigest", submissionPromptDigest);
        identity.put("mediaType", intent == null ? null : intent.getMediaType());
        identity.put("normalizedRequestDigest", intent == null ? null : intent.getRequestDigest());
        identity.put("quoteId", intent == null ? null : intent.getQuoteId());
        identity.put("quoteDigest", intent == null ? null : intent.getQuoteDigest());
        identity.put("quoteExpiresAt", intent == null || intent.getQuoteExpiresAt() == null
                ? null : intent.getQuoteExpiresAt().getTime());
        identity.put("quotedUpperBound", decimal(intent == null ? null : intent.getQuotedUpperBound()));
        identity.put("normalizedRequestJson", intent == null ? null : intent.getNormalizedRequestJson());
        identity.put("operationScope", canonicalScope(scope));
        return SecureUtil.sha256(JSON.toJSONString(identity));
    }

    private static Map<String, Object> canonicalScope(AssistantBudgetOperationScope scope) {
        if (scope == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operationKind", scope.getOperationKind());
        value.put("toolCode", scope.getToolCode());
        value.put("modelCode", scope.getModelCode());
        value.put("capabilityCodes", sorted(scope.getCapabilityCodes()));
        value.put("mediaTypes", sorted(scope.getMediaTypes()));
        value.put("targetRefs", scope.getTargetRefs() == null ? List.of() : scope.getTargetRefs().stream()
                .map(CreativePreparedTextIdentity::canonicalTarget)
                .sorted(Comparator.comparing(item -> JSON.toJSONString(item))).toList());
        value.put("quotedUpperBound", decimal(scope.getQuotedUpperBound()));
        value.put("countsAsIteration", scope.isCountsAsIteration());
        return value;
    }

    private static Map<String, Object> canonicalTarget(AssistantRequests.ContextRef ref) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (ref == null) return value;
        value.put("kind", ref.getKind());
        value.put("workspaceId", ref.getWorkspaceId() == null ? null : String.valueOf(ref.getWorkspaceId()));
        value.put("objectId", ref.getObjectId());
        value.put("version", ref.getVersion());
        value.put("projectId", ref.getProjectId() == null ? null : String.valueOf(ref.getProjectId()));
        value.put("episodeId", ref.getEpisodeId() == null ? null : String.valueOf(ref.getEpisodeId()));
        value.put("entityType", ref.getEntityType());
        value.put("entityId", ref.getEntityId());
        value.put("scriptId", ref.getScriptId() == null ? null : String.valueOf(ref.getScriptId()));
        value.put("contentHash", ref.getContentHash());
        value.put("selection", ref.getSelection());
        value.put("reason", ref.getReason());
        return value;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
