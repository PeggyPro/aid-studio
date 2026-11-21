package com.aid.runtime.assistant;

import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.alibaba.fastjson2.*;
import java.util.*;

/** Current authority accompanies the newest receipt, after historical decisions, without rewriting them. */
public final class NativeAssistantTurnState {
    public static final String VERSION = "native-turn-state/v1";
    public static final String BINDING_FIELD = "turnStateVersion";
    public static final String RECEIPT_FIELD = "currentTurnState";
    private NativeAssistantTurnState() { }

    public static List<TextMessageItem> attach(List<TextMessageItem> history, String bindingsJson) {
        if (bindingsJson == null || bindingsJson.isBlank()) return history;
        JSONObject bindings = JSON.parseObject(bindingsJson);
        if (!bindings.containsKey(BINDING_FIELD)) return history;
        if (!VERSION.equals(bindings.get(BINDING_FIELD))
                || !NativeAssistantBindingPolicy.V11.equals(bindings.getString("schemaVersion")))
            throw new IllegalArgumentException("NATIVE_TURN_STATE_VERSION_INVALID");
        history = NativeToolContractReceiptProjection.history(history, bindings);
        if (history.isEmpty() || !"tool".equals(history.get(history.size() - 1).getRole())) return history;
        JSONArray tools = bindings.getJSONArray("tools");
        if (tools == null || tools.size() > 256) throw new IllegalArgumentException("NATIVE_TURN_STATE_BINDINGS_INVALID");
        JSONArray names = new JSONArray(); Set<String> actions = new LinkedHashSet<>();
        for (Object raw : tools) {
            if (!(raw instanceof JSONObject tool) || !(tool.get("name") instanceof String name)
                    || name.isBlank() || name.length() > 64 || names.contains(name)
                    || !(tool.get("action") instanceof String action))
                throw new IllegalArgumentException("NATIVE_TURN_STATE_BINDINGS_INVALID");
            names.add(name); actions.add(action);
        }
        boolean executable = actions.contains("TOOL") || actions.contains("SKILL");
        JSONObject state = JSONObject.of("version", VERSION, "authority", "SERVER_CURRENT_TURN",
                "runId", bindings.get("runId"), "runRevision", bindings.get("observedRunRevision"),
                "planRevision", bindings.get("observedPlanRevision"), "availableFunctionNames", names,
                "availableActions", new JSONArray(actions), "hasExecutableCall", executable,
                "completedPlanCanBeDelivered", actions.contains("COMPLETE"),
                "additionalOperationsRequireNewPlan", !executable && actions.contains("PLAN") && actions.contains("COMPLETE"),
                "historicalCallsAreNotCurrentPermissions", true);
        TextMessageItem last = history.get(history.size() - 1);
        JSONObject receipt = JSON.parseObject(last.getContent());
        if (receipt == null || receipt.containsKey(RECEIPT_FIELD))
            throw new IllegalArgumentException("NATIVE_TURN_STATE_RECEIPT_INVALID");
        receipt.put(RECEIPT_FIELD, state);
        TextMessageItem enriched = JSON.parseObject(JSON.toJSONString(last), TextMessageItem.class);
        enriched.setContent(AssistantMainInputContract.serialize(receipt));
        List<TextMessageItem> result = new ArrayList<>(history);
        result.set(result.size() - 1, enriched);
        return List.copyOf(result);
    }
}
