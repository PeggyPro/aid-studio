package com.aid.canvas.runtime;

import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.service.CanvasProjectService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical operation identities and request digests shared by Canvas writers and receipt verifiers. */
public final class CanvasAssistantOperationCanonicalizer {
    private CanvasAssistantOperationCanonicalizer() { }

    public static String toolOperationId(String prefix, Long runId, String idempotencyKey) {
        return prefix + "_" + runId + "_" + sha256(idempotencyKey).substring(0, 16);
    }

    public static String toolCreateObjectId(Long runId, String toolCode, String idempotencyKey) {
        return "asst_" + sha256(runId + "\n" + toolCode + "\n" + idempotencyKey).substring(0, 24);
    }

    public static String candidateAdoptionOperationId(Long runId, Long resultId, String idempotencyKey) {
        return "asst_adopt_" + runId + "_"
                + sha256(resultId + "\n" + idempotencyKey).substring(0, 16);
    }

    public static String candidateAdoptionRequestHash(Long runId, Long userId, Long workspaceId,
                                                       String objectId, Long objectVersion, Long resultId,
                                                       String idempotencyKey) {
        return sha256(runId + "\n" + userId + "\n" + workspaceId + "\n" + objectId + "\n"
                + objectVersion + "\n" + resultId + "\n" + idempotencyKey);
    }

    public static String artifactAdoptionOperationId(Long artifactId, String clientRequestId) {
        return "art_" + sha256(artifactId + "\n" + clientRequestId).substring(0, 32);
    }

    public static Map<String, Object> textNodePayload(JSONObject arguments) {
        String title = arguments == null ? null : arguments.getString("title");
        String textContent = arguments == null ? null : arguments.getString("textContent");
        if (title == null || title.length() > 256 || textContent == null || textContent.length() > 65_536) {
            throw new ServiceException("Canvas assistant text payload is invalid");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeType", "TEXT");
        payload.put("title", title);
        payload.put("position", ordered("x", number(arguments, "x", 160D),
                "y", number(arguments, "y", 180D)));
        if (!arguments.containsKey("x") && !arguments.containsKey("y"))
            payload.put(CanvasAssistantNodePlacement.AUTO, true);
        payload.put("size", ordered("width", 360, "height", 260));
        payload.put("prompt", "");
        payload.put("textContent", textContent);
        payload.put("parameters", Map.of());
        payload.put("resourceIds", List.of());
        payload.put("inputPorts", List.of());
        payload.put("metadata", ordered("studioData", ordered("kind", "text", "title", title,
                "documentContent", textContent, "prompt", "")));
        return payload;
    }

    public static Map<String, Object> mediaNodePayload(JSONObject arguments, String nodeType, String kind,
                                                        String mediaKind, int width, int height) {
        String title = arguments == null ? null : arguments.getString("title");
        String prompt = arguments == null ? null : arguments.getString("prompt");
        if (title == null || title.length() > 256 || prompt == null || prompt.length() > 65_536) {
            throw new ServiceException("Canvas assistant media payload is invalid");
        }
        Map<String, Object> studioData = new LinkedHashMap<>();
        studioData.put("kind", kind);
        studioData.put("title", title);
        studioData.put("prompt", prompt);
        studioData.put("mediaKind", mediaKind);
        String model = CanvasAssistantGenerationSettings.model(arguments);
        Map<String, Object> settings = CanvasAssistantGenerationSettings.settings(arguments);
        if (model != null) studioData.put("model", model);
        if (!settings.isEmpty()) studioData.put("generationOptions", settings);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeType", nodeType);
        payload.put("title", title);
        payload.put("position", ordered("x", number(arguments, "x", 160D),
                "y", number(arguments, "y", 180D)));
        if (!arguments.containsKey("x") && !arguments.containsKey("y"))
            payload.put(CanvasAssistantNodePlacement.AUTO, true);
        payload.put("size", ordered("width", width, "height", height));
        payload.put("prompt", prompt);
        payload.put("parameters", settings);
        payload.put("resourceIds", List.of());
        payload.put("inputPorts", List.of());
        payload.put("metadata", ordered("studioData", studioData));
        return payload;
    }

    public static String assistantMutationRequestHash(Long workspaceId, Long userId,
                                                       CanvasProjectService.AssistantNodeMutation input,
                                                       List<CanvasRequests.Mutation> mutations) {
        return assistantMutationRequestHash(workspaceId, userId, input.runId(), input.sourceObjectId(),
                input.sourceObjectVersion(), input.expectedWorkspaceRevision(), input.action(), mutations);
    }

    public static String assistantMutationRequestHash(Long workspaceId, Long userId, Long runId,
                                                       String sourceObjectId, Long sourceObjectVersion,
                                                       Long expectedWorkspaceRevision, String action,
                                                       List<CanvasRequests.Mutation> mutations) {
        return sha256(JSON.toJSONString(ordered("workspaceId", workspaceId, "actorUserId", userId,
                "sourceType", "ASSISTANT_RUNTIME", "sourceRunId", runId,
                "sourceObjectId", sourceObjectId, "sourceObjectVersion", sourceObjectVersion,
                "expectedWorkspaceRevision", expectedWorkspaceRevision,
                "action", action, "mutationHash", mutationRequestHash(mutations))));
    }

    /** Reconstructs a legacy create hash only from the exact stored, semantically identical receipt. */
    public static String assistantCreateReceiptRequestHash(Long workspaceId, Long userId, Long runId,
                                                           Long expectedWorkspaceRevision, String action,
                                                           String operationId, CanvasRequests.Mutation expected,
                                                           String receiptJson) {
        try {
            JSONObject receipt = JSON.parseObject(receiptJson);
            var objects = receipt == null ? null : receipt.getJSONArray("objects");
            if (workspaceId == null || userId == null || runId == null
                    || expectedWorkspaceRevision == null || expectedWorkspaceRevision < 0
                    || expectedWorkspaceRevision == Long.MAX_VALUE
                    || action == null || !java.util.Set.of("CREATE_TEXT", "CREATE_IMAGE", "CREATE_VIDEO",
                            "CREATE_MUSIC", "CREATE_AUDIO").contains(action)
                    || operationId == null || expected == null || !"NODE".equals(expected.getObjectType())
                    || !"UPSERT".equals(expected.getAction()) || !Long.valueOf(0L).equals(expected.getExpectedVersion())
                    || expected.getPayload() == null || objects == null || objects.size() != 1
                    || !operationId.equals(receipt.getString("receiptId"))
                    || !"ASSISTANT_RUNTIME".equals(receipt.getString("sourceType"))
                    || !String.valueOf(runId).equals(receipt.getString("sourceRunId"))
                    || receipt.get("sourceObjectId") != null || receipt.get("sourceObjectVersion") != null
                    || !Long.valueOf(expectedWorkspaceRevision + 1L).equals(receipt.getLong("revision"))
                    || receipt.getLong("eventSeq") == null || receipt.getLong("eventSeq") < 1) {
                throw new ServiceException("Canvas legacy create receipt is invalid");
            }
            JSONObject object = objects.getJSONObject(0);
            JSONObject content = object == null ? null : object.getJSONObject("content");
            JSONObject expectedContent = JSON.parseObject(explicitNullJson(expected.getPayload()));
            // Historic creations predate automatic placement. Only the host marker is absent;
            // coordinates and all business fields must still match the original durable receipt.
            expectedContent.remove(CanvasAssistantNodePlacement.AUTO);
            if (object == null || !java.util.Objects.equals(expected.getObjectId(), object.getString("objectId"))
                    || !"NODE".equals(object.getString("objectType"))
                    || !Long.valueOf(1L).equals(object.getLong("objectVersion"))
                    || !Boolean.FALSE.equals(object.getBoolean("deleted")) || content == null
                    || !semanticJson(expectedContent).equals(semanticJson(content))) {
                throw new ServiceException("Canvas legacy create receipt is invalid");
            }
            CanvasRequests.Mutation stored = new CanvasRequests.Mutation();
            stored.setObjectId(expected.getObjectId()); stored.setObjectType("NODE");
            stored.setAction("UPSERT"); stored.setExpectedVersion(0L); stored.setPayload(content);
            return assistantMutationRequestHash(workspaceId, userId, runId, null, null,
                    expectedWorkspaceRevision, action, List.of(stored));
        } catch (RuntimeException invalid) {
            throw new ServiceException("Canvas legacy create receipt is invalid");
        }
    }

    private static String semanticJson(Object value) {
        return JSON.toJSONString(orderedJson(JSON.parse(JSON.toJSONString(value))));
    }

    private static Object orderedJson(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> result = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet())
                result.put((String) entry.getKey(), orderedJson(entry.getValue()));
            return result;
        }
        if (value instanceof List<?> values) return values.stream().map(CanvasAssistantOperationCanonicalizer::orderedJson).toList();
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    public static String mutationRequestHash(List<CanvasRequests.Mutation> mutations) {
        return sha256(explicitNullJson(canonicalMutations(mutations)));
    }

