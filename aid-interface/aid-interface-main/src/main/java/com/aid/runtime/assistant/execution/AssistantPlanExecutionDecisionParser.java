package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Defensive parser used even after the loop output-schema parser accepted the decision. */
final class AssistantPlanExecutionDecisionParser
{
    private static final Set<String> PROTOCOLS = Set.of("assistant-loop/v1", "assistant-loop/native-v1");
    private static final int MAX_DECISION_BYTES = 1_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern NATIVE_OPERATION_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]*(?:/[A-Za-z0-9][A-Za-z0-9._:-]*)*");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PAYLOADS = Set.of(
            "chat", "ask", "plan", "tool", "skill", "checkpoint", "complete", "fail");
    private static final Set<String> ENVELOPE = Set.of(
            "protocolVersion", "decisionId", "observedRunRevision", "observedPlanRevision", "action",
            "chat", "ask", "plan", "tool", "skill", "checkpoint", "complete", "fail");
    private static final Set<String> TOOL = Set.of(
            "invocationId", "stableStepId", "attemptNo", "toolCode", "contractVersion",
            "operationKind", "arguments", "inputArtifactRefs", "expectedContextRevisions");
    private static final Set<String> SKILL = Set.of(
            "invocationId", "stableStepId", "attemptNo", "skillCode", "skillVersionId",
            "packageDigest", "input");

    private final int maxContextRevisions;

    AssistantPlanExecutionDecisionParser(int maxContextRevisions)
    {
        if (maxContextRevisions < 1 || maxContextRevisions > 1_001)
        {
            throw new IllegalArgumentException("Invalid Assistant context revision limit");
        }
        this.maxContextRevisions = maxContextRevisions;
    }

    AssistantPlanExecutionDecision parse(String decisionJson)
    {
        if (StrUtil.isBlank(decisionJson) || decisionJson.length() > MAX_DECISION_BYTES)
        {
            invalid("DECISION_INVALID");
        }
        JSONObject envelope;
        try
        {
            envelope = JSON.parseObject(decisionJson);
        }
        catch (RuntimeException parseFailure)
        {
            throw new ServiceException("DECISION_INVALID");
        }
        exactKeys(envelope, ENVELOPE, "DECISION_FIELDS_INVALID");
        requiredText(envelope.getString("protocolVersion"), 40, "DECISION_PROTOCOL_INVALID");
        String protocol = envelope.getString("protocolVersion");
        if (!PROTOCOLS.contains(protocol)) invalid("DECISION_PROTOCOL_INVALID");
        String decisionId = identifier(envelope.getString("decisionId"), "DECISION_ID_INVALID");
        Long observedRun = positiveOrZero(envelope, "observedRunRevision", "DECISION_REVISION_INVALID");
        Long observedPlan = positiveOrZero(envelope, "observedPlanRevision", "DECISION_REVISION_INVALID");
        String action = envelope.getString("action");
        if (!("TOOL".equals(action) || "SKILL".equals(action))) invalid("DECISION_ACTION_INVALID");
        String selected = action.toLowerCase();
        JSONObject payload = envelope.getJSONObject(selected);
        if (payload == null) invalid("DECISION_PAYLOAD_INVALID");
        for (String name : PAYLOADS)
        {
            if (!name.equals(selected) && envelope.get(name) != null) invalid("DECISION_PAYLOAD_INVALID");
        }

        if ("TOOL".equals(action))
        {
            exactKeys(payload, TOOL, "TOOL_DECISION_FIELDS_INVALID");
            JSONObject arguments = payload.getJSONObject("arguments");
            if (arguments == null) invalid("TOOL_ARGUMENTS_INVALID");
            List<String> artifacts = stringArray(payload.getJSONArray("inputArtifactRefs"), 1000, 500,
                    "TOOL_ARTIFACT_REFS_INVALID");
            JSONArray revisions = payload.getJSONArray("expectedContextRevisions");
            if (revisions == null || revisions.size() > maxContextRevisions)
                invalid("TOOL_CONTEXT_REVISIONS_INVALID");
            return new AssistantPlanExecutionDecision(protocol, decisionId, observedRun, observedPlan, action,
                    operationIdentifier(payload.get("invocationId"), protocol, 128, "TOOL_INVOCATION_ID_INVALID"),
                    operationIdentifier(payload.get("stableStepId"), protocol, 64, "TOOL_STABLE_STEP_INVALID"),
                    attempt(payload), text(payload, "operationKind", 64, "TOOL_OPERATION_INVALID"),
                    text(payload, "toolCode", 100, "TOOL_CODE_INVALID"),
                    text(payload, "contractVersion", 32, "TOOL_VERSION_INVALID"), null, null,
                    arguments, artifacts, revisions, AssistantPlanExecutionJson.canonical(payload));
        }

        exactKeys(payload, SKILL, "SKILL_DECISION_FIELDS_INVALID");
        JSONObject input = payload.getJSONObject("input");
        if (input == null) invalid("SKILL_INPUT_INVALID");
        Long skillVersionId = positive(payload, "skillVersionId", "SKILL_VERSION_INVALID");
        String packageDigest = payload.getString("packageDigest");
        if (packageDigest == null || !DIGEST.matcher(packageDigest).matches()) invalid("SKILL_DIGEST_INVALID");
        return new AssistantPlanExecutionDecision(protocol, decisionId, observedRun, observedPlan, action,
                operationIdentifier(payload.get("invocationId"), protocol, 128, "SKILL_INVOCATION_ID_INVALID"),
                operationIdentifier(payload.get("stableStepId"), protocol, 64, "SKILL_STABLE_STEP_INVALID"),
                attempt(payload), "SKILL", text(payload, "skillCode", 64, "SKILL_CODE_INVALID"),
                String.valueOf(skillVersionId), skillVersionId, packageDigest, input, List.of(), new JSONArray(),
                AssistantPlanExecutionJson.canonical(payload));
    }

    private int attempt(JSONObject payload)
    {
        Integer value = payload.getInteger("attemptNo");
        if (value == null || value < 1 || value > 1000) invalid("DECISION_ATTEMPT_INVALID");
        return value;
    }

    private Long positive(JSONObject value, String name, String code)
    {
        Long result = value.getLong(name);
        if (result == null || result < 1) invalid(code);
        return result;
    }

    private Long positiveOrZero(JSONObject value, String name, String code)
    {
        Long result = value.getLong(name);
        if (result == null || result < 0) invalid(code);
        return result;
    }

    private String text(JSONObject value, String name, int max, String code)
    {
        String result = value.getString(name);
        requiredText(result, max, code);
        return result;
    }

    private String identifier(String value, String code)
    {
        if (value == null || !ID.matcher(value).matches()) invalid(code);
        return value;
    }

    private String operationIdentifier(Object rawValue, String protocol, int maxLength, String code)
    {
        // Native discovery steps use server-generated namespaces. These are opaque database
        // identities, never filesystem paths; preserve the pinned plan identity during recovery.
        if (!(rawValue instanceof String)) invalid(code);
        String value = (String) rawValue;
        Pattern allowed = "assistant-loop/native-v1".equals(protocol) ? NATIVE_OPERATION_ID : ID;
        if (value.length() > maxLength || !allowed.matcher(value).matches()) invalid(code);
        return value;
    }

    private List<String> stringArray(JSONArray source, int maxItems, int maxLength, String code)
    {
        if (source == null || source.size() > maxItems) invalid(code);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < source.size(); index++)
        {
            Object item = source.get(index);
            if (!(item instanceof String text) || StrUtil.isBlank(text)
                    || text.length() > maxLength || !unique.add(text)) invalid(code);
        }
        return new ArrayList<>(unique);
    }

    private void exactKeys(JSONObject value, Set<String> expected, String code)
    {
        if (value == null || value.size() != expected.size() || !expected.containsAll(value.keySet())) invalid(code);
    }

    private void requiredText(String value, int max, String code)
    {
        if (StrUtil.isBlank(value) || value.length() > max) invalid(code);
    }

    private void invalid(String code)
    {
        throw new ServiceException(code);
    }
}
