package com.aid.media.reconciliation;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ImageOutputItem;
import com.aid.media.provider.ProviderSubmitResult;
import lombok.Data;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitized replay payload captured after a provider returns and before local task handling starts.
 * Raw provider bodies, reasoning and inline binary audio are deliberately excluded.
 */
@Data
public class ProviderSubmissionReceiptSnapshot {
    private String providerTaskId;
    private String directUrl;
    private String directText;
    private MediaTextGenerateRequest.TextMessageItem toolMessage;
    private List<String> resultUrls;
    private List<ImageOutputItem> imageOutputs;
    private List<com.aid.media.provider.AudioOutputItem> audioOutputs;
    private Integer resultCount;
    private Map<String, Object> usage;
    private String ossUrl;
    private Long audioDurationMs;
    private String confirmedFailureCode;
    private Integer submissionHttpStatus;
    private String submissionErrorMessage;
    private String errorDetailJson;
    private boolean inlineAudioOmitted;
    private boolean embeddedBinaryOmitted;

    private static final Pattern DATA_URI = Pattern.compile(
            "(?i)data:[a-z0-9./+_-]{1,80}(?:;[a-z0-9.+_=-]{1,80})*;base64,");
    private static final Pattern LONG_BINARY_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_-]{4096,}={0,2}(?![A-Za-z0-9+/_=-])");

    public static ProviderSubmissionReceiptSnapshot capture(ProviderSubmitResult source) {
        if (source == null) return null;
        ProviderSubmissionReceiptSnapshot value = new ProviderSubmissionReceiptSnapshot();
        value.providerTaskId = source.getProviderTaskId();
        value.directUrl = source.getDirectUrl();
        value.directText = source.getDirectText();
        value.toolMessage = sanitizeToolMessage(source.getToolMessage());
        value.resultUrls = source.getResultUrls();
        value.imageOutputs = source.getImageOutputs();
        value.audioOutputs = source.getAudioOutputs();
        value.resultCount = source.getResultCount();
        value.usage = sanitizeUsage(source.getUsage());
        value.ossUrl = source.getOssUrl();
        value.audioDurationMs = source.getAudioDurationMs();
        value.confirmedFailureCode = source.getConfirmedFailureCode();
        Integer http = source.getSubmissionHttpStatus();
        value.submissionHttpStatus = http != null && http >= 100 && http <= 599 ? http : null;
        value.submissionErrorMessage = source.getSubmissionErrorMessage() == null ? null
                : com.aid.media.provider.ProviderErrorSanitizer.safeMessage(source.getSubmissionErrorMessage(), "上游提交失败");
        value.errorDetailJson = com.aid.media.provider.TextFailureBillingPolicy.sanitizedFailureSnapshot(
                source.getErrorDetailJson());
        value.inlineAudioOmitted = source.getAudioBase64() != null && !source.getAudioBase64().isBlank();
        value.embeddedBinaryOmitted = containsEmbeddedBinary(value);
        return value;
    }

    public ProviderSubmitResult restore() {
        return ProviderSubmitResult.builder()
                .providerTaskId(providerTaskId)
                .directUrl(directUrl)
                .directText(directText)
                .toolMessage(toolMessage)
                .resultUrls(resultUrls)
                .imageOutputs(imageOutputs)
                .audioOutputs(audioOutputs)
                .resultCount(resultCount)
                .usage(usage)
                .ossUrl(ossUrl)
                .audioDurationMs(audioDurationMs)
                .confirmedFailureCode(confirmedFailureCode)
                .submissionHttpStatus(submissionHttpStatus)
                .submissionErrorMessage(submissionErrorMessage)
                .errorDetailJson(errorDetailJson)
                .build();
    }

    public boolean hasTerminalFailure() {
        return confirmedFailureCode != null && confirmedFailureCode.matches("[A-Z][A-Z0-9_]{0,79}")
                && !hasTerminalOutput() && !notBlank(providerTaskId)
                && (resultUrls == null || resultUrls.isEmpty())
                && (imageOutputs == null || imageOutputs.isEmpty())
                && !inlineAudioOmitted && !embeddedBinaryOmitted;
    }

    public boolean hasTerminalOutput() {
        // Keep this exactly aligned with MediaGenerationServiceImpl.handleSubmitResult. Lists are manifests,
        // not independent terminal proof; conforming image adapters also set directUrl to the first item.
        return notBlank(ossUrl) || notBlank(directUrl) || notBlank(directText) || toolMessage != null;
    }

    public boolean isReplayable() {
        return !embeddedBinaryOmitted && (!inlineAudioOmitted || notBlank(ossUrl));
    }

    private static boolean containsEmbeddedBinary(ProviderSubmissionReceiptSnapshot value) {
        if (binaryText(value.directUrl) || binaryText(value.directText) || binaryText(value.ossUrl)
                || binaryStrings(value.resultUrls)) return true;
        if (value.imageOutputs != null) {
            for (ImageOutputItem item : value.imageOutputs) {
                if (item != null && (binaryText(item.getUrl()) || binaryText(item.getName())
                        || binaryText(item.getDescription()) || binaryText(item.getOutputFormat())
                        || binaryMap(item.getBoundingBox()))) return true;
            }
        }
        if (value.audioOutputs != null) for (var item : value.audioOutputs) {
            if (item != null && (binaryText(item.getUrl()) || binaryMap(item.getMetadata()))) return true;
        }
        MediaTextGenerateRequest.TextMessageItem message = value.toolMessage;
        if (message == null) return false;
        if (binaryText(message.getContent()) || binaryText(message.getToolCallId())
                || binaryText(message.getResponseId())) return true;
        if (message.getParts() != null) {
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                if (part != null && (binaryText(part.getText()) || binaryText(part.getUrl()))) return true;
            }
        }
        if (message.getToolCalls() != null) {
            for (MediaTextGenerateRequest.TextToolCall call : message.getToolCalls()) {
                if (call != null && (binaryText(call.getId()) || binaryText(call.getName())
                        || binaryText(call.getArguments()))) return true;
            }
        }
        return false;
    }

    private static boolean binaryStrings(List<String> values) {
        if (values == null) return false;
        for (String value : values) if (binaryText(value)) return true;
        return false;
    }

    private static boolean binaryMap(Map<?, ?> values) {
        if (values == null) return false;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (binaryObject(entry.getKey()) || binaryObject(entry.getValue())) return true;
        }
        return false;
    }

    private static boolean binaryObject(Object value) {
        if (value instanceof String text) return binaryText(text);
        if (value instanceof Map<?, ?> map) return binaryMap(map);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (binaryObject(item)) return true;
        }
        return false;
    }

    private static boolean binaryText(String value) {
        if (value == null || value.isBlank()) return false;
        return DATA_URI.matcher(value).find() || LONG_BINARY_TOKEN.matcher(value).find();
    }

    private static MediaTextGenerateRequest.TextMessageItem sanitizeToolMessage(
            MediaTextGenerateRequest.TextMessageItem source) {
        if (source == null) return null;
        MediaTextGenerateRequest.TextMessageItem value = new MediaTextGenerateRequest.TextMessageItem();
        value.setRole(source.getRole());
        value.setPrefix(source.getPrefix());
        value.setContent(source.getContent());
        value.setParts(source.getParts());
        value.setToolCalls(source.getToolCalls());
        value.setToolCallId(source.getToolCallId());
        value.setResponseId(source.getResponseId());
        value.setToolError(source.getToolError());
        // reasoningContent, thinkingBlocks and provider-native responseItems never enter the journal.
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> sanitizeUsage(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return null;
        Set<String> allowed = Set.of("input_tokens", "output_tokens", "total_tokens", "completion_tokens",
                "prompt_tokens", "cached_tokens", "actualImageCount", "resultCount", "actualDuration",
                "actualProviderCredits", "actualInputVideoSeconds", "actualInputImageCount");
        Map<String, Object> value = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (allowed.contains(key) && item instanceof Number) value.put(key, item);
        });
        return value.isEmpty() ? null : value;
    }
}
