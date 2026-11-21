package com.aid.media.provider;

import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;

/** Submission response classification shared by adapters; never use for polling responses. */
public final class ProviderSubmissionFailures {
    private ProviderSubmissionFailures() { }

    /** Use only for a guarded preparation block which has not entered model HTTP transport. */
    public static ProviderSubmitResult localPreparationFailure(Throwable failure) {
        var error = ErrorNormalizer.normalize(failure);
        return ProviderSubmitResult.builder().confirmedFailureCode("LOCAL_REQUEST_NOT_SENT")
                .submissionErrorMessage(error.getUserMessage())
                .errorDetailJson(TaskErrorSnapshot.write(error)).build();
    }

    /** Only machine-readable evidence may override an entered-provider uncertainty marker. */
    public static boolean definitelyRejected(String protocol, Throwable failure) {
        return TextFailureBillingPolicy.isRejectedWithoutUsage(protocol,
                TextFailureBillingPolicy.snapshotFrom(failure), null);
    }

    /** A local guard executed before any transport I/O; never infer this from an error message. */
    public static com.aid.common.exception.ServiceException notSent(String message) {
        return TextFailureBillingPolicy.notSent(message);
    }

    public static ProviderSubmitResult httpResponse(int status, String body, String model) {
        if (status < 300 || status > 599) throw new IllegalArgumentException("Expected unsuccessful HTTP response");
        String message = ProviderErrorSanitizer.fromHttp(status, body);
        if (message.equals("HTTP " + status))
            message += ": " + ProviderErrorSanitizer.safeMessage(body, "上游请求失败");
        var response = ProviderResponseHelper.readTree(body);
        String taskId = response == null ? null : ProviderResponseHelper.readText(response,
                "task_id", "video_id", "data.task_id", "data.video_id");
        boolean modelUnavailable = response != null && "model_not_found".equalsIgnoreCase(
                ProviderResponseHelper.readText(response, "error.code", "data.error.code", "code"));
        var data = response == null ? null : response.path("data");
        boolean hasResult = response != null && (response.hasNonNull("output") || response.hasNonNull("result")
                || response.hasNonNull("usage") || data.hasNonNull("usage")
                || data.hasNonNull("output") || data.hasNonNull("result")
                || ProviderResponseHelper.readText(response, "video_url", "image_url", "data.video_url", "data.image_url") != null);
        // Some async protocols use id as their task receipt. An error carrying one remains ambiguous.
        boolean explicitRejection = modelUnavailable
                ? ProviderResponseHelper.readText(response, "id", "data.id") == null
                : isFinalHttpRejection(status);
        boolean rejected = explicitRejection
                && (taskId == null || taskId.isBlank()) && !hasResult;
        TaskErrorResult error = modelUnavailable
                ? TaskErrorResult.of(TaskErrorCode.UPSTREAM_MODEL_UNAVAILABLE, message)
                : ErrorNormalizer.classify(null, model, status, message);
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .confirmedFailureCode(rejected ? "PROVIDER_REQUEST_REJECTED" : null)
                .submissionHttpStatus(status).submissionErrorMessage(message)
                .errorDetailJson(TaskErrorSnapshot.write(error))
                .build();
    }

    private static boolean isFinalHttpRejection(int status) {
        return switch (status) {
            case 400, 401, 402, 403, 404, 405, 406, 407, 410, 411, 412,
                    413, 414, 415, 416, 417, 421, 422, 423, 424, 425, 426, 428, 429, 431, 451 -> true;
            default -> false;
        };
    }

    public static ProviderSubmitResult unknownResponse(String message) {
        return ProviderSubmitResult.builder().submissionErrorMessage(
                ProviderErrorSanitizer.safeMessage(message, "上游提交结果无法确认")).build();
    }

    /** Adapter has recognized its documented error envelope and excluded any accepted task/result. */
    public static ProviderSubmitResult reportedFailure(String body, String model) {
        String message = ProviderErrorSanitizer.safeMessage(body, "上游明确返回生成失败");
        return ProviderSubmitResult.builder().confirmedFailureCode("PROVIDER_REPORTED_FAILURE")
                .submissionErrorMessage(message)
                .errorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.classify(null, model, -1, message)))
                .build();
    }
}
