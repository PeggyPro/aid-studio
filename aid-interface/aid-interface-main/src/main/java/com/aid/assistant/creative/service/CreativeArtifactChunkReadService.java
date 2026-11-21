package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;

/** Reads one bounded page from an immutable artifact authorized by a frozen plan input. */
public interface CreativeArtifactChunkReadService {
    CreativeViews.ArtifactChunkPage read(RuntimeToolPreparedInvocation invocation,
                                         CreativeAuditRequests.ArtifactChunkRead request);
}
