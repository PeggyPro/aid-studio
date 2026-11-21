package com.aid.media.reconciliation;

import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ProviderErrorSanitizer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Sanitized, replayable subset of an authoritative known-task query result. */
@Data
public class ProviderTaskEvidenceSnapshot {
    private String status;
    private String resultUrl;
    private String errorMessage;
    private Boolean querySuccessful;
    private String providerStatus;
    private Boolean terminalConfirmed;
    private List<String> resultUrls;
    private List<String> persistedResultUrls;
    private List<String> persistedResultMimeTypes;
    private List<Long> persistedResultFileSizes;
    private Integer resultCount;
    private Integer videoDurationSeconds;
    private BigDecimal providerCredits;
    private Integer inputVideoSeconds;
    private BigDecimal videoBillingDurationSeconds;
    private BigDecimal inputVideoBillingDurationSeconds;
    private Integer inputImageCount;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long outputDurationSeconds;
    private Integer progress;
    private java.util.Map<String, Object> usage;
    private String resultText;

    public static ProviderTaskEvidenceSnapshot capture(ProviderTaskResult source) {
        if (source == null) return null;
        ProviderTaskEvidenceSnapshot value = new ProviderTaskEvidenceSnapshot();
        value.status = source.getStatus();
        value.resultUrl = source.getResultUrl();
        value.errorMessage = source.getErrorMessage() == null ? null
                : ProviderErrorSanitizer.safeMessage(source.getErrorMessage(), "Provider task failed");
        value.querySuccessful = source.getQuerySuccessful();
        value.providerStatus = source.getProviderStatus();
        value.terminalConfirmed = source.getTerminalConfirmed();
        value.resultUrls = source.getResultUrls();
        value.persistedResultUrls = source.getPersistedResultUrls();
        value.persistedResultMimeTypes = source.getPersistedResultMimeTypes();
        value.persistedResultFileSizes = source.getPersistedResultFileSizes();
        value.resultCount = source.getResultCount();
        value.videoDurationSeconds = source.getVideoDurationSeconds();
        value.providerCredits = source.getProviderCredits();
        value.inputVideoSeconds = source.getInputVideoSeconds();
        value.videoBillingDurationSeconds = source.getVideoBillingDurationSeconds();
        value.inputVideoBillingDurationSeconds = source.getInputVideoBillingDurationSeconds();
        value.inputImageCount = source.getInputImageCount();
        value.completionTokens = source.getCompletionTokens();
        value.totalTokens = source.getTotalTokens();
        value.outputDurationSeconds = source.getOutputDurationSeconds();
        value.progress = source.getProgress();
        value.usage = source.getUsage();
        value.resultText = com.aid.media.util.MediaTaskPayloadSanitizer.sanitizeForStorage(source.getResultText());
        return value;
    }

    public ProviderTaskResult restore() {
        return ProviderTaskResult.builder()
                .status(status)
                .resultUrl(resultUrl)
                .errorMessage(errorMessage)
                .querySuccessful(querySuccessful)
                .providerStatus(providerStatus)
                .terminalConfirmed(terminalConfirmed)
                .resultUrls(resultUrls)
                .persistedResultUrls(persistedResultUrls)
                .persistedResultMimeTypes(persistedResultMimeTypes)
                .persistedResultFileSizes(persistedResultFileSizes)
                .resultCount(resultCount)
                .videoDurationSeconds(videoDurationSeconds)
                .providerCredits(providerCredits)
                .inputVideoSeconds(inputVideoSeconds)
                .videoBillingDurationSeconds(videoBillingDurationSeconds)
                .inputVideoBillingDurationSeconds(inputVideoBillingDurationSeconds)
                .inputImageCount(inputImageCount)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .outputDurationSeconds(outputDurationSeconds)
                .progress(progress)
                .usage(usage)
                .resultText(resultText)
                .build();
    }
}