    /** Pre-explicit-null identity, used only to replay an already persisted generic operation receipt. */
    public static String legacyMutationRequestHash(List<CanvasRequests.Mutation> mutations) {
        return sha256(JSON.toJSONString(canonicalMutations(mutations)));
    }

    public static boolean mutationsContainExplicitNull(List<CanvasRequests.Mutation> mutations) {
        if (mutations == null) return false;
        for (CanvasRequests.Mutation mutation : mutations) {
            if (mutation != null && containsExplicitNull(mutation.getPayload())) return true;
        }
        return false;
    }

    public static String explicitNullJson(Object value) {
        return JSON.toJSONString(value, JSONWriter.Feature.WriteMapNullValue);
    }

    private static List<Map<String, Object>> canonicalMutations(List<CanvasRequests.Mutation> mutations) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (CanvasRequests.Mutation mutation : mutations) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "objectId", mutation.getObjectId());
            putIfPresent(item, "objectType", mutation.getObjectType());
            putIfPresent(item, "action", mutation.getAction());
            putIfPresent(item, "expectedVersion", mutation.getExpectedVersion());
            putIfPresent(item, "payload", mutation.getPayload());
            canonical.add(item);
        }
        return canonical;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        // The pre-explicit-null serializer omitted null mutation envelope fields. Keep those bytes stable for
        // historical DELETE and nullable-version receipts; only nested nulls inside a non-null payload are new.
        if (value != null) target.put(key, value);
    }

    private static boolean containsExplicitNull(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null || containsExplicitNull(entry.getValue())) return true;
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (containsExplicitNull(item)) return true;
        }
        return false;
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static double number(JSONObject arguments, String key, double fallback) {
        Object value = arguments.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new ServiceException("Canvas assistant position is invalid");
        }
        return number.doubleValue();
    }
}
