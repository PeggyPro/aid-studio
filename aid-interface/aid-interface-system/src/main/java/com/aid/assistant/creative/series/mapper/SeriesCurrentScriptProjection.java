package com.aid.assistant.creative.series.mapper;

import lombok.Data;

/** Body-free identity/hash projection for a project's current scripts. */
@Data
public class SeriesCurrentScriptProjection {
    private Long id;
    private Long projectId;
    private Long episodeId;
    private Long userId;
    private Integer isExtracted;
    private Integer comicVersion;
    private Integer status;
    private String delFlag;
    private String contentHash;
}
