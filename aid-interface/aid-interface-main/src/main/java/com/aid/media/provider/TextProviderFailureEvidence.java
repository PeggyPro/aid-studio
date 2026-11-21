package com.aid.media.provider;

import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文本供应商失败的安全文案、HTTP 状态、错误快照和用量证据。 */
public record TextProviderFailureEvidence(String message,
                                          Integer httpStatus,
                                          String errorDetailJson,
                                          Map<String, Object> usage) {

    public TextProviderFailureEvidence {
        message = message == null || message.isBlank() ? "上游请求失败" : message;
        usage = usage == null || usage.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(usage));
    }

    /** 兼容仍消费异常的回调，同时完整保留机器可判定的失败快照。 */
    public ServiceException asException() {
        ServiceException exception = httpStatus == null
                ? new ServiceException(message)
                : new ServiceException(message, httpStatus);
        String snapshot = TextFailureBillingPolicy.withObservedUsage(errorDetailJson, usage);
        if (TaskErrorSnapshot.read(snapshot) != null) {
            exception.setTaskErrorJson(snapshot);
        }
        return exception;
    }

    /** Keep bounded correlation metadata for a received HTTP failure, without headers or bodies. */
    public TextProviderFailureEvidence withResponseMetadata(java.net.http.HttpResponse<?> response) {
        if (response == null || TaskErrorSnapshot.read(errorDetailJson) == null) return this;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(errorDetailJson);
            var metadata = root.putObject("providerHttpResponse");
            metadata.put("protocol", response.version().name());
            metadata.put("status", response.statusCode());
            for (String name : new String[] {"x-request-id", "request-id", "cf-ray"})
                response.headers().firstValue(name).filter(value -> value.matches("[a-zA-Z0-9_.:/-]{1,128}"))
                        .ifPresent(value -> metadata.put(name, value));
            return new TextProviderFailureEvidence(message, httpStatus, mapper.writeValueAsString(root), usage);
        } catch (java.io.IOException invalid) {
            return this;
        }
    }

    /** 同步调用使用与流式回调相同的失败证据。 */
    public ProviderSubmitResult toSubmitResult() {
        String snapshot = TextFailureBillingPolicy.withObservedUsage(errorDetailJson, usage);
        var error = TaskErrorSnapshot.read(snapshot);
        boolean confirmed = error != null && TextFailureBillingPolicy.isConfirmedTerminalFailure(snapshot);
        return ProviderSubmitResult.builder()
                .rawResponse(message)
                .errorDetailJson(snapshot)
                .usage(usage)
                .confirmedFailureCode(confirmed ? error.getErrorCode() : null)
                .submissionHttpStatus(httpStatus)
                .submissionErrorMessage(message)
                .build();
    }
}
