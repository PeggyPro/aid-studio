package com.aid.assistant.source.vo;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Safe projections. Private storage locators and extracted bodies never leave the service. */
public final class SourceImportViews {
    private SourceImportViews() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(String schemaVersion, List<Summary> items, String nextAfterDocumentId,
                       boolean complete) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String documentId, String sessionId, String projectId,
                          String originalName, String mediaType, long byteCount, String byteHash,
                          String parserCode, String parserVersion, String status, long stateRevision,
                          int partCount, int readyPartCount, int failedPartCount,
                          String sourceRevisionId, String version, String contentHash,
                          Integer charCount, Integer wordCount, String errorCode, List<String> allowedActions) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(String schemaVersion, String documentId, String sessionId, String projectId,
                         String originalName, String mediaType, long byteCount, String byteHash,
                         String parserCode, String parserVersion, String status, long stateRevision,
                         int partCount, int readyPartCount, int failedPartCount,
                         String sourceRevisionId, String version, String sourceRevision,
                         String contentHash, Integer charCount, Integer wordCount,
                         List<Part> parts, Integer nextAfterPartIndex, boolean partsComplete, String errorCode, List<String> allowedActions) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Part(int partIndex, String partKind, Object locator, String status, int attemptCount,
                       Integer charStart, Integer charEnd, Integer charCount, Integer wordCount,
                       String contentHash, String errorCode) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Command(String schemaVersion, String commandId, String clientRequestId,
                          String status, String errorCode, Detail document) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Lookup(String schemaVersion, String clientRequestId, String observation, Command command) { }
}
