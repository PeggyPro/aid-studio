package com.aid.assistant.creative.series.vo;

import java.util.List;

/** Body-free, stable series target catalog. */
public final class SeriesCatalogViews {
    private SeriesCatalogViews() {
    }

    public record Page(String schemaVersion, String projectId, String projectType,
                       String episodeSetDigest, List<Item> items,
                       Integer nextAfterEpisodeNo, boolean complete) {
    }

    public record Item(String episodeId, Long episodeNo, String title,
                       String currentScriptId, Integer currentScriptVersion,
                       String currentScriptContentHash, String currentTargetRevision,
                       int effectiveCharCount) {
    }
}
