package com.aid.assistant.skill;

import com.aid.skill.dto.SkillInvocationRequests;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Keeps the frozen method payload intact while deriving project identity from the session. */
public final class AssistantFixedSkillInputAdapter {
    public static final String CURRENT_REQUEST_SCHEMA = "assistant-method-request/v6";
    private AssistantFixedSkillInputAdapter() { }

    public static boolean hasCurrentRequestEnvelope(String prompt) {
        try {
            JSONObject value = JSON.parseObject(prompt);
            return value != null && CURRENT_REQUEST_SCHEMA.equals(value.getString("schemaVersion"));
        } catch (RuntimeException legacyPlainText) { return false; }
    }

    /** The host freezes this revision with approval; older approved calls keep their old payload. */
    public static SkillInvocationRequests.InvokeRequest adapt(String inputJson, Long projectId, Long episodeId,
            String originalRequestJson, JSONObject memory, Integer revision) {
        if (revision == null || revision == 5)
            return adapt(inputJson, projectId, episodeId, originalRequestJson, memory);
        try {
            if (revision != 6) throw new IllegalArgumentException("Unsupported method request revision");
            JSONObject original = JSON.parseObject(originalRequestJson);
            if (original == null || !(original.get("parts") instanceof JSONArray parts)
                    || parts.isEmpty() || parts.size() > 20) throw new IllegalArgumentException("Original user text is required");
            JSONArray current = new JSONArray();
            for (Object raw : parts) {
                if (!(raw instanceof JSONObject part) || !"TEXT".equals(part.getString("type"))
                        || !(part.get("text") instanceof String text) || text.isBlank())
                    throw new IllegalArgumentException("Invalid original user text");
                current.add(text);
            }
            if (memory != null) requireMemoryContext(memory);
            SkillInvocationRequests.InvokeRequest request = adapt(inputJson, projectId, episodeId);
            JSONObject prompt = new JSONObject();
            prompt.put("schemaVersion", CURRENT_REQUEST_SCHEMA);
            prompt.put("currentRequest", current);
            prompt.put("historicalRequests", memory == null ? new JSONArray() : memory.getJSONArray("historicalRequests"));
            prompt.put("acceptedInputs", memory == null ? new JSONArray() : memory.getJSONArray("acceptedInputs"));
            prompt.put("methodInput", JSON.parseObject(request.getPrompt()));
            if (memory != null && memory.containsKey("preparedContext"))
                prompt.put("conversationMemory", memory.getJSONObject("preparedContext"));
            if (memory != null && memory.containsKey("parentObservations"))
                prompt.put("parentObservations", memory.getJSONObject("parentObservations"));
            String serialized = prompt.toJSONString();
            if (serialized.length() > 100000) throw new IllegalArgumentException("Method input exceeds prompt capacity");
            request.setPrompt(serialized);
            return request;
        } catch (RuntimeException rejected) {
            throw new AssistantSkillPreparationRejectedException("SKILL_METHOD_INPUT_INVALID", rejected);
        }
    }

    /** Use the durable first-send user text, never an agent-supplied replacement for the user's goal. */
    public static SkillInvocationRequests.InvokeRequest adapt(String inputJson, Long projectId, Long episodeId,
                                                               String originalRequestJson) {
        return adapt(inputJson, projectId, episodeId, originalRequestJson, null);
    }

    /** Memory is server-frozen with the approved operation, never taken from methodInput. */
    public static SkillInvocationRequests.InvokeRequest adapt(String inputJson, Long projectId, Long episodeId,
                                                               String originalRequestJson, JSONObject memory) {
        try {
            JSONObject original = JSON.parseObject(originalRequestJson);
            if (original == null || !(original.get("parts") instanceof JSONArray parts)
                    || parts.isEmpty() || parts.size() > 20) {
                throw new IllegalArgumentException("Original user text is required");
            }
            List<String> texts = new ArrayList<>();
            if (memory != null) {
                requireMemoryContext(memory);
                texts.addAll(memory.getJSONArray("historicalRequests").toJavaList(String.class));
            }
            for (Object raw : parts) {
                if (!(raw instanceof JSONObject part) || !"TEXT".equals(part.getString("type"))
                        || !(part.get("text") instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("Invalid original user text");
                }
                texts.add(text);
            }
            if (memory != null) texts.addAll(memory.getJSONArray("acceptedInputs").toJavaList(String.class));
            SkillInvocationRequests.InvokeRequest request = adapt(inputJson, projectId, episodeId);
            JSONObject prompt = new JSONObject();
            prompt.put("userRequest", texts);
            prompt.put("methodInput", JSON.parseObject(request.getPrompt()));
            if (memory != null && memory.containsKey("preparedContext"))
                prompt.put("conversationMemory", memory.getJSONObject("preparedContext"));
            if (memory != null && memory.containsKey("parentObservations"))
                prompt.put("parentObservations", memory.getJSONObject("parentObservations"));
            String serialized = prompt.toJSONString();
            if (serialized.length() > 100000) throw new IllegalArgumentException("Method input exceeds prompt capacity");
            request.setPrompt(serialized);
            return request;
        } catch (RuntimeException rejected) {
            throw new AssistantSkillPreparationRejectedException("SKILL_METHOD_INPUT_INVALID", rejected);
        }
    }

    public static void requireMemoryContext(JSONObject memory) {
        if (memory == null || !memory.keySet().containsAll(java.util.Set.of("historicalRequests", "acceptedInputs"))
                || !java.util.Set.of("historicalRequests", "acceptedInputs", "preparedContext", "parentObservations")
                    .containsAll(memory.keySet()))
            throw new IllegalArgumentException("Invalid conversation memory context");
        if (memory.containsKey("preparedContext"))
            com.aid.context.PreparedConversationContextContract.require(memory.getJSONObject("preparedContext"));
        if (memory.containsKey("parentObservations"))
            com.aid.context.ParentToolObservationContract.require(memory.getJSONObject("parentObservations"));
        for (String field : java.util.List.of("historicalRequests", "acceptedInputs")) {
            if (!(memory.get(field) instanceof JSONArray values)
                    || values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank()))
                throw new IllegalArgumentException("Invalid conversation memory sources");
        }
    }

    public static SkillInvocationRequests.InvokeRequest adapt(String inputJson, Long projectId, Long episodeId) {
        try {
            JSONObject input = JSON.parseObject(inputJson);
            if (input == null) throw new IllegalArgumentException("Method input object required");
            String prompt = input.toJSONString();
            if (prompt.length() > 100000) throw new IllegalArgumentException("Method input exceeds prompt capacity");
            SkillInvocationRequests.InvokeRequest request = input.toJavaObject(SkillInvocationRequests.InvokeRequest.class);
            request.setPrompt(prompt);
            request.setProjectId(projectId);
            request.setEpisodeId(projectId == null ? null : episodeId);
            return request;
        } catch (RuntimeException rejected) {
            throw new AssistantSkillPreparationRejectedException("SKILL_METHOD_INPUT_INVALID", rejected);
        }
    }
}
