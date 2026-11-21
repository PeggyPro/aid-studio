package com.aid.assistant.creative.series.vo;

import java.util.List;

/** Explicit capability and commit receipts for atomic project-scope series adoption. */
public final class SeriesAdoptionViews {
    private SeriesAdoptionViews() {
    }

    public record Capabilities(String schemaVersion, String mode, String projectId,
                               String projectEpisodeSetDigest, String splitterContractVersion,
                               String capabilityDigest, String splitCapabilitySnapshot,
                               boolean allowed, String reason, List<CapabilityItem> items) {
    }

    public record CapabilityItem(int itemIndex, String artifactId, String artifactRef,
                                 String artifactContentHash, String segmentContentHash,
                                 String episodeId, Long episodeNo, String currentTargetRevision,
                                 String proposedTitle, String proposedDescription,
                                 int sourceCharStart, int sourceCharEnd, int effectiveCharCount,
                                 boolean allowed, String reason) {
    }

    public record Receipt(String schemaVersion, String commandId, String sessionId, String clientRequestId,
                          String mode, String projectId, String beforeEpisodeSetDigest,
                          String afterEpisodeSetDigest, List<ReceiptItem> items) {
    }

    public record ReceiptItem(int itemIndex, String adoptionId, String artifactId,
                              String artifactRef, String artifactContentHash,
                              String segmentContentHash, String targetObjectId,
                              String episodeId, Long episodeNo, String previousTargetRevision,
                              String targetRevision, String createdEpisodeId,
                              String createdScriptId, int sourceCharStart, int sourceCharEnd) {
    }
}
