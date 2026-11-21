package com.aid.assistant.creative.series.dto;

import lombok.Data;

/** Inputs for the project-bound creative series catalog Tool. */
public final class SeriesCatalogRequests {
    private SeriesCatalogRequests() {
    }

    @Data
    public static class Read {
        private Integer afterEpisodeNo;
        private Integer limit;
    }
}
