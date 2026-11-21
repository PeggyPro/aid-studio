package com.aid.media.provider;

import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** 统一保存文本上游调用证据并决定失败任务是否结算。 */
public final class TextFailureBillingPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OUTCOME_FIELD = "providerCallOutcome";
    private static final String HTTP_STATUS_FIELD = "providerHttpStatus";
    private static final String OUTCOME_NOT_SENT = "NOT_SENT";
    private static final String OUTCOME_REJECTED_WITHOUT_USAGE = "REJECTED_WITHOUT_USAGE";
    private static final String OUTCOME_USAGE_OBSERVED = "USAGE_OBSERVED";
    private static final String OUTCOME_HTTP_FAILURE_USAGE_UNKNOWN = "HTTP_FAILURE_USAGE_UNKNOWN";
    private static final String OUTCOME_COMPLETED_WITHOUT_USAGE = "COMPLETED_WITHOUT_USAGE";

    private TextFailureBillingPolicy() {
    }

    /** 构造上游 HTTP 最终拒绝异常，供流式回调保留机器可判定证据。 */
    public static ServiceException httpFailure(int httpStatus, String safeMessage,
                                               Map<String, Object> usage) {
        return httpFailureEvidence(httpStatus, safeMessage, usage).asException();
    }

    /** 同步和流式入口共用的 HTTP 失败证据。 */
    public static TextProviderFailureEvidence httpFailureEvidence(
            int httpStatus, String safeMessage, Map<String, Object> usage) {
        return httpFailureEvidence(httpStatus, safeMessage, usage, null, true);
    }

    /** 正文不完整时不能据缺失 usage 推断未计费，必须保留为结果未知。 */
    public static TextProviderFailureEvidence httpFailureEvidence(
            int httpStatus, String safeMessage, Map<String, Object> usage,
            boolean responseComplete) {
        return httpFailureEvidence(httpStatus, safeMessage, usage, null, responseComplete);
    }

    /** 在供应商专属错误快照上追加通用 HTTP/用量证据。 */
    public static TextProviderFailureEvidence httpFailureEvidence(
            int httpStatus, String safeMessage, Map<String, Object> usage,
            String preferredSnapshot) {
        return httpFailureEvidence(httpStatus, safeMessage, usage, preferredSnapshot, true);
    }

    /** 在供应商专属快照上追加正文完整性证据；完整 usage 始终优先。 */
    public static TextProviderFailureEvidence httpFailureEvidence(
            int httpStatus, String safeMessage, Map<String, Object> usage,
            String preferredSnapshot, boolean responseComplete) {
        return new TextProviderFailureEvidence(safeMessage, httpStatus,
                httpFailureSnapshot(httpStatus, safeMessage, usage, preferredSnapshot,
                        responseComplete), usage);
    }

    /** 构造上游 HTTP 失败快照；仅标准且语义明确的最终拒绝状态在无用量时退款。 */
    public static String httpFailureSnapshot(int httpStatus, String safeMessage,
                                             Map<String, Object> usage) {
        return httpFailureSnapshot(httpStatus, safeMessage, usage, null, true);
    }

    /** 在供应商专属错误快照上追加通用 HTTP/用量证据，不丢失恢复动作等扩展字段。 */
    public static String httpFailureSnapshot(int httpStatus, String safeMessage,
                                             Map<String, Object> usage, String preferredSnapshot) {
        return httpFailureSnapshot(httpStatus, safeMessage, usage, preferredSnapshot, true);
    }

    /** 正文截断时只保存状态，不把未观察到 usage 当成无 usage。 */
    public static String httpFailureSnapshot(int httpStatus, String safeMessage,
                                             Map<String, Object> usage, String preferredSnapshot,
                                             boolean responseComplete) {
        TaskErrorResult error = ErrorNormalizer.classify(null, null, httpStatus, safeMessage);
        String snapshot = TaskErrorSnapshot.read(preferredSnapshot) == null
                ? TaskErrorSnapshot.write(error) : mergeErrorSnapshot(preferredSnapshot, error);
        snapshot = withCompletionEvidence(snapshot, responseComplete, false);
        if (ProviderUsageSupport.hasAnyProviderUsage(usage)) {
            return withObservedUsage(snapshot, usage, httpStatus);
        }
        if (responseComplete && isFinalHttpRejection(httpStatus)) {
            return withOutcome(snapshot, OUTCOME_REJECTED_WITHOUT_USAGE, httpStatus);
        }
        return withOutcome(snapshot, OUTCOME_HTTP_FAILURE_USAGE_UNKNOWN, httpStatus);
    }

    /** 构造本地未发送请求的安全错误快照。 */
    public static String notSentSnapshot(String message) {
        return withOutcome(TaskErrorSnapshot.write(ErrorNormalizer.classifyByMessage(message)),
                OUTCOME_NOT_SENT, null);
    }

    /** 构造本地未发送请求异常。 */
    public static ServiceException notSent(String message) {
        return notSentEvidence(message).asException();
    }

    /** 构造本地未发送请求的统一失败证据。 */
    public static TextProviderFailureEvidence notSentEvidence(String message) {
        return new TextProviderFailureEvidence(message, null, notSentSnapshot(message), null);
    }

    /** Only a parsed, normal model termination may prove an empty text result. */
    public static TextProviderFailureEvidence completedEmptyResponse(Map<String, Object> usage) {
        String message = "模型未返回正文";
        TaskErrorResult error = TaskErrorResult.of(TaskErrorCode.RESULT_INVALID);
        error.setUserMessage(message);
        return completedFailure(error, usage, "MODEL_OUTPUT_EMPTY");
    }

    /** A recognized model finish error is definitive, unlike transport loss. */
    public static TextProviderFailureEvidence completedResponseFailure(String message, Map<String, Object> usage) {
        TaskErrorResult error = ErrorNormalizer.classifyByMessage(message);
        if (TaskErrorCode.UNKNOWN.name().equals(error.getErrorCode())) error = TaskErrorResult.of(TaskErrorCode.RESULT_INVALID);
        error.setUserMessage(ProviderErrorSanitizer.safeMessage(message, "模型响应无效"));
        return completedFailure(error, usage, null);
    }

    /** A parsed complete response failed the adapter's result contract before any tool execution. */
    public static TextProviderFailureEvidence completedInvalidResponse(String message, String snapshot,
                                                                       Map<String, Object> usage) {
        TaskErrorResult error = TaskErrorSnapshot.read(snapshot);
        if (error == null) error = TaskErrorResult.of(TaskErrorCode.RESULT_INVALID);
        error.setUserMessage(ProviderErrorSanitizer.safeMessage(message, "模型响应格式无效"));
        return completedFailure(error, usage, "MODEL_OUTPUT_INVALID");
    }

    private static TextProviderFailureEvidence completedFailure(TaskErrorResult error,
                                                                 Map<String, Object> usage, String reason) {
        String snapshot = withCompletionEvidence(TaskErrorSnapshot.write(error), true, true);
        ObjectNode proof = (ObjectNode) readObject(snapshot);
        if (reason != null) proof.put("textModelFailureReason", reason);
        snapshot = proof.toString();
        snapshot = ProviderUsageSupport.hasAnyProviderUsage(usage)
                ? withObservedUsage(snapshot, usage)
                : withOutcome(snapshot, OUTCOME_COMPLETED_WITHOUT_USAGE, null);
        return new TextProviderFailureEvidence(error.getUserMessage(), null, snapshot, usage);
    }

    /** Transport closure or usage alone never establishes a terminal failure. */
    public static boolean isConfirmedTerminalFailure(String snapshot) {
        JsonNode proof = readObject(snapshot);
        if (proof == null) return false;
        if (OUTCOME_NOT_SENT.equals(proof.path(OUTCOME_FIELD).asText())) return true;
        if (!proof.path("providerResponseComplete").asBoolean(false)) return false;
        return proof.path("providerTextCompletionConfirmed").asBoolean(false)
                || isFinalHttpRejection(proof.path(HTTP_STATUS_FIELD).asInt(-1));
    }

    /** Keep only normalized error fields and bounded machine evidence in the receipt journal. */
    public static String sanitizedFailureSnapshot(String snapshot) {
        TaskErrorResult error = TaskErrorSnapshot.read(snapshot);
        JsonNode source = readObject(snapshot);
        if (error == null || source == null) return null;
        ObjectNode target = (ObjectNode) readObject(TaskErrorSnapshot.write(error));
        String outcome = source.path(OUTCOME_FIELD).asText();
        if (java.util.Set.of(OUTCOME_NOT_SENT, OUTCOME_REJECTED_WITHOUT_USAGE,
                OUTCOME_USAGE_OBSERVED, OUTCOME_HTTP_FAILURE_USAGE_UNKNOWN,
                OUTCOME_COMPLETED_WITHOUT_USAGE).contains(outcome)) target.put(OUTCOME_FIELD, outcome);
        int status = source.path(HTTP_STATUS_FIELD).asInt(-1);
        if (status >= 100 && status <= 599) target.put(HTTP_STATUS_FIELD, status);
        for (String field : new String[]{"providerResponseComplete", "providerTextCompletionConfirmed"})
            if (source.path(field).isBoolean()) target.put(field, source.path(field).booleanValue());
        String reason = source.path("textModelFailureReason").asText();
        if (java.util.Set.of("MODEL_OUTPUT_EMPTY", "MODEL_OUTPUT_INVALID").contains(reason))
            target.put("textModelFailureReason", reason);
        Map<String, Object> usage = observedUsage(snapshot);
        if (!usage.isEmpty()) target.set("observedProviderUsage", MAPPER.valueToTree(usage));
        JsonNode response = source.path("providerHttpResponse");
        if (response.isObject()) {
            ObjectNode metadata = target.putObject("providerHttpResponse");
            int http = response.path("status").asInt(-1);
            if (http >= 100 && http <= 599) metadata.put("status", http);
            for (String name : new String[]{"protocol", "x-request-id", "request-id", "cf-ray"}) {
                String value = response.path(name).asText("");
                if (value.matches("[a-zA-Z0-9_.:/-]{1,128}")) metadata.put(name, value);
            }
        }
        return target.toString();
    }

    /** Both snapshots belong to the same task; observed usage must survive replay and normalization. */
    public static String mergeFailureEvidence(String existing, String incoming) {
        String safeIncoming = sanitizedFailureSnapshot(incoming);
        if (safeIncoming == null) return existing;
        String safeExisting = sanitizedFailureSnapshot(existing);
        if (safeExisting == null) return safeIncoming;
        ObjectNode target = (ObjectNode) readObject(safeExisting);
        readObject(safeIncoming).fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
        Map<String, Object> usage = ProviderUsageSupport.merge(observedUsage(safeExisting), observedUsage(safeIncoming));
        return usage.isEmpty() ? target.toString() : withObservedUsage(target.toString(), usage);
    }

    private static String withCompletionEvidence(String snapshot, boolean responseComplete,
                                                  boolean textCompletionConfirmed) {
        JsonNode value = readObject(snapshot);
        if (!(value instanceof ObjectNode object)) return snapshot;
        object.put("providerResponseComplete", responseComplete);
        object.put("providerTextCompletionConfirmed", textCompletionConfirmed);
        if (!textCompletionConfirmed) object.remove("textModelFailureReason");
        return object.toString();
    }

    /** 从异常链恢复完整调用证据，避免重新序列化错误时丢失扩展字段。 */
    public static String snapshotFrom(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ServiceException serviceException
                    && TaskErrorSnapshot.read(serviceException.getTaskErrorJson()) != null) {
                return serviceException.getTaskErrorJson();
            }
            current = current.getCause();
        }
        return null;
    }

    /** 已观察到任何真实 token 字段时持久化用量证据，并撤销旧的无用量拒绝标记。 */
    public static String withObservedUsage(String snapshot, Map<String, Object> usage) {
        return withObservedUsage(snapshot, usage, null);
    }

    private static String withObservedUsage(String snapshot, Map<String, Object> usage,
                                            Integer httpStatus) {
        if (!ProviderUsageSupport.hasAnyProviderUsage(usage)) {
            return snapshot;
        }
        Map<String, Object> actual = ProviderUsageSupport.actualUsage(
                ProviderUsageSupport.merge(observedUsage(snapshot), usage));
        String migrated = TokenDanceResponseMapper.withObservedUsage(snapshot, actual);
        String result = withOutcome(migrated, OUTCOME_USAGE_OBSERVED, httpStatus);
        JsonNode node = readObject(result);
        if (node instanceof ObjectNode object) {
            object.set("observedProviderUsage", MAPPER.valueToTree(actual));
            return object.toString();
        }
        return result;
    }

    /** 恢复已经持久化的真实用量；单独的“已观察”标记不等于可计价用量。 */
    public static Map<String, Object> observedUsage(String snapshot) {
        JsonNode node = readObject(snapshot);
        if (node == null || !node.path("observedProviderUsage").isObject()) return Map.of();
        Map<String, Object> values = MAPPER.convertValue(node.get("observedProviderUsage"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return ProviderUsageSupport.actualUsage(values);
    }

    /** 成功结果才向用户结算；失败调用的 usage 留作供应商成本审计，原冻结款退还。 */
    public static boolean shouldSettle(boolean businessSucceeded, boolean providerCallStarted,
                                       String protocol, String snapshot,
                                       Map<String, Object> usage) {
        return businessSucceeded;
    }

    /** 父任务聚合时判定子调用是否可能产生上游费用。 */
    public static boolean isBillableProviderCall(boolean providerCallStarted, String protocol,
                                                 String snapshot) {
        return shouldSettle(false, providerCallStarted, protocol, snapshot, null);
    }

    /** 合并标准错误展示字段，同时保留已有调用证据和供应商扩展字段。 */
    public static String mergeErrorSnapshot(String existingSnapshot, TaskErrorResult error) {
        JsonNode existing = readObject(existingSnapshot);
        JsonNode normalized = readObject(TaskErrorSnapshot.write(error));
        if (!(existing instanceof ObjectNode target) || !(normalized instanceof ObjectNode source)) {
            return TaskErrorSnapshot.write(error);
        }
        source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
        return target.toString();
    }

    /** 判断错误快照是否确认请求未发送或被上游明确无用量拒绝。 */
    public static boolean isRejectedWithoutUsage(String protocol, String snapshot,
                                                 Map<String, Object> usage) {
        if (ProviderUsageSupport.hasAnyProviderUsage(usage) || hasOutcome(snapshot, OUTCOME_USAGE_OBSERVED)) {
            return false;
        }
        return hasOutcome(snapshot, OUTCOME_NOT_SENT)
                || hasOutcome(snapshot, OUTCOME_REJECTED_WITHOUT_USAGE)
                || hasOutcome(snapshot, OUTCOME_COMPLETED_WITHOUT_USAGE) && isConfirmedTerminalFailure(snapshot)
                || TokenDanceResponseMapper.isConfirmedRejection(protocol, snapshot);
    }

    private static boolean isFinalHttpRejection(int httpStatus) {
        // 完整的文本 HTTP 503 响应表示本次分析未交付，可进入有界重试；真实 usage 始终优先。
        // 408、499、截断响应和供应商私有 4xx 仍可能代表断链或结果未知。
        return switch (httpStatus) {
            case 400, 401, 402, 403, 404, 405, 406, 407, 409, 410, 411, 412,
                    413, 414, 415, 416, 417, 421, 422, 423, 424, 425, 426,
                    428, 429, 431, 451, 503 -> true;
            default -> false;
        };
    }

    private static boolean hasOutcome(String snapshot, String expected) {
        JsonNode node = readObject(snapshot);
        return node != null && expected.equals(node.path(OUTCOME_FIELD).asText());
    }

    private static String withOutcome(String snapshot, String outcome, Integer httpStatus) {
        JsonNode node = readObject(snapshot);
        if (!(node instanceof ObjectNode object)) {
            return snapshot;
        }
        object.put(OUTCOME_FIELD, outcome);
        if (httpStatus != null) {
            object.put(HTTP_STATUS_FIELD, httpStatus);
        }
        return object.toString();
    }

    private static JsonNode readObject(String snapshot) {
        if (snapshot == null || TaskErrorSnapshot.read(snapshot) == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(snapshot);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
