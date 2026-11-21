package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantSkillResultTextResolver;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparationRejectedException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Freezes exact business text before approval; execute/reconcile never fetch or regenerate the source. */
@Component
@RequiredArgsConstructor
public class CanvasSkillResultTextPreparation {
    private static final String VERSION = "canvas-skill-result-text/v1";
    private static final Set<String> INPUT = Set.of("workspaceId", "title", "sourceStepId", "resultPointer", "x", "y");
    private static final Set<String> NODE = Set.of("workspaceId", "title", "textContent", "x", "y");
    private static final Set<String> SOURCE = Set.of("sourceRunId", "sourceStepId", "skillRunId", "resultPointer",
            "sourceOutputDigest", "textDigest");
    private final AssistantSkillResultTextResolver resolver;

    public String prepare(RuntimeToolInvocation invocation) {
        JSONObject arguments;
        Long sourceStepId;
        try {
            arguments = object(invocation.getArgumentsJson());
            require(INPUT.containsAll(arguments.keySet()), "INPUT_INVALID");
            sourceStepId = id(arguments.get("sourceStepId"));
            require(arguments.get("resultPointer") instanceof String, "INPUT_INVALID");
        } catch (ServiceException invalid) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, invalid);
        }
        var resolved = resolver.resolveForPreparation(invocation, sourceStepId,
                arguments.getString("resultPointer"), 65_536);
        JSONObject node = new JSONObject(arguments);
        node.remove("sourceStepId"); node.remove("resultPointer");
        node.put("textContent", resolved.text());
        JSONObject source = new JSONObject();
        source.put("sourceRunId", String.valueOf(resolved.sourceRunId()));
        source.put("sourceStepId", String.valueOf(resolved.sourceStepId()));
        source.put("skillRunId", String.valueOf(resolved.skillRunId()));
        source.put("resultPointer", resolved.resultPointer());
        source.put("sourceOutputDigest", resolved.sourceOutputDigest());
        source.put("textDigest", resolved.textDigest());
        JSONObject payload = new JSONObject();
        payload.put("schemaVersion", VERSION); payload.put("node", node); payload.put("source", source);
        String json = CanvasAssistantOperationCanonicalizer.explicitNullJson(payload);
        try { nodeArguments(json, invocation.getArgumentsJson()); }
        catch (ServiceException invalid) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, invalid);
        }
        return json;
    }

    public static JSONObject nodeArguments(String frozenJson, String originalJson) {
        JSONObject payload = object(frozenJson), original = object(originalJson);
        require(payload.keySet().equals(Set.of("schemaVersion", "node", "source"))
                && VERSION.equals(payload.getString("schemaVersion"))
                && INPUT.containsAll(original.keySet()), "FROZEN_CHANGED");
        JSONObject node = payload.getJSONObject("node"), source = payload.getJSONObject("source");
        require(node != null && NODE.containsAll(node.keySet()) && source != null
                && source.keySet().equals(SOURCE), "FROZEN_CHANGED");
        id(node.get("workspaceId")); id(source.get("sourceRunId")); id(source.get("skillRunId"));
        require(Objects.equals(id(source.get("sourceStepId")), id(original.get("sourceStepId")))
                && original.get("resultPointer") instanceof String
                && Objects.equals(source.get("resultPointer"), original.get("resultPointer"))
                && source.get("sourceOutputDigest") instanceof String digest && digest.matches("[0-9a-f]{64}")
                && node.get("title") instanceof String title && title.length() <= 256
                && node.get("textContent") instanceof String text && !text.isBlank() && text.length() <= 65_536
                && Objects.equals(SecureUtil.sha256(text), source.get("textDigest")), "FROZEN_CHANGED");
        for (String field : Set.of("workspaceId", "title", "x", "y")) {
            require(Objects.equals(node.get(field), original.get(field)), "FROZEN_CHANGED");
        }
        return node;
    }

    private static Long id(Object value) {
        require(value instanceof String text && text.matches("[1-9][0-9]{0,18}"), "INPUT_INVALID");
        try { return Long.valueOf((String) value); }
        catch (NumberFormatException invalid) { throw new ServiceException("CANVAS_RESULT_TEXT_INPUT_INVALID"); }
    }

    private static JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            require(value != null, "FROZEN_CHANGED"); return value;
        } catch (RuntimeException invalid) {
            throw new ServiceException("CANVAS_RESULT_TEXT_FROZEN_CHANGED");
        }
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new ServiceException("CANVAS_RESULT_TEXT_" + code);
    }
}
